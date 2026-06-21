package com.liverpool.ms_home.adapter.inbound.rest.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import com.liverpool.ms_home.adapter.inbound.rest.dto.GlobalDataResponse;
import com.liverpool.ms_home.adapter.inbound.rest.mapper.GlobalDataMapper;
import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.model.globaldata.GlobalData;
import com.liverpool.ms_home.domain.model.globaldata.GlobalDataQuery;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.inbound.GetGlobalDataUseCase;
import com.liverpool.ms_home.domain.port.outbound.SessionContextPort;

/**
 * Inbound adapter for the GlobalData endpoint. Serves site-wide CMS configuration needed by every
 * page: feature flags, public variables, and brand theme tokens (Rule 1 — dependencies inward).
 *
 * <p><strong>Contract:</strong> {@code GET /global-data}</p>
 *
 * <p>This controller is intentionally thin: session resolution, brand derivation, and preview
 * detection are delegated to their respective ports. The use case is responsible for caching and
 * origin fetching. No business logic lives here (Rule 18).</p>
 *
 * <h3>Request signals:</h3>
 * <ul>
 *   <li>Session (brand/locale) — resolved from upstream-validated headers via {@link SessionContextPort}.</li>
 *   <li>Preview mode — presence of the configured preview header fetches Contentstack preview content.</li>
 * </ul>
 *
 * <h3>Response signals:</h3>
 * <ul>
 *   <li>{@code x-request-id} — echoed from the inbound header or generated if absent.</li>
 * </ul>
 */
@RestController
@RequestMapping("/global-data")
public class GlobalDataController {

    private static final Logger log = LoggerFactory.getLogger(GlobalDataController.class);

    static final String HEADER_REQUEST_ID = "x-request-id";

    private final GetGlobalDataUseCase getGlobalDataUseCase;
    private final SessionContextPort sessionContextPort;
    private final GlobalDataMapper globalDataMapper;
    private final ContentstackProperties contentstackProperties;

    /**
     * @param getGlobalDataUseCase   fetches or serves cached GlobalData
     * @param sessionContextPort     resolves the session from upstream-validated headers
     * @param globalDataMapper       converts domain GlobalData to REST DTO
     * @param contentstackProperties supplies the configurable preview header name
     */
    public GlobalDataController(GetGlobalDataUseCase getGlobalDataUseCase,
                                SessionContextPort sessionContextPort,
                                GlobalDataMapper globalDataMapper,
                                ContentstackProperties contentstackProperties) {
        this.getGlobalDataUseCase = getGlobalDataUseCase;
        this.sessionContextPort = sessionContextPort;
        this.globalDataMapper = globalDataMapper;
        this.contentstackProperties = contentstackProperties;
    }

    /**
     * Returns site-wide GlobalData for the requesting session's brand and locale.
     *
     * <p>Results are served from the in-process Caffeine L1 cache when available. On a cache miss
     * the content-service is called and the result is cached with a 15-minute TTL (configurable).
     * Errors propagate to {@link com.liverpool.ms_home.adapter.inbound.rest.GlobalExceptionHandler}
     * as RFC 7807 ProblemDetail responses (Rule 12).</p>
     *
     * @param request used to read the configurable preview header
     * @return 200 with the GlobalData payload; error cases handled by {@link com.liverpool.ms_home.adapter.inbound.rest.GlobalExceptionHandler}
     */
    @GetMapping
    public ResponseEntity<GlobalDataResponse> getGlobalData(HttpServletRequest request) {
        SessionContext session = sessionContextPort.currentContext();
        boolean preview = request.getHeader(contentstackProperties.previewHeader()) != null;

        log.info("GET /global-data brand={} locale={} preview={}",
                session.brand(), session.locale(), preview);

        GlobalDataQuery query = new GlobalDataQuery(session.brand(), session.locale(), preview);
        GlobalData data = getGlobalDataUseCase.getGlobalData(query);
        GlobalDataResponse response = globalDataMapper.toResponse(data);

        return ResponseEntity.ok()
                .header(HEADER_REQUEST_ID, MDC.get("requestId"))
                .body(response);
    }
}
