package com.shortener.gateway;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

// ─── Application ─────────────────────────────────────────────────────────────

@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}

// ─── Route Configuration ──────────────────────────────────────────────────────

@Configuration
@Slf4j
class RouteConfig {

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder,
                        JwtGatewayFilter    jwtFilter) {

        return builder.routes()

            // ── Auth service (public) ──────────────────────────────────────
            .route("auth-service", r -> r
                .path("/api/v1/auth/**")
                .filters(f -> f
                    .addRequestHeader("X-Gateway-Route", "auth-service")
                    .circuitBreaker(c -> c.setName("auth-cb")
                        .setFallbackUri("forward:/fallback")))
                .uri("${services.auth-url:http://localhost:8081}"))

            // ── URL management API (write) ─────────────────────────────────
            .route("url-service-write", r -> r
                .path("/api/v1/urls/**")
                .and().method(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
                .filters(f -> f
                    .filter(jwtFilter)
                    .addRequestHeader("X-Gateway-Route", "url-service")
                    .circuitBreaker(c -> c.setName("url-cb")
                        .setFallbackUri("forward:/fallback")))
                .uri("${services.url-url:http://localhost:8080}"))

            // ── URL management API (read) ──────────────────────────────────
            .route("url-service-read", r -> r
                .path("/api/v1/urls/**")
                .and().method(HttpMethod.GET)
                .filters(f -> f
                    .filter(jwtFilter)
                    .circuitBreaker(c -> c.setName("url-cb")
                        .setFallbackUri("forward:/fallback")))
                .uri("${services.url-url:http://localhost:8080}"))

            // ── Redirect hot path (minimal filters — performance critical) ─
            .route("url-redirect", r -> r
                .path("/{shortCode:[0-9a-zA-Z]{4,10}}")
                .and().method(HttpMethod.GET)
                .filters(f -> f
                    .addRequestHeader("X-Forwarded-Via", "api-gateway"))
                .uri("${services.url-url:http://localhost:8080}"))

            // ── Analytics ─────────────────────────────────────────────────
            .route("analytics", r -> r
                .path("/api/v1/analytics/**")
                .filters(f -> f
                    .filter(jwtFilter)
                    .circuitBreaker(c -> c.setName("analytics-cb")
                        .setFallbackUri("forward:/fallback")))
                .uri("${services.analytics-url:http://localhost:8082}"))

            .build();
    }
}

// ─── JWT Gateway Filter ───────────────────────────────────────────────────────

@Component
@Slf4j
class JwtGatewayFilter implements GatewayFilter {

    private final RSAPublicKey publicKey;

    JwtGatewayFilter(
            @Value("${jwt.public-key:}") String pubPem) throws Exception {
        if (pubPem == null || pubPem.isBlank()) {
            this.publicKey = null; // dev mode — skip validation
        } else {
            String stripped = pubPem.replaceAll("-----.*?-----", "").replaceAll("\\s+", "");
            byte[] decoded  = Base64.getDecoder().decode(stripped);
            this.publicKey  = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decoded));
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String header = exchange.getRequest().getHeaders()
            .getFirst(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.startsWith("Bearer ")) {
            // Anonymous — forward request without user context
            return chain.filter(addAnonymousHeaders(exchange));
        }

        if (publicKey == null) {
            // Dev mode — trust the token without verifying
            return chain.filter(exchange);
        }

        try {
            DecodedJWT jwt = JWT.require(Algorithm.RSA256(publicKey, null))
                .withIssuer("shortener-auth-service")
                .build()
                .verify(header.substring(7));

            // Propagate JWT claims as headers to downstream services
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id",    jwt.getSubject())
                .header("X-User-Email", jwt.getClaim("email").asString())
                .header("X-User-Tier",  jwt.getClaim("tier").asString())
                .build();

            return chain.filter(exchange.mutate().request(mutated).build());

        } catch (JWTVerificationException e) {
            log.debug("Invalid JWT at gateway: {}", e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private ServerWebExchange addAnonymousHeaders(ServerWebExchange exchange) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
            .header("X-User-Id",   "")
            .header("X-User-Tier", "FREE")
            .build();
        return exchange.mutate().request(mutated).build();
    }
}

// ─── Fallback Controller ──────────────────────────────────────────────────────

@RestController
@Slf4j
class FallbackController {

    @GetMapping("/fallback")
    Map<String, Object> fallback() {
        log.warn("Circuit breaker fallback triggered");
        return Map.of(
            "status",  503,
            "title",   "Service Temporarily Unavailable",
            "detail",  "The requested service is temporarily unavailable. Please retry in a moment.",
            "retryAfter", 30
        );
    }
}
