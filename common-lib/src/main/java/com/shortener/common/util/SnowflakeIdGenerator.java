package com.shortener.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Snowflake-inspired 64-bit distributed ID generator.
 *
 * Bit layout:
 * [1 sign][41 timestamp ms][10 worker][12 sequence]
 *
 * Throughput: 4,096 IDs/ms/worker × 1,024 workers = ~4B IDs/ms globally.
 * IDs are monotonically increasing → excellent B-tree index locality.
 */
@Component
@Slf4j
public class SnowflakeIdGenerator {

    private static final long EPOCH          = 1700000000000L; // Nov 2023
    private static final long WORKER_BITS    = 10L;
    private static final long SEQUENCE_BITS  = 12L;
    private static final long MAX_WORKER_ID  = ~(-1L << WORKER_BITS);   // 1023
    private static final long MAX_SEQUENCE   = ~(-1L << SEQUENCE_BITS); // 4095
    private static final long WORKER_SHIFT   = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence      = 0L;

    public SnowflakeIdGenerator(@Value("${app.worker-id:1}") long workerId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(
                "Worker ID must be 0–" + MAX_WORKER_ID + ", got: " + workerId);
        }
        this.workerId = workerId;
        log.info("SnowflakeIdGenerator initialized: workerId={}", workerId);
    }

    public synchronized long nextId() {
        long ts = System.currentTimeMillis();

        if (ts < lastTimestamp) {
            throw new IllegalStateException(
                "Clock moved backward by " + (lastTimestamp - ts) + " ms");
        }

        if (ts == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                ts = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = ts;
        return ((ts - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT) | sequence;
    }

    private long waitForNextMillis(long lastTs) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }
}
