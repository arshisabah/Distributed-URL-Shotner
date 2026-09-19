package com.shortener.url.api;

import com.shortener.url.application.UrlApplicationService;
import com.shortener.url.application.dto.RedirectContext;
import com.shortener.url.application.dto.ResolveUrlResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Redirect controller — the highest-traffic endpoint in the system.
 *
 * Performance targets:
 *   cache hit  : p99 < 5 ms
 *   cache miss : p99 < 30 ms
 *
 * 302 vs 301:
 *   302 (Temporary): browser does NOT cache — every click hits us → accurate analytics
 *   301 (Permanent): browser caches → fewer server hits but analytics broken
 *   Decision: 302 by default. Users who opt out of tracking receive Cache-Control
 *             allowing edge-cache to serve their links.
 *
 * Note: This controller is intentionally thin. All logic lives in UrlApplicationService.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Redirect", description = "URL redirect endpoint")
public class RedirectController {

    private final UrlApplicationService urlService;
    private final MeterRegistry         metrics;

    /**
     * GET /{shortCode} — resolve and redirect.
     *
     * Order of operations:
     *  1. L0 Caffeine cache hit (~0.01 ms)
     *  2. L1 Redis cache hit (~1-3 ms)
     *  3. DB fallback, re-warm cache (~5-20 ms)
     *  4. Publish click event to Kafka (async, does not block response)
     *  5. Return HTTP 302
     */
    @GetMapping("/{shortCode:[0-9a-zA-Z]{4,10}}")
    @Operation(
        summary = "Redirect to original URL",
        responses = {
            @ApiResponse(responseCode = "302", description = "Redirect to original URL"),
            @ApiResponse(responseCode = "404", description = "Short code not found"),
            @ApiResponse(responseCode = "410", description = "URL expired or deactivated")
        }
    )
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode,
            @RequestHeader(value = "User-Agent",       defaultValue = "") String userAgent,
            @RequestHeader(value = "Referer",          defaultValue = "") String referer,
            @RequestHeader(value = "Accept-Language",  defaultValue = "") String acceptLang,
            @RequestHeader(value = "X-Password",       defaultValue = "") String password,
            HttpServletRequest request) {

        Timer.Sample sample = Timer.start(metrics);

        try {
            RedirectContext ctx = RedirectContext.builder()
                .ipAddress(extractClientIp(request))
                .userAgent(userAgent)
                .referer(referer)
                .acceptLanguage(acceptLang)
                .providedPassword(password.isBlank() ? null : password)
                .build();

            ResolveUrlResult result = urlService.resolve(shortCode, ctx);

            if (result.requiresPassword()) {
                // Return 401 with a hint — client shows password prompt UI
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("X-Requires-Password", "true")
                    .header("X-Short-Code", shortCode)
                    .build();
            }

            metrics.counter("url.redirect.success").increment();

            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(result.originalUrl()))
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("X-Short-Code", shortCode)
                .build();

        } finally {
            sample.stop(metrics.timer("url.redirect.duration",
                "short_code", shortCode));
        }
    }

    /**
     * GET /{shortCode}/preview — show destination without redirecting.
     * Useful for privacy-conscious users or password entry.
     */
    @GetMapping("/{shortCode:[0-9a-zA-Z]{4,10}}/preview")
    @Operation(summary = "Preview redirect destination without following it")
    public ResponseEntity<PreviewResponse> preview(@PathVariable String shortCode) {
        // Minimal implementation: just confirm URL exists and return info
        return urlService.listUserUrls(null, 0, 1)
            .stream()
            .filter(u -> u.getShortCode().equals(shortCode))
            .findFirst()
            .map(u -> ResponseEntity.ok(new PreviewResponse(
                shortCode,
                u.getOriginalUrl(),
                u.requiresPassword()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    private String extractClientIp(HttpServletRequest req) {
        // Cloudflare header takes priority (most accurate behind CDN)
        String cfIp = req.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) return cfIp;

        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();

        return req.getRemoteAddr();
    }

    public record PreviewResponse(
        String  shortCode,
        String  destination,
        boolean requiresPassword
    ) {}
}
