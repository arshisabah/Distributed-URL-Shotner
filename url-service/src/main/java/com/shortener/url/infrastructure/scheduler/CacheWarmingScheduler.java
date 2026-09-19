package com.shortener.url.infrastructure.scheduler;

import com.shortener.url.infrastructure.persistence.UrlRepository;
import com.shortener.url.port.out.UrlCachePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Proactive cache warming — pre-populates Redis before traffic arrives.
 *
 * Why?  After a deployment or Redis failover, all requests hit the DB.
 *       At 100k RPS with empty cache, the DB would be overwhelmed.
 *       Pre-warming the top 50k URLs takes ~5-10 seconds and absorbs
 *       the initial traffic spike.
 *
 * Runs:
 *   - Once on startup (async, does NOT block readiness probe)
 *   - Nightly at 03:00 to refresh with latest popular URLs
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheWarmingScheduler {

    private static final int TOP_N      = 50_000;
    private static final int BATCH_SIZE = 1_000;

    private final UrlRepository urlRepo;
    private final UrlCachePort  cachePort;

    /** Fires after all beans are ready but does NOT block K8s readiness probe. */
    @EventListener(ApplicationReadyEvent.class)
    @Async("asyncExecutor")
    public CompletableFuture<Void> warmOnStartup() {
        log.info("Cache warm-up starting (async, won't block startup)...");
        warmTopUrls();
        warmRecentUrls();
        log.info("Cache warm-up complete");
        return CompletableFuture.completedFuture(null);
    }

    /** Nightly refresh — keep the cache aligned with current traffic patterns. */
    @Scheduled(cron = "${app.cache-warming.cron:0 0 3 * * *}")
    public void scheduledWarmup() {
        log.info("Scheduled cache warm-up running...");
        warmTopUrls();
    }

    private void warmTopUrls() {
        int warmed = 0;
        int page   = 0;

        while (warmed < TOP_N) {
            List<?> batch = urlRepo.findTopByClicksDesc(PageRequest.of(page, BATCH_SIZE));
            if (batch.isEmpty()) break;

            batch.forEach(url -> {
                if (url instanceof com.shortener.url.domain.Url u) {
                    try {
                        cachePort.cacheUrl(u.getShortCode(), u);
                    } catch (Exception e) {
                        log.warn("Cache warm failed for {}: {}", u.getShortCode(), e.getMessage());
                    }
                }
            });

            warmed += batch.size();
            page++;

            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Warmed {} top-clicked URLs into cache", warmed);
    }

    private void warmRecentUrls() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        List<?> recent = urlRepo.findRecentlyCreated(since);
        recent.forEach(url -> {
            if (url instanceof com.shortener.url.domain.Url u) {
                try { cachePort.cacheUrl(u.getShortCode(), u); } catch (Exception ignored) {}
            }
        });
        log.info("Warmed {} recently-created URLs into cache", recent.size());
    }
}
