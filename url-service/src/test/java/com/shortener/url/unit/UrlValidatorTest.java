package com.shortener.url.unit;

import com.shortener.common.domain.exception.BlockedDomainException;
import com.shortener.common.domain.exception.UrlValidationException;
import com.shortener.url.domain.service.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("UrlValidator")
class UrlValidatorTest {

    private UrlValidator validator;

    @BeforeEach
    void setUp() {
        // Instantiate directly — no Spring context needed for unit tests
        validator = new UrlValidator(
            "malware-example.com,phishing-example.net", // blocked domains
            "https://sho.rt"                             // self domain
        );
    }

    // ─── Valid URLs ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.google.com",
        "https://example.com/path?query=value",
        "http://blog.example.org/post/1",
        "https://example.com:8080/api"
    })
    @DisplayName("accepts valid HTTP/HTTPS URLs")
    void validUrlsShouldPass(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    // ─── Invalid scheme ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://files.example.com",
        "file:///etc/passwd",
        "data:text/html,<script>alert(1)</script>",
        "javascript:alert(1)",
        "mailto:user@example.com"
    })
    @DisplayName("rejects non-HTTP/HTTPS schemes")
    void nonHttpSchemesShouldThrow(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(UrlValidationException.class)
            .hasMessageContaining("Only HTTP/HTTPS");
    }

    // ─── SSRF prevention ─────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "http://192.168.1.1/admin",
        "http://10.0.0.1/internal",
        "http://172.16.0.1/secret",
        "http://127.0.0.1/localhost",
        "http://localhost/admin",
        "http://169.254.169.254/latest/meta-data/"
    })
    @DisplayName("blocks private / internal IP addresses (SSRF prevention)")
    void privateIpsShouldThrow(String url) {
        assertThatThrownBy(() -> validator.validate(url))
            .isInstanceOf(UrlValidationException.class);
    }

    // ─── Blocklist ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("rejects URLs from the domain blocklist")
    void blockedDomainShouldThrow() {
        assertThatThrownBy(() -> validator.validate("https://malware-example.com/payload"))
            .isInstanceOf(BlockedDomainException.class);
        assertThatThrownBy(() -> validator.validate("https://phishing-example.net/login"))
            .isInstanceOf(BlockedDomainException.class);
    }

    @Test
    @DisplayName("rejects subdomains of blocked domains")
    void subdomainOfBlockedDomainShouldThrow() {
        assertThatThrownBy(() -> validator.validate("https://cdn.malware-example.com/file.js"))
            .isInstanceOf(BlockedDomainException.class);
    }

    // ─── Edge cases ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("rejects null URL")
    void nullShouldThrow() {
        assertThatThrownBy(() -> validator.validate(null))
            .isInstanceOf(UrlValidationException.class);
    }

    @Test
    @DisplayName("rejects blank URL")
    void blankShouldThrow() {
        assertThatThrownBy(() -> validator.validate("   "))
            .isInstanceOf(UrlValidationException.class);
    }

    @Test
    @DisplayName("rejects URL exceeding 2048 characters")
    void tooLongUrlShouldThrow() {
        String longUrl = "https://example.com/" + "a".repeat(2030);
        assertThatThrownBy(() -> validator.validate(longUrl))
            .isInstanceOf(UrlValidationException.class)
            .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("rejects self-referential URLs")
    void selfReferentialUrlShouldThrow() {
        assertThatThrownBy(() -> validator.validate("https://sho.rt/abc1234"))
            .isInstanceOf(UrlValidationException.class)
            .hasMessageContaining("sho.rt");
    }
}
