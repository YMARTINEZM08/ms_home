package com.liverpool.ms_home.adapter.inbound.rest.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;

import com.liverpool.ms_home.adapter.inbound.rest.dto.HomePageResponse;
import com.liverpool.ms_home.adapter.inbound.rest.mapper.HomePageMapper;
import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.HomePageQuery;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.inbound.GetHomePageUseCase;
import com.liverpool.ms_home.domain.port.outbound.SessionContextPort;

/**
 * Inbound adapter for the Home page layout endpoint. Orchestrates the request lifecycle from HTTP
 * headers to domain use case and back to a JSON response (Rule 1 — dependencies point inward).
 *
 * <p><strong>Contract:</strong> {@code GET /home}</p>
 *
 * <p>This controller is intentionally thin: it extracts inputs, validates them, delegates to the
 * use case, and maps the result. No business logic lives here (Rule 18). All domain exceptions are
 * translated by {@link GlobalExceptionHandler} to RFC 7807 {@code ProblemDetail} responses
 * (Rule 12).</p>
 *
 * <h3>Request signals:</h3>
 * <ul>
 *   <li>Session (auth/brand/channel/locale) — from upstream-validated headers via
 *       {@link SessionContextPort}.</li>
 *   <li>Preview mode — presence of the configured preview header triggers Contentstack preview
 *       content (value is irrelevant; presence is the signal).</li>
 *   <li>Path — optional query param for sub-page paths; defaults to the root home entry.</li>
 * </ul>
 *
 * <h3>Response signals:</h3>
 * <ul>
 *   <li>{@code x-request-id} — echoed from the inbound header or generated if absent.</li>
 * </ul>
 */
@RestController
@RequestMapping("/home")
@Validated
public class HomeController {

    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

    static final String HEADER_REQUEST_ID = "x-request-id";
    private static final String DEFAULT_PATH = "";

    private final GetHomePageUseCase getHomePageUseCase;
    private final SessionContextPort sessionContextPort;
    private final HomePageMapper homePageMapper;
    private final ContentstackProperties contentstackProperties;

    /**
     * @param getHomePageUseCase     composes the home page from CMS + session
     * @param sessionContextPort     resolves the session from upstream-validated headers
     * @param homePageMapper         converts domain page to REST DTO
     * @param contentstackProperties supplies the configurable preview header name
     */
    public HomeController(GetHomePageUseCase getHomePageUseCase,
                          SessionContextPort sessionContextPort,
                          HomePageMapper homePageMapper,
                          ContentstackProperties contentstackProperties) {
        this.getHomePageUseCase = getHomePageUseCase;
        this.sessionContextPort = sessionContextPort;
        this.homePageMapper = homePageMapper;
        this.contentstackProperties = contentstackProperties;
    }

    /**
     * Composes the Home page layout for the requesting session.
     *
     * <p>Static blocks are fully resolved; dynamic blocks are returned as placeholders with their
     * dedicated resolution endpoints so the frontend can fetch them independently (Rule 18).</p>
     *
     * @param path    optional sub-page path (Contentstack entry id); defaults to root home entry
     * @param request used to read the configurable preview header name
     * @return 200 with the composed home page; error cases handled by {@link GlobalExceptionHandler}
     */
    @GetMapping
    public ResponseEntity<HomePageResponse> getHomePage(
            @RequestParam(defaultValue = DEFAULT_PATH) @Size(max = 256) String path,
            HttpServletRequest request) {

        SessionContext session = sessionContextPort.currentContext();
        boolean preview = request.getHeader(contentstackProperties.previewHeader()) != null;

        log.info("GET /home brand={} locale={} authenticated={} preview={}",
                session.brand(), session.locale(), session.authenticated(), preview);

        HomePageQuery query = new HomePageQuery(session.locale(), path, preview, session);
        HomePage page = getHomePageUseCase.getHomePage(query);
        HomePageResponse response = homePageMapper.toResponse(page);

        return ResponseEntity.ok()
                .header(HEADER_REQUEST_ID, MDC.get("requestId"))
                .body(response);
    }
}
