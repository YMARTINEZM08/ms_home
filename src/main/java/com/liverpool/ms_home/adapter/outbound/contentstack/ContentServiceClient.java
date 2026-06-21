package com.liverpool.ms_home.adapter.outbound.contentstack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.error.ContentServiceUnavailableException;
import com.liverpool.ms_home.domain.error.HomeDefinitionNotFoundException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.content.BlockDefinition;
import com.liverpool.ms_home.domain.model.content.ContentQuery;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;
import com.liverpool.ms_home.domain.port.outbound.ContentPort;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Outbound adapter that fetches the raw Home definition from the content-service HTTP proxy.
 *
 * <p>This is the only point where Contentstack data enters the domain — the Contentstack SDK never
 * leaks past this class (Rule 2). The adapter applies a circuit breaker to protect the service from
 * cascading failures when the content-service proxy is unhealthy (Rule 4 — 5% threshold, no retries).
 * All outbound request observability (tracing, cURL debug) is handled by the shared
 * {@code OutboundLoggingInterceptor} already wired into the {@link RestClient} (Rule 10).</p>
 *
 * <h3>URL contract (read-only from digital_bff, Rule 19):</h3>
 * <pre>GET /content/{contentType}/{locale}/{id}</pre>
 * <p>Header {@code x-brand-id}: brand identifier; appended with {@code -PREVIEW} when the request
 * carries the preview header.</p>
 *
 * <h3>Response structure:</h3>
 * <p>Current production schema: {@code template.blocks[]} — flat ordered array.<br>
 * Legacy schema: {@code template.top_layout + layout + bottom_layout} merged in that order.<br>
 * The adapter reads {@code blocks} first; falls back to the three-section merge when absent.</p>
 */
@Component
public class ContentServiceClient implements ContentPort {

    private static final Logger log = LoggerFactory.getLogger(ContentServiceClient.class);

    private static final String CIRCUIT_BREAKER_NAME = "content-service";
    private static final String HEADER_BRAND_ID = "x-brand-id";
    private static final String PREVIEW_BRAND_SUFFIX = "-PREVIEW";

    private static final String RESPONSE_KEY_TEMPLATE  = "template";
    private static final String RESPONSE_KEY_PAGE_TITLE = "page_title";
    private static final String RESPONSE_KEY_SEO        = "seo";
    // Current production schema: template.blocks[]
    private static final String RESPONSE_KEY_BLOCKS = "blocks";
    // Legacy schema: template.top_layout + layout + bottom_layout
    private static final String RESPONSE_KEY_LAYOUT = "layout";
    private static final String RESPONSE_KEY_TOP_LAYOUT = "top_layout";
    private static final String RESPONSE_KEY_BOTTOM_LAYOUT = "bottom_layout";

    // Production entries use "uid"; legacy layout entries used "_uid".
    private static final String BLOCK_KEY_UID = "uid";
    private static final String BLOCK_KEY_UID_LEGACY = "_uid";
    private static final String BLOCK_KEY_CONTENT_TYPE_UID = "_content_type_uid";
    private static final String BLOCK_KEY_AUDIENCE_FILTER = "audience_filter";
    private static final String BLOCK_KEY_ENABLE_ON_WEB = "enable_on_web";
    private static final String BLOCK_KEY_ENABLE_ON_APPS = "enable_on_apps";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final ContentstackProperties properties;
    private final CircuitBreaker circuitBreaker;

    /**
     * @param restClient      the pooled, interceptor-wired client (from {@code RestClientConfig})
     * @param properties      content-service connection and routing settings
     * @param cbRegistry      shared circuit-breaker registry (from {@code Resilience4jConfig})
     */
    public ContentServiceClient(@Qualifier("contentServiceRestClient") RestClient restClient,
                                ContentstackProperties properties,
                                CircuitBreakerRegistry cbRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.circuitBreaker = cbRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches the raw Home definition from the content-service proxy and maps it to domain model.
     * The call is protected by the {@code content-service} circuit breaker. On an open breaker the
     * call is short-circuited immediately and a {@link ServiceUnavailableException} is thrown. HTTP
     * 404 from the proxy maps to {@link HomeDefinitionNotFoundException}; all other failures map to
     * {@link ContentServiceUnavailableException}.</p>
     *
     * @param query brand/locale/path/preview for the Home page request
     * @return the ordered raw Home definition
     * @throws HomeDefinitionNotFoundException     when the proxy returns HTTP 404
     * @throws ContentServiceUnavailableException  when the proxy is unreachable or returns an error
     * @throws ServiceUnavailableException         when the circuit breaker is open
     */
    @Override
    public HomeDefinition fetchHomeDefinition(ContentQuery query) {
        try {
            return circuitBreaker.executeSupplier(() -> doFetch(query));
        } catch (CallNotPermittedException e) {
            throw new ServiceUnavailableException(
                    "Circuit breaker '" + CIRCUIT_BREAKER_NAME + "' is open; content-service call rejected.", e);
        } catch (HomeDefinitionNotFoundException | ContentServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ContentServiceUnavailableException(
                    "Unexpected error fetching home definition.", buildDetail(query, e.getMessage()), e);
        }
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private HomeDefinition doFetch(ContentQuery query) {
        String brandHeader = buildBrandHeader(query);
        String uri = buildUri(query);

        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .header(HEADER_BRAND_ID, brandHeader)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new HomeDefinitionNotFoundException(
                        "Home definition not found for brand=" + query.brand() + " locale=" + query.locale(),
                        buildDetail(query, "HTTP 404 from content-service at " + uri));
            }
            throw new ContentServiceUnavailableException(
                    "Content-service returned an error.", buildDetail(query, e.getMessage()), e);
        } catch (RestClientException e) {
            throw new ContentServiceUnavailableException(
                    "Failed to reach content-service.", buildDetail(query, e.getMessage()), e);
        }

        if (response == null) {
            throw new ContentServiceUnavailableException(
                    "Content-service returned an empty response.", buildDetail(query, "null body"), null);
        }

        String pageTitle = (String) response.get(RESPONSE_KEY_PAGE_TITLE);
        Map<String, Object> seo = extractNestedMap(response, RESPONSE_KEY_SEO);

        Map<String, Object> template = (Map<String, Object>) response.get(RESPONSE_KEY_TEMPLATE);
        if (template == null) {
            log.warn("content-service response missing 'template' key for query={}", query);
            return new HomeDefinition(properties.homeContentType(), query.locale(), pageTitle, seo, List.of());
        }

        List<BlockDefinition> blocks = resolveBlocks(template).stream()
                .map(this::toBlockDefinition)
                .toList();

        return new HomeDefinition(properties.homeContentType(), query.locale(), pageTitle, seo, blocks);
    }

