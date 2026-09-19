package com.shortener.url.application.dto;

import com.shortener.url.domain.Url;
import lombok.Builder;
import java.time.Instant;

@Builder
public record CreateUrlResult(
    Long    id,
    String  shortCode,
    String  shortUrl,
    String  originalUrl,
    String  title,
    String  customAlias,
    boolean isActive,
    long    totalClicks,
    String  qrCodeUrl,
    Instant expiresAt,
    Instant createdAt
) {
    public static CreateUrlResult from(Url url, String baseUrl) {
        return CreateUrlResult.builder()
            .id(url.getId())
            .shortCode(url.getShortCode())
            .shortUrl(baseUrl + "/" + url.getShortCode())
            .originalUrl(url.getOriginalUrl())
            .title(url.getTitle())
            .customAlias(url.getCustomAlias())
            .isActive(url.isActive())
            .totalClicks(url.getTotalClicks())
            .qrCodeUrl(baseUrl + "/api/v1/urls/" + url.getShortCode() + "/qr")
            .expiresAt(url.getExpiresAt())
            .createdAt(url.getCreatedAt())
            .build();
    }
}
