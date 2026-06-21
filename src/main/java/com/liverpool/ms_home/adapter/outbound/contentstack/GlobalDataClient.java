package com.liverpool.ms_home.adapter.outbound.contentstack;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.error.ContentServiceUnavailableException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.globaldata.GlobalData;
import com.liverpool.ms_home.domain.model.globaldata.GlobalDataQuery;
import com.liverpool.ms_home.domain.port.outbound.GlobalDataPort;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Outbound adapter that fetches GlobalData from the content-service HTTP proxy (ADR-001).
 *
 * <p>Shares the same {@link RestClient} bean (connection pool + {@code OutboundLoggingInterceptor})
 * as {@link ContentServiceClient} but uses its own circuit breaker ({@code "global-data"}) so
 * a GlobalData failure never trips the home-page breaker and vice versa (ADR-004).</p>
 *
 * <h3>URL contract:</h3>
 * <pre>GET /content/{globalDataContentType}/{locale}/{globalDataEntryId}</pre>
 * <p>Header {@code x-brand-id}: brand identifier; appended with {@code -PREVIEW} for preview mode.
 * Both the content-type and entry-id are externalised via {@link ContentstackProperties}.</p>
 *
 * <h3>Response mapping:</h3>
 * <p>The top-level JSON object is expected to carry {@code feature_flags}, {@code public_variables},
 * and {@code themes} as nested maps. Absent keys default to empty maps.</p>
 */
@Component
public class GlobalDataClient implements GlobalDataPort {

    private static final Logger log = LoggerFactory.getLogger(GlobalDataClient.class);

    private static final String CIRCUIT_BREAKER_NAME = "global-data";
    private static final String HEADER_BRAND_ID = "x-brand-id";
    private static final String PREVIEW_BRAND_SUFFIX = "-PREVIEW";

    private static final String KEY_FEATURE_FLAGS    = "feature_flags";
    private static final String KEY_PUBLIC_VARIABLES = "public_variables";
    private static final String KEY_THEMES           = "themes";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final ContentstackProperties properties;
    private final CircuitBreaker circuitBreaker;

    /**
     * @param restClient  the pooled, interceptor-wired client (from {@code RestClientConfig})
     * @param properties  content-service connection and routing settings
     * @param cbRegistry  shared circuit-breaker registry (from {@code Resilience4jConfig})
     */
    public GlobalDataClient(@Qualifier("contentServiceRestClient") RestClient restClient,
                            ContentstackProperties properties,
                            CircuitBreakerRegistry cbRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.circuitBreaker = cbRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Wraps the HTTP call in the {@code global-data} circuit breaker. An open breaker throws
     * {@link ServiceUnavailableException} (503). HTTP errors and I/O failures throw
     * {@link ContentServiceUnavailableException} (502).</p>
     *
     * @param query brand, locale, and preview flag for the content-service call
     * @return the resolved GlobalData (maps are empty when CMS fields are absent)
     * @throws ContentServiceUnavailableException when the proxy is unreachable or returns an error
     * @throws ServiceUnavailableException        when the circuit breaker is open
     */
    @Override
    public GlobalData fetchGlobalData(GlobalDataQuery query) {
        try {
            return circuitBreaker.executeSupplier(() -> doFetch(query));
        } catch (CallNotPermittedException e) {
            throw new ServiceUnavailableException(
                    "Circuit breaker '" + CIRCUIT_BREAKER_NAME + "' is open; global-data call rejected.", e);
        } catch (ContentServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ContentServiceUnavailableException(
                    "Unexpected error fetching global data.", buildDetail(query, e.getMessage()), e);
        }
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private GlobalData doFetch(GlobalDataQuery query) {
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
                throw new ContentServiceUnavailableException(
                        "Global data entry not found — verify content-service.global-data-entry-id and locale.",
                        buildDetail(query, "HTTP 404 from content-service at " + uri), e);
            }
            throw new ContentServiceUnavailableException(
                    "Content-service returned an error fetching global data.",
                    buildDetail(query, e.getMessage()), e);
        } catch (RestClientException e) {
            throw new ContentServiceUnavailableException(
                    "Failed to reach content-service for global data.",
                    buildDetail(query, e.getMessage()), e);
        }

        if (response == null) {
            throw new ContentServiceUnavailableException(
                    "Content-service returned an empty global data response.",
                    buildDetail(query, "null body"), null);
        }

        log.debug("GlobalData fetched brand={} locale={} keys={}", query.brand(), query.locale(), response.keySet());

        return new GlobalData(
                query.locale(),
                extractMap(response, KEY_FEATURE_FLAGS),
                extractMap(response, KEY_PUBLIC_VARIABLES),
                extractMap(response, KEY_THEMES));
    }

    private String buildUri(GlobalDataQuery query) {
        return UriComponentsBuilder.fromPath("/content/{contentType}/{locale}/{id}")
                .buildAndExpand(
                        properties.globalDataContentType(),
                        query.locale(),
                        properties.globalDataEntryId())
                .toUriString();
    }

    private String buildBrandHeader(GlobalDataQuery query) {
        String brand = (query.brand() != null && !query.brand().isBlank())
                ? query.brand()
                : properties.defaultBrand();
        return query.preview() ? brand + PREVIEW_BRAND_SUFFIX : brand;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMap(Map<String, Object> response, String key) {
        Object value = response.get(key);
        if (value instanceof Map<?, ?> m) {
            return Map.copyOf((Map<String, Object>) m);
        }
        return Map.of();
    }

    private String buildDetail(GlobalDataQuery query, String cause) {
        return "brand=" + query.brand() + " locale=" + query.locale()
                + " preview=" + query.preview() + " | cause: " + cause;
    }
}
