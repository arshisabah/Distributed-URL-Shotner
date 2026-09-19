package com.shortener.url.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * User entity — managed by auth-service but referenced by url-service
 * for ownership checks and tier-based limits.
 *
 * URL service reads this table; auth-service owns writes.
 * In a fully decoupled deployment, url-service would cache user data
 * received via Kafka (user.registered / user.updated events).
 */
@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "idx_users_email",    columnList = "email",    unique = true),
        @Index(name = "idx_users_username", columnList = "username", unique = true)
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "email", length = 255, nullable = false, unique = true)
    private String email;

    @Column(name = "username", length = 50, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 20, nullable = false)
    @Builder.Default
    private UserTier tier = UserTier.FREE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "two_factor_enabled", nullable = false)
    @Builder.Default
    private boolean twoFactorEnabled = false;

    @Column(name = "two_factor_secret")
    private String twoFactorSecret;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "timezone", length = 50)
    @Builder.Default
    private String timezone = "UTC";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ─── Business rules ───────────────────────────────────────────────────

    public boolean isActive() { return status == UserStatus.ACTIVE && deletedAt == null; }

    public boolean canCreateCustomAlias() {
        return tier == UserTier.PRO || tier == UserTier.ENTERPRISE;
    }

    public int getUrlCreationRateLimitPerMinute() {
        return switch (tier) {
            case FREE       -> 10;
            case PRO        -> 100;
            case ENTERPRISE -> 1000;
        };
    }

    public int getMonthlyUrlLimit() {
        return switch (tier) {
            case FREE       -> 50;
            case PRO        -> 5000;
            case ENTERPRISE -> Integer.MAX_VALUE;
        };
    }

    public enum UserTier   { FREE, PRO, ENTERPRISE }
    public enum UserStatus { PENDING_VERIFICATION, ACTIVE, SUSPENDED, DELETED }
}
