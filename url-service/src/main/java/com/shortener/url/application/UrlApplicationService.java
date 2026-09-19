package com.shortener.url.application;

import com.shortener.common.domain.event.UrlCreatedEvent;
import com.shortener.common.domain.event.UrlDeactivatedEvent;
import com.shortener.common.domain.event.UrlClickedEvent;
import com.shortener.common.domain.exception.*;
import com.shortener.url.application.dto.*;
import com.shortener.url.domain.CachedUrl;
import com.shortener.url.domain.Url;
import com.shortener.url.domain.service.ShortCodeGenerator;
import com.shortener.url.domain.service.UrlValidator;
import com.shortener.url.infrastructure.persistence.UrlRepository;
import com.shortener.common.util.SnowflakeIdGenerator;
import com.shortener.url.port.in.CreateUrlUseCase;
import com.shortener.url.port.in.ResolveUrlUseCase;
import com.shortener.url.port.out.UrlCachePort;
import com.shortener.url.port.out.UrlEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Core application service: URL creation and redirect resolution.
 *
 * Implements the Ports & Adapters (Hexagonal) pattern:
 *   - Inbound ports: CreateUrlUseCase, ResolveUrlUseCase
 *   - Outbound ports: UrlCachePort, UrlEventPublisher, UrlRepository
 *
 * All infrastructure concerns (Redis, Kafka, JPA) are behind port interfaces;
 * this class contains only orchestration logic.
 *
 * Performance targets:
 *   createUrl : p99 < 100 ms  (synchronous DB write + async cache/event)
 *   resolve   : p99 <   5 ms  (cache hit) / < 30 ms (DB fallback)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlApplicationService implements CreateUrlUseCase, ResolveUrlUseCase {

    private final UrlRepository      urlRepo;
    private final UrlCachePort       cachePort;
    private final UrlEventPublisher  eventPublisher;
    private final ShortCodeGenerator codeGenerator;
    private final UrlValidator       urlValidator;
    private final PasswordEncoder      passwordEncoder;
    private final SnowflakeIdGenerator snowflake;
    private final MeterRegistry        metrics;

    @Value("${app.base-url:https://sho.rt}")
    private String baseUrl;

    // ─── CreateUrlUseCase ─────────────────────────────────────────────────

    @Override
    @Transactional
    public CreateUrlResult createUrl(CreateUrlCommand cmd, Long userId) {
        Timer.Sample timer = Timer.start(metrics);
        try {
            // 1. Validate original URL (SSRF, scheme, blocklist)
            urlValidator.validate(cmd.originalUrl());

            // 2. Validate / reserve custom alias
            if (cmd.customAlias() != null) {
                validateCustomAlias(cmd.customAlias());
            }

            // 3. Idempotency: return existing URL if same user + same target
            if (userId != null) {
                Optional<Url> existing = findExistingUrl(userId, cmd.originalUrl());
                if (existing.isPresent()) {
                    log.debug("Idempotent URL create: returning existing shortCode={}",
                        existing.get().getShortCode());
                    return CreateUrlResult.from(existing.get(), baseUrl);
                }
            }

            // 4. Generate short code
            String shortCode = (cmd.customAlias() != null)
                ? cmd.customAlias()
                : codeGenerator.generate();

            // 5. Hash password if provided
            String pwHash = (cmd.password() != null && !cmd.password().isBlank())
                ? passwordEncoder.encode(cmd.password())
                : null;

            // 6. Persist (synchronous — durability required before returning)
            Url url = Url.builder()
                .id(generateId())
                .shortCode(shortCode)
                .originalUrl(cmd.originalUrl())
                .userId(userId)
                .customAlias(cmd.customAlias())
                .expiresAt(cmd.expiresAt())
                .maxClicks(cmd.maxClicks())
                .passwordHash(pwHash)
                .privateUrl(cmd.isPrivate())
                .utmSource(cmd.utmSource())
                .utmMedium(cmd.utmMedium())
                .utmCampaign(cmd.utmCampaign())
                .utmTerm(cmd.utmTerm())
                .utmContent(cmd.utmContent())
                .build();

            Url saved = urlRepo.save(url);

            // 7. Warm cache asynchronously (do NOT block the HTTP response)
            warmCacheAsync(saved);

            // 8. Publish domain event asynchronously
            publishCreatedAsync(saved, userId);

            metrics.counter("url.created",
                "has_expiry", String.valueOf(cmd.expiresAt() != null),
                "has_alias",  String.valueOf(cmd.customAlias() != null),
                "has_password", String.valueOf(pwHash != null)
            ).increment();

            log.info("URL created: shortCode={} userId={}", shortCode, userId);
            return CreateUrlResult.from(saved, baseUrl);

        } finally {
            timer.stop(metrics.timer("url.create.duration"));
        }
    }

    // ─── ResolveUrlUseCase ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ResolveUrlResult resolve(String shortCode, RedirectContext ctx) {
        Timer.Sample timer = Timer.start(metrics);
        try {
            // Validate format before any I/O (prevents DB/cache calls for garbage input)
            if (!codeGenerator.isValidFormat(shortCode)) {
                throw new InvalidShortCodeException("Invalid short code: " + shortCode);
            }

            // L1/L0 cache lookup
            Optional<CachedUrl> cached = cachePort.findByShortCode(shortCode);
            if (cached.isPresent()) {
                metrics.counter("url.resolve.cache.hit").increment();
                return handleCachedRedirect(cached.get(), ctx, shortCode);
            }

            // L2 DB fallback (read replica)
            metrics.counter("url.resolve.cache.miss").increment();
            Url url = urlRepo.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

            // Re-warm cache for next request
            warmCacheAsync(url);

            return handleUrlRedirect(url, ctx, shortCode);

        } finally {
            timer.stop(metrics.timer("url.resolve.duration"));
        }
    }

    // ─── URL info (authenticated) ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public Url getUrlForOwner(String shortCode, Long userId) {
        return urlRepo.findByShortCodeAndUserIdAndActiveTrue(shortCode, userId)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));
    }

    @Transactional(readOnly = true)
    public Page<Url> listUserUrls(Long userId, int page, int size) {
        return urlRepo.findByUserIdAndActiveTrueOrderByCreatedAtDesc(
            userId, PageRequest.of(page, size));
    }

    @Transactional
    public void deactivateUrl(String shortCode, Long userId) {
        Url url = urlRepo.findByShortCodeAndUserIdAndActiveTrue(shortCode, userId)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));

        url.deactivate();
        urlRepo.save(url);
        cachePort.evict(shortCode);

        eventPublisher.publishUrlDeactivated(UrlDeactivatedEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .shortCode(shortCode)
            .actorId(userId)
            .reason("USER_REQUEST")
            .deactivatedAt(Instant.now())
            .build());

        log.info("URL deactivated: shortCode={} userId={}", shortCode, userId);
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private ResolveUrlResult handleCachedRedirect(CachedUrl cached,
                                                   RedirectContext ctx,
                                                   String shortCode) {
        if (!cached.isActive())             throw new UrlDeactivatedException(shortCode);
        if (cached.isExpired())             throw new UrlExpiredException(shortCode);
        if (cached.isClickLimitReached())   throw new UrlExpiredException(shortCode);
        if (cached.isRequiresPassword() && !ctx.hasValidPassword(null))
            return ResolveUrlResult.passwordRequired(shortCode);

        publishClickAsync(shortCode, cached.getOriginalUrl(), cached.getUserId(), ctx);
        return ResolveUrlResult.redirect(cached.getOriginalUrl());
    }

    private ResolveUrlResult handleUrlRedirect(Url url,
                                                RedirectContext ctx,
                                                String shortCode) {
        if (!url.isActive())           throw new UrlDeactivatedException(shortCode);
        if (url.isExpired())           throw new UrlExpiredException(shortCode);
        if (url.isClickLimitReached()) throw new UrlExpiredException(shortCode);
        if (url.requiresPassword() && !ctx.hasValidPassword(url.getPasswordHash()))
            return ResolveUrlResult.passwordRequired(shortCode);

        publishClickAsync(shortCode, url.getOriginalUrl(), url.getUserId(), ctx);
        return ResolveUrlResult.redirect(url.getOriginalUrl());
    }

    private Optional<Url> findExistingUrl(Long userId, String originalUrl) {
        List<Url> existing = urlRepo.findActiveByUserIdAndOriginalUrl(
            userId, originalUrl, PageRequest.of(0, 1));
        return existing.isEmpty() ? Optional.empty() : Optional.of(existing.get(0));
    }

    private void validateCustomAlias(String alias) {
        if (urlRepo.existsByCustomAliasAndActiveTrue(alias)) {
            throw new AliasAlreadyTakenException(alias);
        }
        if (alias.length() < 3 || alias.length() > 50) {
            throw new UrlValidationException("Custom alias must be 3-50 characters");
        }
        if (!alias.matches("^[a-z0-9][a-z0-9\\-_]{1,48}[a-z0-9]$")) {
            throw new UrlValidationException(
                "Custom alias must start/end with alphanumeric and contain only a-z, 0-9, -, _");
        }
    }

    @Async("asyncExecutor")
    public CompletableFuture<Void> warmCacheAsync(Url url) {
        try {
            cachePort.cacheUrl(url.getShortCode(), url);
        } catch (Exception e) {
            log.warn("Async cache warm failed for {}: {}", url.getShortCode(), e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Async("asyncExecutor")
    public CompletableFuture<Void> publishCreatedAsync(Url url, Long userId) {
        try {
            eventPublisher.publishUrlCreated(UrlCreatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .shortCode(url.getShortCode())
                .originalUrl(url.getOriginalUrl())
                .userId(userId)
                .customAlias(url.getCustomAlias())
                .createdAt(url.getCreatedAt())
                .expiresAt(url.getExpiresAt())
                .build());
        } catch (Exception e) {
            log.error("Async event publish failed: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Async("asyncExecutor")
    public CompletableFuture<Void> publishClickAsync(String shortCode,
                                                      String originalUrl,
                                                      Long userId,
                                                      RedirectContext ctx) {
        try {
            eventPublisher.publishUrlClicked(UrlClickedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .userId(userId)
                .clickedAt(Instant.now())
                .ipAddress(ctx.ipAddress())
                .userAgent(ctx.userAgent())
                .referer(ctx.referer())
                .acceptLanguage(ctx.acceptLanguage())
                .isBot(false)
                .build());
        } catch (Exception e) {
            log.warn("Async click event failed: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    private long generateId() {
        return snowflake.nextId();
    }
}
