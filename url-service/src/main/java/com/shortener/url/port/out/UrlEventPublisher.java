package com.shortener.url.port.out;

import com.shortener.common.domain.event.*;

/**
 * Output port for publishing domain events to Kafka.
 * Implemented by: KafkaUrlEventPublisher (production), NoOpEventPublisher (tests)
 */
public interface UrlEventPublisher {
    void publishUrlCreated(UrlCreatedEvent event);
    void publishUrlClicked(UrlClickedEvent event);
    void publishUrlDeactivated(UrlDeactivatedEvent event);
    void publishUrlExpired(UrlExpiredEvent event);
}
