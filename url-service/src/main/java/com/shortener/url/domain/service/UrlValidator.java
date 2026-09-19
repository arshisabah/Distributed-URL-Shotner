package com.shortener.url.domain.service;

import com.shortener.common.domain.exception.BlockedDomainException;
import com.shortener.common.domain.exception.UrlValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Multi-layer URL validator.
 *
 * Validation order (fail-fast, cheapest first):
 *  1. Null / blank check       ~0 ns
 *  2. Length limit             ~0 ns
 *  3. URI parse + scheme check ~1 µs
 *  4. SSRF / private-IP check  ~1 ms  (DNS resolution)
 *  5. Domain blocklist         ~0.1 ms (HashSet lookup)
 *  6. Self-referential         ~0 ns
 *
 * Safe Browsing API (async, run in background after URL is created).
 */
@Service
@Slf4j
public class UrlValidator {

    private static final int    MAX_URL_LENGTH  = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Set<String> blocklist;
    private final String selfDomain;

    public UrlValidator(
            @Value("${app.blocked-domains:}") String blockedDomainsConfig,
            @Value("${app.base-url:https://sho.rt}") String baseUrl) {

        // Parse comma-separated blocked domains from config
        this.blocklist = parseBlocklist(blockedDomainsConfig);

        // Extract own domain to prevent self-referential shortening
        this.selfDomain = extractDomain(baseUrl);

        log.info("UrlValidator initialized: selfDomain={}, blockedDomains={}",
            selfDomain, blocklist.size());
    }

    public void validate(String rawUrl) {
        // ── Stage 1: Basic null/blank/length ────────────────────────────────
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new UrlValidationException("URL must not be empty");
        }
        String url = rawUrl.trim();
        if (url.length() > MAX_URL_LENGTH) {
            throw new UrlValidationException(
                "URL length " + url.length() + " exceeds maximum of " + MAX_URL_LENGTH);
        }

        // ── Stage 2: URI parse and scheme check ─────────────────────────────
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new UrlValidationException("Malformed URL: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new UrlValidationException(
                "Only HTTP/HTTPS URLs are allowed. Received scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UrlValidationException("URL has no valid host");
        }

        // ── Stage 3: SSRF prevention — block private/internal IPs ──────────
        validateNotPrivateAddress(host);

        // ── Stage 4: Domain blocklist ──────────────────────────────────────
        String hostLower = host.toLowerCase();
        if (blocklist.contains(hostLower)) {
            throw new BlockedDomainException(host);
        }
        // Also check parent domain (e.g., subdomain.blocked.com)
        blocklist.stream()
            .filter(blocked -> hostLower.endsWith("." + blocked))
            .findFirst()
            .ifPresent(blocked -> { throw new BlockedDomainException(host); });

        // ── Stage 5: No self-referential URLs ─────────────────────────────
        if (selfDomain != null && hostLower.equals(selfDomain)) {
            throw new UrlValidationException(
                "Cannot shorten URLs from this service (" + selfDomain + ")");
        }
    }

    /**
     * Blocks use of our service as an SSRF proxy to access internal infrastructure.
     * Covers RFC 1918 private ranges, loopback, link-local, and AWS metadata.
     */
    private void validateNotPrivateAddress(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isSiteLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isMulticastAddress()
                    || addr.isAnyLocalAddress()) {
                throw new UrlValidationException(
                    "URLs pointing to private/internal network addresses are not allowed");
            }
            // Block AWS Instance Metadata Service explicitly
            if ("169.254.169.254".equals(addr.getHostAddress())) {
                throw new UrlValidationException("Access to cloud metadata endpoints is blocked");
            }
        } catch (UnknownHostException e) {
            // Cannot resolve — allow (DNS check is best-effort, not hard block)
            log.debug("Could not resolve host during SSRF check: {} — allowing", host);
        }
    }

    private Set<String> parseBlocklist(String config) {
        if (config == null || config.isBlank()) return Set.of();
        return Set.of(config.split(",")).stream()
            .map(String::trim)
            .map(String::toLowerCase)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
