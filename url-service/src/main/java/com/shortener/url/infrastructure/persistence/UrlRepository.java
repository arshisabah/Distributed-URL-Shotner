package com.shortener.url.infrastructure.persistence;

import com.shortener.url.domain.Url;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for URL persistence.
 *
 * Key design decisions:
 * - findByShortCode: partial index (WHERE is_active = TRUE) → index-only scan
 * - findExpiredActive: partial index (WHERE expires_at IS NOT NULL AND is_active = TRUE)
 * - bulkDeactivate: single UPDATE IN clause instead of N individual updates
 * - findTopByClicks: for cache warming on startup
 */
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {

    // ─── Hot path ─────────────────────────────────────────────────────────

    Optional<Url> findByShortCodeAndActiveTrue(String shortCode);

    boolean existsByShortCodeAndActiveTrue(String shortCode);

    boolean existsByCustomAliasAndActiveTrue(String customAlias);

    // ─── User URL management ──────────────────────────────────────────────

    Page<Url> findByUserIdAndActiveTrueOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Url> findByShortCodeAndUserIdAndActiveTrue(String shortCode, Long userId);

    long countByUserIdAndActiveTrue(Long userId);

    // Idempotency: find existing URL for same user + same target
    @Query("""
        SELECT u FROM Url u
        WHERE u.userId = :userId
          AND u.originalUrl = :originalUrl
          AND u.active = true
        ORDER BY u.createdAt DESC
        """)
    List<Url> findActiveByUserIdAndOriginalUrl(
        @Param("userId") Long userId,
        @Param("originalUrl") String originalUrl,
        Pageable pageable);

    // ─── Expiry scheduler queries ──────────────────────────────────────────

    @Query("""
        SELECT u FROM Url u
        WHERE u.active = true
          AND u.expiresAt IS NOT NULL
          AND u.expiresAt < :now
        ORDER BY u.expiresAt ASC
        """)
    Page<Url> findExpiredActiveUrls(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Url u SET u.active = false, u.updatedAt = :now WHERE u.id IN :ids")
    int bulkDeactivate(@Param("ids") List<Long> ids, @Param("now") Instant now);

    // ─── Cache warming ────────────────────────────────────────────────────

    @Query("""
        SELECT u FROM Url u
        WHERE u.active = true
        ORDER BY u.totalClicks DESC
        """)
    List<Url> findTopByClicksDesc(Pageable pageable);

    @Query("""
        SELECT u FROM Url u
        WHERE u.active = true
          AND u.createdAt > :since
        ORDER BY u.createdAt DESC
        """)
    List<Url> findRecentlyCreated(@Param("since") Instant since);

    // ─── Admin / analytics ────────────────────────────────────────────────

    long countByActiveTrue();

    @Query("SELECT COUNT(u) FROM Url u WHERE u.createdAt > :since")
    long countCreatedSince(@Param("since") Instant since);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Url u SET u.active = false, u.updatedAt = :now WHERE u.userId = :userId")
    int deactivateAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}
