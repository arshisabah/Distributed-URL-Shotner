package com.shortener.url.application.dto;

import lombok.Builder;
import java.time.Instant;

@Builder
public record CreateUrlCommand(
    String  originalUrl,
    String  customAlias,
    Instant expiresAt,
    Long    maxClicks,
    String  password,
    boolean isPrivate,
    String  utmSource,
    String  utmMedium,
    String  utmCampaign,
    String  utmTerm,
    String  utmContent
) {}
