package com.shortener.common.domain.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.Instant;

/**
 * Immutable Kafka event: fired on every URL redirect.
 * Partition key = shortCode → all clicks for a URL go to same partition.
 */
@Builder
public record UrlClickedEvent(
        String  eventId,
        String  shortCode,
        String  originalUrl,
        Long    userId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant clickedAt,
        String  ipAddress,
        String  userAgent,
        String  referer,
        String  acceptLanguage,
        // Enriched by analytics consumer
        String  country,
        String  region,
        String  city,
        String  deviceType,
        String  browser,
        String  operatingSystem,
        boolean isBot
) {
    public String partitionKey() { return shortCode; }
}
