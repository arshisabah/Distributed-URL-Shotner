package com.shortener.url.domain.service;

import com.shortener.common.util.Base62Encoder;
import com.shortener.common.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Generates globally unique, collision-free 7-character short codes.
 *
 * Strategy: Snowflake ID → Base62 encode → 7-char code
 *
 * Why Snowflake?
 *   - Monotonically increasing → B-tree locality (no index fragmentation)
 *   - No DB round-trip needed for uniqueness (counter-based, not random)
 *   - Distributed-safe: worker ID differentiates pods
 *
 * Capacity: 62^7 = 3.52 trillion codes, enough for ~111 years at 1000 URLs/sec.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShortCodeGenerator {

    private final SnowflakeIdGenerator snowflake;
    private final Base62Encoder        encoder;

    /** Generate a new unique 7-char short code. O(1), no I/O. */
    public String generate() {
        long id   = snowflake.nextId();
        String code = encoder.encode(id);
        log.debug("Generated short code: {} (id={})", code, id);
        return code;
    }

    /** Validate format without full decode — used in hot redirect path. */
    public boolean isValidFormat(String code) {
        return encoder.isValidFormat(code);
    }
}
