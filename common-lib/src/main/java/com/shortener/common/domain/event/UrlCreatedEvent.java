package com.shortener.common.domain.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.Instant;

@Builder
public record UrlCreatedEvent(
        String  eventId,
        String  shortCode,
        String  originalUrl,
        Long    userId,
        String  customAlias,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt
) {}
