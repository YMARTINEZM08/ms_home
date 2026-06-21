package com.liverpool.ms_home.adapter.outbound.contentstack;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.error.ContentServiceUnavailableException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.globaldata.GlobalData;
import com.liverpool.ms_home.domain.model.globaldata.GlobalDataQuery;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.springframework.http.HttpStatus;

class GlobalDataClientTest {

    private static final String BASE_URL = "http://content-service";

    private static final ContentstackProperties PROPS = new ContentstackProperties(
            BASE_URL, "LP", "x-preview",
            Duration.ofSeconds(2), Duration.ofSeconds(5),
            "page", "home", Duration.ofMinutes(5), Duration.ofSeconds(30),
            "global_data", "global_data", Duration.ofMinutes(15));

    private static final GlobalDataQuery QUERY = new GlobalDataQuery("LP", "es-mx", false);
    private static final String EXPECTED_URL = BASE_URL + "/content/global_data/es-mx/global_data";

    private MockRestServiceServer mockServer;
    private GlobalDataClient client;
    private CircuitBreakerRegistry defaultRegistry;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        defaultRegistry = CircuitBreakerRegistry.ofDefaults();
        client = new GlobalDataClient(restClient, PROPS, defaultRegistry);
    }

    // ── happy paths ────────────────────────────────────────────────────────────────────────────

    @Test
    void fetchGlobalData_success_returnsGlobalDataWithFeatureFlags() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-brand-id", "LP"))
                .andRespond(withSuccess("""
                        {
                          "feature_flags": { "salesforce": true, "personalization": false },
                          "public_variables": { "site_domain": "https://www.liverpool.com.mx" },
                          "themes": { "primary_color": "#E31837" }
                        }
                        """, MediaType.APPLICATION_JSON));

        GlobalData result = client.fetchGlobalData(QUERY);

        assertThat(result.locale()).isEqualTo("es-mx");
        assertThat(result.featureFlags()).containsEntry("salesforce", true);
        assertThat(result.featureFlags()).containsEntry("personalization", false);
        assertThat(result.publicVariables()).containsEntry("site_domain", "https://www.liverpool.com.mx");
        assertThat(result.themes()).containsEntry("primary_color", "#E31837");
        mockServer.verify();
    }

    @Test
    void fetchGlobalData_previewMode_appendsPreviewSuffix() {
        GlobalDataQuery previewQuery = new GlobalDataQuery("LP", "es-mx", true);
        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(header("x-brand-id", "LP-PREVIEW"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.fetchGlobalData(previewQuery);
        mockServer.verify();
    }

    @Test
    void fetchGlobalData_absentCmsKeys_returnsEmptyMaps() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess("{\"other_field\": \"ignored\"}", MediaType.APPLICATION_JSON));

        GlobalData result = client.fetchGlobalData(QUERY);

        assertThat(result.featureFlags()).isEmpty();
        assertThat(result.publicVariables()).isEmpty();
        assertThat(result.themes()).isEmpty();
        assertThat(result.header()).isEmpty();
        assertThat(result.footer()).isEmpty();
    }

    @Test
    void fetchGlobalData_headerAndFooter_extractedFromCmsResponse() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "feature_flags": {},
                          "public_variables": {},
                          "themes": {},
                          "header": { "logo_url": "https://cdn.liverpool.com.mx/logo.svg", "nav_items": [] },
                          "footer": { "copyright": "© Liverpool 2025", "links": [] }
                        }
                        """, MediaType.APPLICATION_JSON));

        GlobalData result = client.fetchGlobalData(QUERY);

        assertThat(result.header()).containsEntry("logo_url", "https://cdn.liverpool.com.mx/logo.svg");
        assertThat(result.header()).containsKey("nav_items");
        assertThat(result.footer()).containsEntry("copyright", "© Liverpool 2025");
        assertThat(result.footer()).containsKey("links");
        mockServer.verify();
    }

    @Test
    void fetchGlobalData_usesGlobalDataContentTypeAndEntryId() {
        // URL must use global_data content-type and entry-id from config, not the home config.
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.fetchGlobalData(QUERY);
        mockServer.verify();
    }

    // ── error handling ────────────────────────────────────────────────────────────────────────

    @Test
    void fetchGlobalData_http404_throwsContentServiceUnavailableException() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchGlobalData(QUERY))
                .isInstanceOf(ContentServiceUnavailableException.class)
                .hasMessageContaining("Global data entry not found");
    }

    @Test
    void fetchGlobalData_http500_throwsContentServiceUnavailableException() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchGlobalData(QUERY))
                .isInstanceOf(ContentServiceUnavailableException.class);
    }

    @Test
    void fetchGlobalData_circuitBreakerOpensAfterThreshold_throwsServiceUnavailableException() {
        // Tight CB: 2-call window, 50% threshold, minimum 2 calls → opens after 2 consecutive failures.
        CircuitBreakerConfig tightConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build();
        GlobalDataClient tightClient = buildClient(CircuitBreakerRegistry.of(tightConfig));

        // Two server-error responses to trip the breaker.
        mockServer.expect(requestTo(EXPECTED_URL)).andRespond(withServerError());
        mockServer.expect(requestTo(EXPECTED_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> tightClient.fetchGlobalData(QUERY))
                .isInstanceOf(ContentServiceUnavailableException.class);
        assertThatThrownBy(() -> tightClient.fetchGlobalData(QUERY))
                .isInstanceOf(ContentServiceUnavailableException.class);

        // Breaker is now OPEN — third call is rejected without going to the server.
        assertThatThrownBy(() -> tightClient.fetchGlobalData(QUERY))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private GlobalDataClient buildClient(CircuitBreakerRegistry registry) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        return new GlobalDataClient(builder.build(), PROPS, registry);
    }
}
