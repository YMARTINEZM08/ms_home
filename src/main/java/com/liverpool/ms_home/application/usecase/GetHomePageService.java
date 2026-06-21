package com.liverpool.ms_home.application.usecase;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.liverpool.ms_home.domain.model.content.ContentQuery;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;
import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.HomePageQuery;
import com.liverpool.ms_home.domain.port.inbound.GetHomePageUseCase;
import com.liverpool.ms_home.domain.port.outbound.ContentPort;
import com.liverpool.ms_home.domain.port.outbound.StaticBlockCachePort;
import com.liverpool.ms_home.domain.service.HomeCompositionService;

/**
 * Application use case that composes the Home page for a given session and request (Rule 18).
 *
 * <p>Orchestration strategy (cache-aside):
 * <ol>
 *   <li>Derive a {@link ContentQuery} from the incoming {@link HomePageQuery}.</li>
 *   <li>Check the two-tier cache ({@link StaticBlockCachePort} — L1 Caffeine + L2 Redis). Static
 *       Home definitions change infrequently and are identical across sessions for the same
 *       brand/locale/path, making them safe to cache at this level.</li>
 *   <li>On a cache miss, fetch from the content-service proxy via {@link ContentPort} (circuit
 *       breaker is applied transparently in the adapter).</li>
 *   <li>Populate the cache with the freshly fetched definition.</li>
 *   <li>Delegate composition to {@link HomeCompositionService}: audience filtering, channel
 *       visibility, static resolution, dynamic placeholder creation — all pure, no I/O.</li>
 * </ol>
 *
 * <p>The session is pre-resolved by the inbound adapter and embedded in the query (Rule 1 —
 * dependencies point inward; the use case does not reach into HTTP infrastructure).</p>
 */
@Service
public class GetHomePageService implements GetHomePageUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetHomePageService.class);

    private final ContentPort contentPort;
    private final StaticBlockCachePort cachePort;
    private final HomeCompositionService compositionService;

    /**
     * @param contentPort        fetches raw Home definitions from the content-service proxy
     * @param cachePort          two-tier cache for static Home definitions
     * @param compositionService pure domain orchestrator for page composition
     */
    public GetHomePageService(ContentPort contentPort,
                              StaticBlockCachePort cachePort,
                              HomeCompositionService compositionService) {
        this.contentPort = contentPort;
        this.cachePort = cachePort;
        this.compositionService = compositionService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Throws whatever the {@link ContentPort} or {@link HomeCompositionService} throw — the
     * caller (REST adapter) is responsible for translating domain exceptions to HTTP responses via
     * the {@code @RestControllerAdvice} (Phase 5). This service adds no extra exception wrapping.</p>
     *
     * @param query locale, path, preview flag and pre-resolved session context
     * @return ordered, composed Home page (static blocks resolved; dynamic blocks as placeholders)
     */
    @Override
    public HomePage getHomePage(HomePageQuery query) {
        ContentQuery contentQuery = toContentQuery(query);

        Optional<HomeDefinition> cached = cachePort.get(contentQuery);
        HomeDefinition definition;

        if (cached.isPresent()) {
            log.info("Home definition served from cache brand={} locale={} path={}",
                    contentQuery.brand(), contentQuery.locale(), contentQuery.path());
            definition = cached.get();
        } else {
            log.info("Home definition cache miss — fetching from content-service brand={} locale={} path={}",
                    contentQuery.brand(), contentQuery.locale(), contentQuery.path());
            definition = contentPort.fetchHomeDefinition(contentQuery);
            cachePort.put(contentQuery, definition);
        }

        return compositionService.compose(definition, query.session());
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Maps use-case query fields to the outbound content query. Brand originates from the session
     * context so it remains consistent across the whole request lifecycle.
     */
    private ContentQuery toContentQuery(HomePageQuery query) {
        return new ContentQuery(
                query.session().brand(),
                query.locale(),
                query.path(),
                query.preview());
    }
}
