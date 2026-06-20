package com.liverpool.ms_home.config;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;

/**
 * Configures the Caffeine L1 in-process cache for static Home definitions (Rule 4 — low latency).
 *
 * <p>The L1 cache absorbs repeated requests within the same Cloud Run instance between Redis reads.
 * Its TTL is intentionally shorter than the Redis L2 TTL so that updates propagate across instances
 * within {@code l1CacheTtl} of the Redis entry being refreshed.</p>
 */
@Configuration
public class CacheConfig {

    /**
     * In-process Caffeine cache for raw {@link HomeDefinition} objects.
     *
     * <p>Maximum size is set conservatively — ms-home serves a small number of distinct
     * brand/locale/path combinations so memory overhead is negligible. TTL is read from
     * {@code content-service.l1-cache-ttl} (default: 30 s).</p>
     *
     * @param properties content-service config supplying {@code l1CacheTtl}
     * @return the configured Caffeine cache
     */
    @Bean
    public Cache<String, HomeDefinition> homeDefinitionL1Cache(ContentstackProperties properties) {
        return Caffeine.newBuilder()
                .expireAfterWrite(properties.l1CacheTtl().toSeconds(), TimeUnit.SECONDS)
                .maximumSize(200)
                .recordStats()
                .build();
    }
}
