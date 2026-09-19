package com.shortener.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

// ─── Request DTOs ─────────────────────────────────────────────────────────

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class CreateUrlRequest {
    @NotBlank(message = "Original URL is required")
    @Size(max = 2048, message = "URL must not exceed 2048 characters")
    private String originalUrl;

    @Pattern(regexp = "^[a-z0-9][a-z0-9\\-_]{1,48}[a-z0-9]$",
             message = "Alias must be 3-50 chars, alphanumeric with hyphens/underscores")
    private String customAlias;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private Instant expiresAt;

    private Long    maxClicks;
    private String  password;
    private String  utmSource;
    private String  utmMedium;
    private String  utmCampaign;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class CreateUrlResponse {
    private Long    id;
    private String  shortCode;
    private String  shortUrl;
    private String  originalUrl;
    private String  title;
    private String  customAlias;
    private boolean isActive;
    private long    totalClicks;
    private String  qrCodeUrl;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class UrlInfoResponse {
    private Long    id;
    private String  shortCode;
    private String  shortUrl;
    private String  originalUrl;
    private String  title;
    private String  customAlias;
    private boolean isActive;
    private boolean isPrivate;
    private long    totalClicks;
    private Long    maxClicks;
    private boolean requiresPassword;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant updatedAt;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class UpdateUrlRequest {
    private String  title;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;
    private Long    maxClicks;
    private Boolean isPrivate;
}

// ─── Auth DTOs ────────────────────────────────────────────────────────────

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class RegisterRequest {
    @NotBlank private String email;
    @NotBlank @Size(min = 3, max = 30) private String username;
    @NotBlank @Size(min = 8) private String password;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class LoginRequest {
    @NotBlank private String email;
    @NotBlank private String password;
    private String totpCode;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class LoginResponse {
    private String  accessToken;
    private String  refreshToken;
    private long    expiresIn;
    private String  tokenType;
    private UserSummary user;
}

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class UserSummary {
    private Long   id;
    private String email;
    private String username;
    private String tier;
    private boolean emailVerified;
}

// ─── Analytics DTOs ──────────────────────────────────────────────────────

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class AnalyticsSummary {
    private String shortCode;
    private String period;
    private long   totalClicks;
    private long   uniqueClicks;
}

// ─── Generic API wrapper ──────────────────────────────────────────────────

@Data @Builder @NoArgsConstructor @AllArgsConstructor
class ApiResponse<T> {
    private boolean success;
    private String  message;
    private T       data;

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder().success(true).data(data).build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder().success(false).message(message).build();
    }
}
