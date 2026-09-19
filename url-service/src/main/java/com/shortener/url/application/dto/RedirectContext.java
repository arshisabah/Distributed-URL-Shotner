package com.shortener.url.application.dto;

import lombok.Builder;

@Builder
public record RedirectContext(
    String ipAddress,
    String userAgent,
    String referer,
    String acceptLanguage,
    String providedPassword
) {
    public boolean hasValidPassword(String hash) {
        if (hash == null || hash.isBlank()) return true;
        if (providedPassword == null) return false;
        // Real impl uses BCrypt.matches; simplified here
        return org.springframework.security.crypto.bcrypt.BCrypt.checkpw(providedPassword, hash);
    }
}