    /**
     * Reads the ordered block list from the template. Prefers the current {@code template.blocks[]}
     * schema; falls back to merging {@code top_layout + layout + bottom_layout} for legacy entries.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveBlocks(Map<String, Object> template) {
        List<Map<String, Object>> direct = extractBlockList(template, RESPONSE_KEY_BLOCKS);
        if (!direct.isEmpty()) {
            return direct;
        }
        // Legacy three-section schema.
        List<Map<String, Object>> merged = new ArrayList<>();
        merged.addAll(extractBlockList(template, RESPONSE_KEY_TOP_LAYOUT));
        merged.addAll(extractBlockList(template, RESPONSE_KEY_LAYOUT));
        merged.addAll(extractBlockList(template, RESPONSE_KEY_BOTTOM_LAYOUT));
        return merged;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractBlockList(Map<String, Object> template, String key) {
        Object value = template.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractNestedMap(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (value instanceof Map<?, ?> m) {
            return Map.copyOf((Map<String, Object>) m);
        }
        return Map.of();
    }

    private BlockDefinition toBlockDefinition(Map<String, Object> raw) {
        // Production entries use "uid"; legacy layout entries used "_uid".
        String uid = raw.containsKey(BLOCK_KEY_UID)
                ? (String) raw.get(BLOCK_KEY_UID)
                : (String) raw.get(BLOCK_KEY_UID_LEGACY);
        String contentTypeUid = (String) raw.get(BLOCK_KEY_CONTENT_TYPE_UID);
        String audienceFilter = (String) raw.get(BLOCK_KEY_AUDIENCE_FILTER);
        // Default to enabled when the field is absent — production home blocks omit these fields,
        // meaning channel control is implicit (separate CMS template per channel/path).
        boolean enableOnWeb  = !raw.containsKey(BLOCK_KEY_ENABLE_ON_WEB)  || Boolean.TRUE.equals(raw.get(BLOCK_KEY_ENABLE_ON_WEB));
        boolean enableOnApps = !raw.containsKey(BLOCK_KEY_ENABLE_ON_APPS) || Boolean.TRUE.equals(raw.get(BLOCK_KEY_ENABLE_ON_APPS));

        Map<String, Object> attributes = new HashMap<>(raw);
        attributes.remove(BLOCK_KEY_UID);        // production: "uid"
        attributes.remove(BLOCK_KEY_UID_LEGACY); // legacy: "_uid"
        attributes.remove(BLOCK_KEY_CONTENT_TYPE_UID);

        return new BlockDefinition(uid, contentTypeUid, audienceFilter, enableOnWeb, enableOnApps,
                Map.copyOf(attributes));
    }

    private String buildUri(ContentQuery query) {
        String entryId = (query.path() == null || query.path().isBlank())
                ? properties.homeEntryId()
                : query.path();
        return UriComponentsBuilder.fromPath("/content/{contentType}/{locale}/{id}")
                .buildAndExpand(properties.homeContentType(), query.locale(), entryId)
                .toUriString();
    }

    private String buildBrandHeader(ContentQuery query) {
        String brand = (query.brand() != null && !query.brand().isBlank())
                ? query.brand()
                : properties.defaultBrand();
        return query.preview() ? brand + PREVIEW_BRAND_SUFFIX : brand;
    }

    private String buildDetail(ContentQuery query, String cause) {
        return "brand=" + query.brand() + " locale=" + query.locale()
                + " path=" + query.path() + " preview=" + query.preview()
                + " | cause: " + cause;
    }
}
