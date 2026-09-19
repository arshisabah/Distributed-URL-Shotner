package com.shortener.url.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.shortener.url.domain.CachedUrl;
import com.shortener.url.domain.Url;
import com.shortener.url.port.out.UrlCachePort;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Two-level cache adapter: L0 = Caffeine (per-pod) + L1 = Redis (shared).
 *
 * Read path:
 *   1. L0 Caffeine: ~0.01 ms, process-local, 5,000 entries, 10s TTL
 *   2. L1 Redis:    ~1-3 ms,  cluster-shared, 24h TTL (refreshed on access)
 *   3. miss → caller falls back to DB
 *
 * Write path (cacheUrl):
 *   Writes to Redis; invalidates L0 so next read fetches fresh data from Redis.
 *
 * ⚡ HFT insight: Same hierarchy as market data:
 *   L0 = per-strategy in-process map (nanoseconds)
 *   L1 = shared memory segment (microseconds)
 *   L2 = network call to data server (milliseconds)
 */
@Component
@Slf4j
public class RedisUrlCacheAdapter implements UrlCachePort {

    static final String KEY_PREFIX  = "url:";
    static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate  redis;
    private final ObjectMapper         mapper;
    private final MeterRegistry        metrics;

    /** L0 Caffeine cache — per-pod, ultra-fast, accepts ~10s staleness. */
    private final LoadingCache<String, Optional<CachedUrl>> l0;

    public RedisUrlCacheAdapter(StringRedisTemplate redis,
                                 ObjectMapper mapper,
                                 MeterRegistry metrics) {
        this.redis   = redis;
        this.mapper  = mapper;
        this.metrics = metrics;

        this.l0 = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(Duration.ofSeconds(10))
            .recordStats()
            .build(this::loadFromRedis);
    }

    // ─── UrlCachePort implementation ──────────────────────────────────────

    @Override
    public Optional<CachedUrl> findByShortCode(String shortCode) {
        Optional<CachedUrl> result = l0.get(shortCode);
        metrics.counter("cache.l0." + (result.isPresent() ? "hit" : "miss")).increment();
        return result;
    }

    @Override
    public void cacheUrl(String shortCode, Url url) {
        try {
            String json = mapper.writeValueAsString(CachedUrl.from(url));
            Duration ttl = effectiveTtl(url.getExpiresAt());
            if (!ttl.isNegative() && !ttl.isZero()) {
                redis.opsForValue().set(KEY_PREFIX + shortCode, json, ttl);
            }
            l0.invalidate(shortCode);   // force fresh load on next read
        } catch (Exception e) {
            log.warn("Failed to cache URL shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    @Override
    public void evict(String shortCode) {
        redis.delete(KEY_PREFIX + shortCode);
        l0.invalidate(shortCode);
        log.debug("Evicted from cache: {}", shortCode);
    }

    @Override
    public void cacheCachedUrl(String shortCode, CachedUrl cachedUrl) {
        try {
            String json = mapper.writeValueAsString(cachedUrl);
            Duration ttl = effectiveTtl(cachedUrl.getExpiresAt());
            if (!ttl.isNegative() && !ttl.isZero()) {
                redis.opsForValue().set(KEY_PREFIX + shortCode, json, ttl);
            }
            l0.invalidate(shortCode);
        } catch (Exception e) {
            log.warn("Failed to cache CachedUrl shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    /** L0 loader: fetches from Redis on L0 miss. */
    private Optional<CachedUrl> loadFromRedis(String shortCode) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + shortCode);
            if (json == null) {
                metrics.counter("cache.l1.miss").increment();
                return Optional.empty();
            }
            // Refresh TTL on access (LRU-like behaviour)
            redis.expire(KEY_PREFIX + shortCode, DEFAULT_TTL);
            metrics.counter("cache.l1.hit").increment();
            return Optional.of(mapper.readValue(json, CachedUrl.class));
        } catch (Exception e) {
            log.warn("Redis read failed for shortCode={}: {}", shortCode, e.getMessage());
            return Optional.empty(); // degrade gracefully; caller falls to DB
        }
    }

    /** Computes TTL: min(remaining URL lifetime, DEFAULT_TTL). */
    private Duration effectiveTtl(Instant expiresAt) {
        if (expiresAt == null) return DEFAULT_TTL;
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.compareTo(DEFAULT_TTL) < 0 ? remaining : DEFAULT_TTL;
    }
}
