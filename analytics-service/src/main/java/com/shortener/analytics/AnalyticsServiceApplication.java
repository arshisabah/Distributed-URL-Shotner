package com.shortener.analytics;

import com.shortener.common.domain.event.UrlClickedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@SpringBootApplication(scanBasePackages = "com.shortener")
public class AnalyticsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}

/**
 * Click event consumer — increments Redis counters per shortCode.
 * In production replace with ClickHouse batch insert (see Part 4).
 */
@Service
@RequiredArgsConstructor
@Slf4j
class ClickEventConsumer {

    private final StringRedisTemplate redis;
    private final MeterRegistry       metrics;

    @KafkaListener(
        topics = "url.clicked",
        groupId = "analytics-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(List<UrlClickedEvent> events, Acknowledgment ack) {
        try {
            events.forEach(event -> {
                String key = "analytics:clicks:" + event.shortCode();
                redis.opsForValue().increment(key);
                metrics.counter("analytics.events.processed").increment();
            });
            ack.acknowledge();
            log.debug("Processed {} click events", events.size());
        } catch (Exception e) {
            log.error("Failed to process click events batch: {}", e.getMessage());
        }
    }
}

/**
 * Basic analytics REST API — reads Redis counters.
 * In production, query ClickHouse materialized views (see Part 4).
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
class AnalyticsController {

    private final StringRedisTemplate redis;

    @GetMapping("/{shortCode}")
    Map<String, Object> getAnalytics(@PathVariable String shortCode) {
        String key    = "analytics:clicks:" + shortCode;
        String clicks = redis.opsForValue().get(key);
        return Map.of(
            "shortCode",   shortCode,
            "totalClicks", clicks != null ? Long.parseLong(clicks) : 0L,
            "period",      "all-time",
            "note",        "Production uses ClickHouse for rich analytics"
        );
    }
}
