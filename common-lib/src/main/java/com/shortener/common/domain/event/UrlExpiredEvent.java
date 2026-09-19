package com.shortener.common.domain.event;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import java.time.Instant;

@Builder
public record UrlExpiredEvent(
    String eventId, String shortCode, Long userId, long totalClicks,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiredAt
) {}
