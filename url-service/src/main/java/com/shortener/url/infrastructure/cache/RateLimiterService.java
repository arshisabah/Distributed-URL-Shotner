package com.shortener.url.infrastructure.cache;

import com.shortener.common.domain.exception.RateLimitExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Distributed token-bucket rate limiter backed by Redis.
 *
 * A single Lua script performs check-and-decrement atomically —
 * no race conditions, one Redis round-trip per request.
 *
 * Rate tiers (configured in application.yml):
 *   FREE       :  10 requests/min
 *   PRO        : 100 requests/min
 *   ENTERPRISE : unlimited
 *   ANONYMOUS  :  60 requests/min (by IP)
 */
@Service
@Slf4j
public class RateLimiterService {

    private static final String LUA_SCRIPT = """
        local key       = KEYS[1]
        local capacity  = tonumber(ARGV[1])
        local rate      = tonumber(ARGV[2])
        local now       = tonumber(ARGV[3])
        local requested = tonumber(ARGV[4])

        local bucket    = redis.call('HMGET', key, 'tokens', 'last_refill')
        local tokens    = tonumber(bucket[1]) or capacity
        local last      = tonumber(bucket[2]) or now
        local elapsed   = (now - last) / 1000.0
        local refilled  = math.min(capacity, tokens + elapsed * rate)

        if refilled >= requested then
            refilled = refilled - requested
            redis.call('HMSET', key, 'tokens', refilled, 'last_refill', now)
            redis.call('PEXPIRE', key, math.ceil(capacity / rate * 1000) + 5000)
            return {1, math.floor(refilled), 0}
        else
            local wait_ms = math.ceil(((requested - refilled) / rate) * 1000)
            return {0, 0, wait_ms}
        end
        """;

    private final RedisScript<List<Long>> script;
    private final StringRedisTemplate     redis;
    private final MeterRegistry           metrics;

    public RateLimiterService(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis   = redis;
        this.metrics = metrics;
        DefaultRedisScript<List<Long>> s = new DefaultRedisScript<>();
        s.setScriptText(LUA_SCRIPT);
        s.setResultType((Class<List<Long>>) (Class<?>) List.class);
        this.script  = s;
    }

    /**
     * Check rate limit for a given key.
     * Throws RateLimitExceededException when bucket is empty.
     *
     * @param key           e.g. "rl:user:123" or "rl:ip:1.2.3.4"
     * @param burstCapacity max tokens in bucket
     * @param refillPerSec  tokens added per second
     */
    public void checkLimit(String key, int burstCapacity, int refillPerSec) {
        if (burstCapacity <= 0) return; // unlimited tier

        List<Long> result = redis.execute(
            script,
            List.of(key),
            String.valueOf(burstCapacity),
            String.valueOf(refillPerSec),
            String.valueOf(System.currentTimeMillis()),
            "1"
        );

        if (result == null || result.get(0) == 0L) {
            long waitMs = (result != null) ? result.get(2) : 1000L;
            long retryAfterSec = Math.max(1, waitMs / 1000);
            metrics.counter("rate_limit.rejected", "key_prefix",
                key.split(":")[1]).increment();
            throw new RateLimitExceededException(retryAfterSec);
        }
        metrics.counter("rate_limit.allowed").increment();
    }

    /** Helper: rate-limit by user ID using their tier limits. */
    public void checkUserLimit(Long userId, int burstCapacity, int refillPerSec) {
        String key = "rl:user:" + userId;
        checkLimit(key, burstCapacity, refillPerSec);
    }

    /** Helper: rate-limit anonymous requests by IP address. */
    public void checkIpLimit(String ipAddress) {
        String key = "rl:ip:" + ipAddress.replace(":", "_"); // IPv6 safe
        checkLimit(key, 60, 1); // 60 burst, 1/s refill = 60/min steady state
    }
}
