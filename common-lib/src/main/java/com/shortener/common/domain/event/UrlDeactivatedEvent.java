package com.shortener.common.domain.event;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import java.time.Instant;

@Builder
public record UrlDeactivatedEvent(
    String eventId, String shortCode, Long actorId, String reason,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant deactivatedAt
) {}
