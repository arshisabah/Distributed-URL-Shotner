package com.shortener.common.domain.event;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import java.time.Instant;

@Builder
public record UserRegisteredEvent(
    Long userId, String email, String username,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant registeredAt
) {}
