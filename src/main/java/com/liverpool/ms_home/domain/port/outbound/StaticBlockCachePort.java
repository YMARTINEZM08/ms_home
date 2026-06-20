package com.liverpool.ms_home.domain.port.outbound;

import java.util.Optional;

import com.liverpool.ms_home.domain.model.content.ContentQuery;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;

/**
 * Outbound port for caching raw Home definitions fetched from the content-service proxy.
 *
 * <p>Only static-eligible data is cached here — the raw {@link HomeDefinition} retrieved from the
 * CMS before composition. Dynamic block placeholders are never cached; their dedicated endpoints own
 * their own caching policy (Rule 18).</p>
 */
public interface StaticBlockCachePort {

    /**
     * Returns a cached Home definition for the given query if one exists and has not expired.
     *
     * @param query brand/locale/path/preview key
     * @return cached definition, or empty when the cache has no entry
     */
    Optional<HomeDefinition> get(ContentQuery query);

    /**
     * Stores a Home definition under the given query key with the configured TTL.
     *
     * @param query      cache key
     * @param definition value to cache
     */
    void put(ContentQuery query, HomeDefinition definition);
}
