package com.shortener.url.infrastructure.scheduler;

import com.shortener.common.domain.event.UrlExpiredEvent;
import com.shortener.url.domain.Url;
import com.shortener.url.infrastructure.persistence.UrlRepository;
import com.shortener.url.port.out.UrlCachePort;
import com.shortener.url.port.out.UrlEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled job: deactivate URLs whose expiresAt has passed.
 *
 * Design decisions:
 *   - Soft delete only — data kept for analytics
 *   - Batch processing (500 per run) — avoids locking large result sets
 *   - Leader election via Redis SET NX — only ONE pod runs this job in K8s
 *   - Runs every 5 minutes with configurable initial delay (avoid thundering herd)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrlExpiryScheduler {

    private static final String LEADER_KEY  = "scheduler:expiry:leader";
    private static final Duration LEADER_TTL = Duration.ofMinutes(6); // > schedule interval
    private static final int BATCH_SIZE = 500;

    private final UrlRepository       urlRepo;
    private final UrlCachePort        cachePort;
    private final UrlEventPublisher   eventPublisher;
    private final StringRedisTemplate redis;
    private final MeterRegistry       metrics;

    @Value("${app.instance-id:default}")
    private String instanceId;

    @Scheduled(
        fixedDelayString = "${app.expiry-scheduler.fixed-delay-ms:300000}",
        initialDelayString = "${app.expiry-scheduler.initial-delay-ms:30000}"
    )
    public void processExpiredUrls() {
        if (!tryAcquireLeadership()) {
            log.debug("Expiry scheduler: not leader, skipping");
            return;
        }

        log.info("Expiry scheduler started (leader={})", instanceId);
        Timer.Sample timer = Timer.start(metrics);
        int totalDeactivated = 0;

        try {
            int page = 0;
            while (true) {
                Page<Url> batch = urlRepo.findExpiredActiveUrls(
                    Instant.now(), PageRequest.of(page, BATCH_SIZE));

                if (batch.isEmpty()) break;

                List<Long> ids = batch.stream().map(Url::getId).toList();

                // Single bulk UPDATE — far cheaper than N individual saves
                int updated = urlRepo.bulkDeactivate(ids, Instant.now());
                totalDeactivated += updated;

                // Evict from cache + publish events for each expired URL
                batch.forEach(url -> {
                    cachePort.evict(url.getShortCode());
                    publishExpiredEvent(url);
                });

                if (!batch.hasNext()) break;
                page++;

                // Brief pause between batches (avoid I/O spike)
                Thread.sleep(100);
            }

            metrics.counter("url.expiry.deactivated").increment(totalDeactivated);
            log.info("Expiry scheduler done: deactivated={}", totalDeactivated);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Expiry scheduler interrupted");
        } catch (Exception e) {
            log.error("Expiry scheduler failed: {}", e.getMessage(), e);
        } finally {
            timer.stop(metrics.timer("url.expiry.scheduler.duration"));
            releaseLeadership();
        }
    }

    private void publishExpiredEvent(Url url) {
        try {
            eventPublisher.publishUrlExpired(UrlExpiredEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .shortCode(url.getShortCode())
                .userId(url.getUserId())
                .totalClicks(url.getTotalClicks())
                .expiredAt(Instant.now())
                .build());
        } catch (Exception e) {
            log.warn("Failed to publish UrlExpiredEvent for {}: {}", url.getShortCode(), e.getMessage());
        }
    }

    private boolean tryAcquireLeadership() {
        Boolean acquired = redis.opsForValue()
            .setIfAbsent(LEADER_KEY, instanceId, LEADER_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLeadership() {
        String script = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else return 0 end
                """;
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> redisScript =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        redis.execute(redisScript, List.of(LEADER_KEY), instanceId);
    }
}
