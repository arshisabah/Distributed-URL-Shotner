package com.shortener.url.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Lightweight URL representation stored in Redis.
 *
 * Contains only the fields needed for the redirect decision + analytics event.
 * Deliberately NOT the full Url entity — smaller = faster Redis serialization.
 *
 * Typical size: ~300 bytes (vs full entity ~800+ bytes with all UTM fields).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedUrl implements Serializable {

    private Long    id;
    private String  shortCode;
    private String  originalUrl;
    private Long    userId;
    private boolean active;
    private boolean requiresPassword;
    private Long    maxClicks;
    private long    totalClicks;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;

    // ─── Business rules (same logic as Url entity) ────────────────────────

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isClickLimitReached() {
        return maxClicks != null && totalClicks >= maxClicks;
    }

    public boolean isRedirectAllowed() {
        return active && !isExpired() && !isClickLimitReached();
    }

    // ─── Factory ──────────────────────────────────────────────────────────

    public static CachedUrl from(Url url) {
        return CachedUrl.builder()
            .id(url.getId())
            .shortCode(url.getShortCode())
            .originalUrl(url.getOriginalUrl())
            .userId(url.getUserId())
            .active(url.isActive())
            .requiresPassword(url.requiresPassword())
            .maxClicks(url.getMaxClicks())
            .totalClicks(url.getTotalClicks())
            .expiresAt(url.getExpiresAt())
            .build();
    }
}
