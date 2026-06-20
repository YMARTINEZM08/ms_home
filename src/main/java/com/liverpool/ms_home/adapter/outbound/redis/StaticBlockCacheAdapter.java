package com.liverpool.ms_home.adapter.outbound.redis;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.model.content.ContentQuery;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;
import com.liverpool.ms_home.domain.port.outbound.StaticBlockCachePort;

/**
 * Two-tier cache (L1 Caffeine + L2 Redis) for raw Home definitions fetched from the content-service.
 *
 * <p>Only static-eligible data is stored here (Rule 18). The L1 Caffeine cache absorbs hot traffic
 * with sub-millisecond access; L2 Redis is the shared, durable store across instances. On a cache
 * miss in both tiers the caller is expected to populate via {@link #put}.</p>
 *
 * <p>Serialization is handled by {@link ObjectMapper} using JSON, which round-trips Java records
 * cleanly. Cache errors are handled defensively: a Redis failure on read returns empty (so the
 * caller fetches from the origin); a Redis failure on write is logged but not thrown (the origin
 * call already succeeded).</p>
 */
@Component
public class StaticBlockCacheAdapter implements StaticBlockCachePort {

    private static final Logger log = LoggerFactory.getLogger(StaticBlockCacheAdapter.class);

    private static final String CACHE_KEY_PREFIX = "home:def:";

    private final StringRedisTemplate redis;
    private final Cache<String, HomeDefinition> l1Cache;
    private final ObjectMapper objectMapper;
    private final Duration redisTtl;

    /**
     * @param redis        Spring Data Redis template (auto-configured, String-based)
     * @param l1Cache      Caffeine in-process cache (bean from {@code CacheConfig})
     * @param objectMapper Jackson mapper for JSON serialization of domain records
     * @param properties   content-service config supplying {@code cacheTtl} and {@code l1CacheTtl}
     */
    public StaticBlockCacheAdapter(StringRedisTemplate redis,
                                   Cache<String, HomeDefinition> l1Cache,
                                   ObjectMapper objectMapper,
                                   ContentstackProperties properties) {
        this.redis = redis;
        this.l1Cache = l1Cache;
        this.objectMapper = objectMapper;
        this.redisTtl = properties.cacheTtl();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks L1 first; on miss checks L2 Redis and promotes to L1 on a hit. Redis read failures
     * return empty so the caller transparently falls back to the origin (content-service).</p>
     */
    @Override
    public Optional<HomeDefinition> get(ContentQuery query) {
        String key = cacheKey(query);

        HomeDefinition l1Hit = l1Cache.getIfPresent(key);
        if (l1Hit != null) {
            log.debug("L1 cache hit for key={}", key);
            return Optional.of(l1Hit);
        }

        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis read error for key={} — falling back to origin. cause={}", key, e.getMessage());
            return Optional.empty();
        }

        if (json == null) {
            return Optional.empty();
        }

        try {
            HomeDefinition definition = objectMapper.readValue(json, HomeDefinition.class);
            l1Cache.put(key, definition);
            log.debug("L2 cache hit for key={}, promoted to L1", key);
            return Optional.of(definition);
        } catch (JacksonException e) {
            log.warn("Redis deserialization error for key={} — evicting and falling back to origin. cause={}",
                    key, e.getMessage());
            redis.delete(key);
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes to L2 Redis (with TTL) and L1 Caffeine simultaneously. Redis write failures are
     * logged and swallowed — the origin call already succeeded; the next request will simply miss the
     * cache and re-fetch.</p>
     */
    @Override
    public void put(ContentQuery query, HomeDefinition definition) {
        String key = cacheKey(query);

        l1Cache.put(key, definition);

        try {
            String json = objectMapper.writeValueAsString(definition);
            redis.opsForValue().set(key, json, redisTtl);
            log.debug("Cached home definition in L1+L2 for key={} ttl={}s", key, redisTtl.toSeconds());
        } catch (JacksonException e) {
            log.warn("Failed to serialize HomeDefinition for key={} — Redis write skipped. cause={}", key,
                    e.getMessage());
        } catch (Exception e) {
            log.warn("Redis write error for key={} — L1 populated, L2 skipped. cause={}", key, e.getMessage());
        }
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Cache key format: {@code home:def:{brand}:{locale}:{path}:{preview}}.
     * Preview content has its own cache entry so live and preview never collide.
     */
    private String cacheKey(ContentQuery query) {
        return CACHE_KEY_PREFIX
                + safeSegment(query.brand()) + ":"
                + safeSegment(query.locale()) + ":"
                + safeSegment(query.path()) + ":"
                + query.preview();
    }

    private String safeSegment(String value) {
        return (value == null || value.isBlank()) ? "_" : value;
    }
}
