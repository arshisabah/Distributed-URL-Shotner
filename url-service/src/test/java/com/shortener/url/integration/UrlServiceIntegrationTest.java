package com.shortener.url.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortener.url.api.UrlController.CreateRequest;
import com.shortener.url.infrastructure.persistence.UrlRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.Map;

/**
 * Full integration test: spins up PostgreSQL, Redis, and Kafka via Testcontainers.
 * Tests the complete request lifecycle from HTTP → service → DB → Kafka.
 *
 * Runs in CI; takes 60-90 s due to container startup.
 * Use @Tag("integration") to exclude from fast unit-test runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"jwt.public-key=", "app.base-url=http://localhost"})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("URL Service — Integration Tests")
class UrlServiceIntegrationTest {

    // ─── Containers (shared for the entire test class) ────────────────────

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("urlshortener")
            .withUsername("shortener")
            .withPassword("testpassword");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server --save '' --appendonly no");

    @Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",          postgres::getJdbcUrl);
        registry.add("spring.datasource.username",     postgres::getUsername);
        registry.add("spring.datasource.password",     postgres::getPassword);
        registry.add("spring.data.redis.host",         redis::getHost);
        registry.add("spring.data.redis.port",         () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("app.worker-id",                  () -> "1");
        registry.add("app.instance-id",                () -> "test-1");
    }

    // ─── Test fixtures ────────────────────────────────────────────────────

    @Autowired MockMvc         mvc;
    @Autowired ObjectMapper    json;
    @Autowired UrlRepository   urlRepo;
    @Autowired StringRedisTemplate redis;

    // State shared across ordered tests
    static String createdShortCode;

    // ─── Tests ────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /api/v1/urls — creates URL, returns 201 with shortCode")
    void createUrl_shouldReturn201_andShortCode() throws Exception {
        CreateRequest req = new CreateRequest();
        req.setOriginalUrl("https://www.example.com/integration-test-path");

        MvcResult result = mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.shortCode").exists())
            .andExpect(jsonPath("$.shortCode", matchesPattern("[0-9a-zA-Z]{7}")))
            .andExpect(jsonPath("$.originalUrl").value("https://www.example.com/integration-test-path"))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.totalClicks").value(0))
            .andReturn();

        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        createdShortCode = (String) body.get("shortCode");
        assertThat(createdShortCode).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("Created URL is persisted in PostgreSQL")
    void createUrl_shouldPersistToDatabase() {
        assertThat(createdShortCode).isNotNull();
        assertThat(urlRepo.findByShortCodeAndActiveTrue(createdShortCode)).isPresent();
    }

    @Test
    @Order(3)
    @DisplayName("Created URL is cached in Redis within 2 seconds")
    void createUrl_shouldWarmRedisCache() {
        assertThat(createdShortCode).isNotNull();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            String cached = redis.opsForValue().get("url:" + createdShortCode);
            assertThat(cached).isNotNull();
            assertThat(cached).contains("example.com");
        });
    }

    @Test
    @Order(4)
    @DisplayName("GET /{shortCode} — redirects to original URL with 302")
    void redirect_shouldReturn302_withLocationHeader() throws Exception {
        assertThat(createdShortCode).isNotNull();

        mvc.perform(get("/" + createdShortCode))
            .andExpect(status().isFound())
            .andExpect(header().string("Location",
                "https://www.example.com/integration-test-path"))
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(header().string("Cache-Control", containsString("no-cache")));
    }

    @Test
    @Order(5)
    @DisplayName("GET /{shortCode}/qr — returns PNG bytes with 200")
    void qrCode_shouldReturn200_withPngBytes() throws Exception {
        assertThat(createdShortCode).isNotNull();

        mvc.perform(get("/api/v1/urls/" + createdShortCode + "/qr")
                .param("size", "200"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/v1/urls — rejects invalid URL (SSRF attempt) with 422")
    void createUrl_withPrivateIp_shouldReturn422() throws Exception {
        CreateRequest req = new CreateRequest();
        req.setOriginalUrl("http://192.168.1.1/admin");

        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.title").exists());
    }

    @Test
    @Order(7)
    @DisplayName("POST /api/v1/urls — rejects javascript: scheme with 422")
    void createUrl_withJavascriptScheme_shouldReturn422() throws Exception {
        CreateRequest req = new CreateRequest();
        req.setOriginalUrl("javascript:alert(document.cookie)");

        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @Order(8)
    @DisplayName("GET /nonexistent — returns 404 for unknown short code")
    void redirect_unknownCode_shouldReturn404() throws Exception {
        mvc.perform(get("/aaaaaaa"))
            .andExpect(status().isNotFound());
    }

    @Test
    @Order(9)
    @DisplayName("Actuator health endpoints return UP")
    void actuatorHealth_shouldReturnUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
