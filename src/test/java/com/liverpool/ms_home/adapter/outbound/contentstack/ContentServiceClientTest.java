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
import com.liverpool.ms_home.domain.error.HomeDefinitionNotFoundException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.content.ContentQuery;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;

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

class ContentServiceClientTest {

    private static final String BASE_URL = "http://content-service";

    private static final ContentstackProperties PROPS = new ContentstackProperties(
            BASE_URL, "LP", "x-preview",
            Duration.ofSeconds(2), Duration.ofSeconds(5),
            "page", "home", Duration.ofMinutes(5), Duration.ofSeconds(30));

    private static final ContentQuery QUERY = new ContentQuery("LP", "es-mx", "", false);
    // path="" → homeEntryId "home" is used
    private static final String EXPECTED_URL = BASE_URL + "/content/page/es-mx/home";

    private MockRestServiceServer mockServer;
    private ContentServiceClient client;
    private CircuitBreakerRegistry defaultRegistry;

    @BeforeEach
    void setUp() {
        defaultRegistry = CircuitBreakerRegistry.ofDefaults();
        client = buildClient(defaultRegistry);
    }

    // ── happy path — current production schema ────────────────────────────────────────────────────

    @Test
    void fetchHomeDefinition_templateDotBlocks_readDirectly() {
        // Production schema: template.blocks[] with "uid" (not "_uid") and no channel flags.
        String json = """
                {
                  "template": {
                    "blocks": [
                      { "uid":"b1","_content_type_uid":"hero_banner_slider" },
                      { "uid":"b2","_content_type_uid":"container" },
                      { "uid":"b3","_content_type_uid":"band" }
                    ]
                  }
                }
                """;
        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-brand-id", "LP"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        HomeDefinition result = client.fetchHomeDefinition(QUERY);

        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks()).extracting(b -> b.uid())
                .containsExactly("b1", "b2", "b3");
        assertThat(result.blocks()).extracting(b -> b.contentTypeUid())
                .containsExactly("hero_banner_slider", "container", "band");
        mockServer.verify();
    }

    @Test
    void fetchHomeDefinition_absentChannelFlags_defaultToEnabled() {
        // Production blocks omit enable_on_web / enable_on_apps — both must default to true.
        String json = """
                {
                  "template": {
                    "blocks": [
                      { "uid":"b1","_content_type_uid":"hero_banner_slider" }
                    ]
                  }
                }
                """;
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        HomeDefinition result = client.fetchHomeDefinition(QUERY);

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0).enabledOnWeb()).isTrue();
        assertThat(result.blocks().get(0).enabledOnApps()).isTrue();
    }

    // ── happy path — legacy schema ────────────────────────────────────────────────────────────────

    @Test
    void fetchHomeDefinition_legacySchema_mergesTopLayoutAndLayout() {
        // Legacy schema: top_layout + layout + bottom_layout with "_uid" and explicit channel flags.
        String json = """
                {
                  "template": {
                    "top_layout":    [{ "_uid":"t1","_content_type_uid":"banner","enable_on_web":true,"enable_on_apps":true }],
                    "layout":        [{ "_uid":"l1","_content_type_uid":"banner","enable_on_web":true,"enable_on_apps":true }],
                    "bottom_layout": [{ "_uid":"b1","_content_type_uid":"banner","enable_on_web":true,"enable_on_apps":true }]
                  }
                }
                """;
        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-brand-id", "LP"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        HomeDefinition result = client.fetchHomeDefinition(QUERY);

        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks()).extracting(b -> b.uid())
                .containsExactly("t1", "l1", "b1");
        mockServer.verify();
    }

    @Test
    void fetchHomeDefinition_templateDotBlocksTakesPrecedenceOverLegacySections() {
        // When both schemas are present, template.blocks wins.
        String json = """
                {
                  "template": {
                    "blocks": [
                      { "uid":"new","_content_type_uid":"hero_banner_slider" }
                    ],
                    "layout": [
                      { "_uid":"old","_content_type_uid":"banner","enable_on_web":true,"enable_on_apps":true }
                    ]
                  }
                }
                """;
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        HomeDefinition result = client.fetchHomeDefinition(QUERY);

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0).uid()).isEqualTo("new");
    }

    @Test
    void fetchHomeDefinition_previewMode_appendsPreviewBrandSuffix() {
        ContentQuery previewQuery = new ContentQuery("LP", "es-mx", "", true);
        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(header("x-brand-id", "LP-PREVIEW"))
                .andRespond(withSuccess("{\"template\":{}}", MediaType.APPLICATION_JSON));

        client.fetchHomeDefinition(previewQuery);

        mockServer.verify();
    }

    @Test
    void fetchHomeDefinition_customPath_usedAsEntryId() {
        ContentQuery pathQuery = new ContentQuery("LP", "es-mx", "about-us", false);
        String pathUrl = BASE_URL + "/content/page/es-mx/about-us";
        mockServer.expect(requestTo(pathUrl))
                .andRespond(withSuccess("{\"template\":{}}", MediaType.APPLICATION_JSON));

        client.fetchHomeDefinition(pathQuery);

        mockServer.verify();
    }

    @Test
    void fetchHomeDefinition_extractsPageTitleAndSeo() {
        String json = """
                {
                  "page_title": "Liverpool Online",
                  "seo": {
                    "meta_description": "Tienda en línea",
                    "no_index": false
                  },
                  "template": { "blocks": [] }
                }
                """;
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        HomeDefinition result = client.fetchHomeDefinition(QUERY);

        assertThat(result.pageTitle()).isEqualTo("Liverpool Online");
        assertThat(result.seo()).containsEntry("meta_description", "Tienda en línea");
        assertThat(result.seo()).containsEntry("no_index", false);
    }

    @Test
    void fetchHomeDefinition_absentPageTitleAndSeo_defaultToNullAndEmpty() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess("{\"template\":{\"blocks\":[]}}", MediaType.APPLICATION_JSON));

        HomeDefinition result = client.fetchHomeDefinition(QUERY);

        assertThat(result.pageTitle()).isNull();
        assertThat(result.seo()).isEmpty();
    }

    @Test
    void fetchHomeDefinition_missingTemplateKey_returnsEmptyBlocks() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess("{\"other\":{}}", MediaType.APPLICATION_JSON));

        HomeDefinition result = client.fetchHomeDefinition(QUERY);

        assertThat(result.blocks()).isEmpty();
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    void fetchHomeDefinition_http404_throwsHomeDefinitionNotFoundException() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchHomeDefinition(QUERY))
                .isInstanceOf(HomeDefinitionNotFoundException.class);
    }

    @Test
    void fetchHomeDefinition_http500_throwsContentServiceUnavailableException() {
        mockServer.expect(requestTo(EXPECTED_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchHomeDefinition(QUERY))
                .isInstanceOf(ContentServiceUnavailableException.class);
    }

    // ── circuit breaker ──────────────────────────────────────────────────────────────────────────

    @Test
    void fetchHomeDefinition_circuitBreakerOpensAfterThreshold_throwsServiceUnavailableException() {
        // Tight CB: 2-call window, 50% threshold, minimum 2 calls → opens after 2 consecutive failures.
        CircuitBreakerConfig tightConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build();
        client = buildClient(CircuitBreakerRegistry.of(tightConfig));

        // Two server-error responses to trip the breaker.
        mockServer.expect(requestTo(EXPECTED_URL)).andRespond(withServerError());
        mockServer.expect(requestTo(EXPECTED_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchHomeDefinition(QUERY))
                .isInstanceOf(ContentServiceUnavailableException.class);
        assertThatThrownBy(() -> client.fetchHomeDefinition(QUERY))
                .isInstanceOf(ContentServiceUnavailableException.class);

        // Breaker is now OPEN — third call is rejected without going to the server.
        assertThatThrownBy(() -> client.fetchHomeDefinition(QUERY))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private ContentServiceClient buildClient(CircuitBreakerRegistry registry) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        return new ContentServiceClient(restClient, PROPS, registry);
    }
}
