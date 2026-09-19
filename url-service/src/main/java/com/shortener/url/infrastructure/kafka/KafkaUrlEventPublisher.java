package com.shortener.url.infrastructure.kafka;

import com.shortener.common.domain.event.*;
import com.shortener.url.port.out.UrlEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter for publishing URL domain events.
 *
 * All sends are fire-and-forget (async, non-blocking).
 * The redirect path MUST NOT wait for Kafka acknowledgement.
 *
 * Partition key = shortCode → all events for a URL land on the same
 * partition, enabling ordered processing by analytics consumers.
 *
 * Error handling:
 *   Failures are logged + metered. Producer buffers 32MB locally before blocking,
 *   giving ~30s of resilience against Kafka unavailability.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaUrlEventPublisher implements UrlEventPublisher {

    private static final String TOPIC_CLICKED     = "url.clicked";
    private static final String TOPIC_CREATED     = "url.created";
    private static final String TOPIC_DEACTIVATED = "url.deactivated";
    private static final String TOPIC_EXPIRED     = "url.expired";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry                 metrics;

    @Override
    public void publishUrlClicked(UrlClickedEvent event) {
        send(TOPIC_CLICKED, event.shortCode(), event);
    }

    @Override
    public void publishUrlCreated(UrlCreatedEvent event) {
        send(TOPIC_CREATED, event.shortCode(), event);
    }

    @Override
    public void publishUrlDeactivated(UrlDeactivatedEvent event) {
        send(TOPIC_DEACTIVATED, event.shortCode(), event);
    }

    @Override
    public void publishUrlExpired(UrlExpiredEvent event) {
        send(TOPIC_EXPIRED, event.shortCode(), event);
    }

    // ─── Private ──────────────────────────────────────────────────────────

    private void send(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka publish failed: topic={} key={} error={}",
                        topic, key, ex.getMessage());
                    metrics.counter("kafka.publish.failure",
                        "topic", topic).increment();
                } else {
                    metrics.counter("kafka.publish.success",
                        "topic", topic).increment();
                }
            });
    }
}
