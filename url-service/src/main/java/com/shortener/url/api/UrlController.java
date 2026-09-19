package com.shortener.url.api;

import com.shortener.url.application.QrCodeService;
import com.shortener.url.application.UrlApplicationService;
import com.shortener.url.application.dto.CreateUrlCommand;
import com.shortener.url.application.dto.CreateUrlResult;
import com.shortener.url.domain.Url;
import com.shortener.url.infrastructure.cache.RateLimiterService;
import com.shortener.url.infrastructure.security.JwtUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * REST controller for URL management (create, list, info, update, delete, QR).
 *
 * Authentication: JWT Bearer or X-API-Key header (both supported).
 * Anonymous URL creation is permitted with stricter rate limits.
 */
@RestController
@RequestMapping("/api/v1/urls")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "URL Management", description = "Shorten, manage, and inspect URLs")
public class UrlController {

    private final UrlApplicationService urlService;
    private final QrCodeService         qrService;
    private final RateLimiterService    rateLimiter;

    // ─── Create URL ───────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Shorten a URL")
    public ResponseEntity<CreateUrlResult> create(
            @Valid @RequestBody CreateRequest req,
            @AuthenticationPrincipal JwtUserDetails user,
            HttpServletRequest httpReq) {

        Long userId = (user != null) ? user.getUserId() : null;

        // Rate limiting: authenticated users use tier limits, anonymous use IP limit
        if (userId != null) {
            int burst   = user.getBurstCapacity();
            int refill  = user.getRefillRate();
            rateLimiter.checkUserLimit(userId, burst, refill);
        } else {
            rateLimiter.checkIpLimit(extractIp(httpReq));
        }

        CreateUrlCommand cmd = CreateUrlCommand.builder()
            .originalUrl(req.getOriginalUrl())
            .customAlias(req.getCustomAlias())
            .expiresAt(req.getExpiresAt())
            .maxClicks(req.getMaxClicks())
            .password(req.getPassword())
            .isPrivate(Boolean.TRUE.equals(req.getIsPrivate()))
            .utmSource(req.getUtmSource())
            .utmMedium(req.getUtmMedium())
            .utmCampaign(req.getUtmCampaign())
            .build();

        CreateUrlResult result = urlService.createUrl(cmd, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    // ─── List user's URLs ─────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List your shortened URLs (paginated)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Page<UrlSummary>> list(
            @AuthenticationPrincipal JwtUserDetails user,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Url> urls = urlService.listUserUrls(user.getUserId(),
            Math.max(0, page), Math.min(100, size));
        return ResponseEntity.ok(urls.map(UrlSummary::from));
    }

    // ─── Get URL info ─────────────────────────────────────────────────────

    @GetMapping("/{shortCode}/info")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get URL metadata (no redirect)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<UrlSummary> info(
            @PathVariable @Pattern(regexp = "^[0-9a-zA-Z]{4,10}$") String shortCode,
            @AuthenticationPrincipal JwtUserDetails user) {

        Url url = urlService.getUrlForOwner(shortCode, user.getUserId());
        return ResponseEntity.ok(UrlSummary.from(url));
    }

    // ─── Delete (deactivate) URL ──────────────────────────────────────────

    @DeleteMapping("/{shortCode}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Deactivate (soft-delete) a URL",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> delete(
            @PathVariable @Pattern(regexp = "^[0-9a-zA-Z]{4,10}$") String shortCode,
            @AuthenticationPrincipal JwtUserDetails user) {

        urlService.deactivateUrl(shortCode, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    // ─── QR code ─────────────────────────────────────────────────────────

    @GetMapping("/{shortCode}/qr")
    @Operation(summary = "Generate QR code for a shortened URL")
    public ResponseEntity<byte[]> qrCode(
            @PathVariable @Pattern(regexp = "^[0-9a-zA-Z]{4,10}$") String shortCode,
            @RequestParam(defaultValue = "300") int size,
            @RequestParam(defaultValue = "png") String format) {

        byte[] png = qrService.getQrCode(shortCode, size, format);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "image/png")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=604800") // 7 days
            .body(png);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private String extractIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String cfIp = req.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) return cfIp;
        return req.getRemoteAddr();
    }

    // ─── Request / Response DTOs ──────────────────────────────────────────

    @Data
    public static class CreateRequest {
        @NotBlank(message = "originalUrl is required")
        @Size(max = 2048)
        private String  originalUrl;

        @Pattern(regexp = "^[a-z0-9][a-z0-9\\-_]{1,48}[a-z0-9]$",
                 message = "alias must be 3-50 chars: a-z, 0-9, hyphens, underscores")
        private String  customAlias;

        private Instant expiresAt;
        private Long    maxClicks;
        private String  password;
        private Boolean isPrivate;
        private String  utmSource;
        private String  utmMedium;
        private String  utmCampaign;
    }

    @Builder
    public record UrlSummary(
        Long    id,
        String  shortCode,
        String  shortUrl,
        String  originalUrl,
        String  title,
        String  customAlias,
        boolean isActive,
        long    totalClicks,
        Long    maxClicks,
        boolean requiresPassword,
        Instant expiresAt,
        Instant createdAt
    ) {
        public static UrlSummary from(Url u) {
            return UrlSummary.builder()
                .id(u.getId())
                .shortCode(u.getShortCode())
                .shortUrl("https://sho.rt/" + u.getShortCode())
                .originalUrl(u.getOriginalUrl())
                .title(u.getTitle())
                .customAlias(u.getCustomAlias())
                .isActive(u.isActive())
                .totalClicks(u.getTotalClicks())
                .maxClicks(u.getMaxClicks())
                .requiresPassword(u.requiresPassword())
                .expiresAt(u.getExpiresAt())
                .createdAt(u.getCreatedAt())
                .build();
        }
    }
}
