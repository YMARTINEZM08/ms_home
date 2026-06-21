package com.liverpool.ms_home.application.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.liverpool.ms_home.domain.model.globaldata.GlobalData;
import com.liverpool.ms_home.domain.model.globaldata.GlobalDataQuery;
import com.liverpool.ms_home.domain.port.inbound.GetGlobalDataUseCase;
import com.liverpool.ms_home.domain.port.outbound.GlobalDataPort;

/**
 * Application use case that serves site-wide GlobalData from the CMS (Rule 18).
 *
 * <p>Orchestration strategy (cache-aside):
 * <ol>
 *   <li>Derive a cache key from brand, locale, and preview flag.</li>
 *   <li>Check the Caffeine L1 cache — a hit avoids the content-service call entirely.</li>
 *   <li>On a cache miss, delegate to {@link GlobalDataPort} (circuit breaker applied in the
 *       adapter) and populate the L1 cache with the result.</li>
 * </ol>
 *
 * <p>GlobalData is session-independent: the same brand/locale/preview triple yields the same
 * payload for every user. Caffeine L1 alone is sufficient here because the payload is small
 * (a few hundred bytes), the TTL is long (15 min default), and a Redis L2 would add latency for
 * negligible cache-miss reduction benefit (ADR-006 context).</p>
 */
@Service
public class GetGlobalDataService implements GetGlobalDataUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetGlobalDataService.class);

    private final GlobalDataPort globalDataPort;
    private final Cache<String, GlobalData> globalDataL1Cache;

    /**
     * @param globalDataPort    fetches GlobalData from the content-service proxy
     * @param globalDataL1Cache Caffeine in-process cache (bean from {@code CacheConfig})
     */
    public GetGlobalDataService(GlobalDataPort globalDataPort,
                                Cache<String, GlobalData> globalDataL1Cache) {
        this.globalDataPort = globalDataPort;
        this.globalDataL1Cache = globalDataL1Cache;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Passes all exceptions from {@link GlobalDataPort} up to the caller without wrapping.
     * The REST adapter's {@code GlobalExceptionHandler} translates domain exceptions to RFC 7807
     * responses (Rule 12).</p>
     *
     * @param query brand, locale, and preview flag from the incoming session context
     * @return the GlobalData (from cache or freshly fetched)
     */
    @Override
    public GlobalData getGlobalData(GlobalDataQuery query) {
        String key = buildCacheKey(query);

        GlobalData cached = globalDataL1Cache.getIfPresent(key);
        if (cached != null) {
            log.info("GlobalData cache hit brand={} locale={} preview={}",
                    query.brand(), query.locale(), query.preview());
            return cached;
        }

        log.info("GlobalData cache miss — fetching from content-service brand={} locale={} preview={}",
                query.brand(), query.locale(), query.preview());
        GlobalData data = globalDataPort.fetchGlobalData(query);
        globalDataL1Cache.put(key, data);
        return data;
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    private String buildCacheKey(GlobalDataQuery query) {
        return "global:def:" + query.brand() + ":" + query.locale() + ":" + query.preview();
    }
}
