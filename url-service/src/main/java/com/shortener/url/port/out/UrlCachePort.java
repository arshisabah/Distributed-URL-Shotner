package com.shortener.url.port.out;

import com.shortener.url.domain.CachedUrl;
import com.shortener.url.domain.Url;

import java.util.Optional;

/**
 * Output port for URL caching.
 *
 * Implemented by: RedisUrlCacheAdapter (production), InMemoryUrlCacheAdapter (tests)
 * The application layer depends on this interface, never on Redis directly.
 */
public interface UrlCachePort {

    /** Look up a URL by its short code. Returns empty if not cached. */
    Optional<CachedUrl> findByShortCode(String shortCode);

    /** Store a URL in the cache with appropriate TTL. */
    void cacheUrl(String shortCode, Url url);

    /** Remove a URL from the cache (on deactivation / expiry). */
    void evict(String shortCode);

    /** Warm cache with a pre-built CachedUrl (used during startup warm-up). */
    default void cacheCachedUrl(String shortCode, CachedUrl cachedUrl) {
        // Default no-op; adapters that support direct CachedUrl writes can override
    }
}
