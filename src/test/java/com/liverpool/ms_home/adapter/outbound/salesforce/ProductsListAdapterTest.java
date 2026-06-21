package com.liverpool.ms_home.adapter.outbound.salesforce;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.liverpool.ms_home.config.SalesforceProperties;
import com.liverpool.ms_home.domain.error.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_home.domain.model.block.productslist.ProductItem;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;
import com.liverpool.ms_home.domain.model.home.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link ProductsListAdapter}: verifies guest guard, request body format,
 * response mapping (productDataMapped path, raw-products fallback, empty/null edge cases),
 * and HTTP error translation.
 *
 * <p>No live Salesforce connection required — all HTTP interactions are handled via
 * {@link MockRestServiceServer}.</p>
 */
class ProductsListAdapterTest {

    private static final String BASE_URL = "http://salesforce";
    private static final String ACTIONS_PATH = "/api2/authevent/liverpool";

    private static final SalesforceProperties PROPS = new SalesforceProperties(
            BASE_URL, ACTIONS_PATH, "Bearer test-token",
            Duration.ofSeconds(4), "Web", "CMSOfertasIncreibles");

    private static final SessionContext AUTH_SESSION = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final String USER_ID = "user-31005309570";
    private static final String BLOCK_ID = "bltabc123";

    private MockRestServiceServer mockServer;
    private ProductsListAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new ProductsListAdapter(builder.build(), PROPS);
    }

    // ── guest guard ───────────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_nullUserId_throwsImmediatelyWithoutHttpCall() {
        ProductsListQuery query = new ProductsListQuery(BLOCK_ID, AUTH_SESSION, null);

        assertThatThrownBy(() -> adapter.fetch(query))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .satisfies(ex -> {
                    DynamicBlockServiceUnavailableException e = (DynamicBlockServiceUnavailableException) ex;
                    assertThat(e.getBlockId()).isEqualTo(BLOCK_ID);
                    assertThat(e.getDetail()).contains("user id");
                });

        mockServer.verify(); // zero HTTP calls made
    }

    @Test
    void fetch_blankUserId_throwsImmediatelyWithoutHttpCall() {
        ProductsListQuery query = new ProductsListQuery(BLOCK_ID, AUTH_SESSION, "  ");

        assertThatThrownBy(() -> adapter.fetch(query))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .satisfies(ex -> {
                    DynamicBlockServiceUnavailableException e = (DynamicBlockServiceUnavailableException) ex;
                    assertThat(e.getBlockId()).isEqualTo(BLOCK_ID);
                    assertThat(e.getDetail()).contains("user id");
                });

        mockServer.verify();
    }

    // ── request body structure ────────────────────────────────────────────────────────────────────

    @Test
    void fetch_requestBody_containsRequiredFields() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "action":  "CMSOfertasIncreibles",
                          "flags":   { "noCampaigns": false },
                          "source":  { "channel": "Server", "application": "Web" },
                          "user":    { "attributes": { "ID_ATG1": "user-31005309570" } }
                        }
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));
        mockServer.verify();
    }

    // ── productDataMapped response path ──────────────────────────────────────────────────────────

    @Test
    void fetch_productDataMapped_mapsSkuTitleAndPrice() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "campaignResponses": [{
                            "campaignName": "HomeCarousel",
                            "payload": {
                              "title": "Best Sellers",
                              "productDataMapped": [{
                                "productId": "sku-001",
                                "title": "Product A",
                                "priceInfo": { "listPrice": { "price": "999.00" } }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        assertThat(result.title()).isEqualTo("Best Sellers");
        assertThat(result.products()).hasSize(1);

        ProductItem product = result.products().get(0);
        assertThat(product.sku()).isEqualTo("sku-001");
        assertThat(product.title()).isEqualTo("Product A");
        assertThat(product.price()).isEqualTo("999.00");
    }

    @Test
    void fetch_productDataMapped_multipleProducts_allMapped() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "campaignResponses": [{
                            "campaignName": "MultiCarousel",
                            "payload": {
                              "title": "Top Picks",
                              "productDataMapped": [
                                { "productId": "sku-001", "title": "A", "priceInfo": { "listPrice": { "price": "10.00" } } },
                                { "productId": "sku-002", "title": "B", "priceInfo": { "listPrice": { "price": "20.00" } } },
                                { "productId": "sku-003", "title": "C", "priceInfo": null }
                              ]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.products()).hasSize(3);
        assertThat(result.products().get(0).sku()).isEqualTo("sku-001");
        assertThat(result.products().get(1).sku()).isEqualTo("sku-002");
        // null priceInfo → empty price string (no NPE)
        assertThat(result.products().get(2).price()).isEmpty();
    }

    // ── raw products fallback path ────────────────────────────────────────────────────────────────

    @Test
    void fetch_productsRawFallback_whenProductDataMappedAbsent() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "campaignResponses": [{
                            "campaignName": "RawCarousel",
                            "payload": {
                              "title": "Raw Products",
                              "products": [{
                                "id": "sku-raw-001",
                                "attributes": {
                                  "name": { "value": "Raw Product A" },
                                  "listPrice": { "value": "49.99" }
                                }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.title()).isEqualTo("Raw Products");
        assertThat(result.products()).hasSize(1);
        ProductItem product = result.products().get(0);
        assertThat(product.sku()).isEqualTo("sku-raw-001");
        assertThat(product.title()).isEqualTo("Raw Product A");
        assertThat(product.price()).isEqualTo("49.99");
    }

    // ── empty / null edge cases ──────────────────────────────────────────────────────────────────

    @Test
    void fetch_emptyCampaignResponses_returnsEmptyResolution() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("{\"campaignResponses\": []}", MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        assertThat(result.title()).isEmpty();
        assertThat(result.products()).isEmpty();
    }

    @Test
    void fetch_noCampaignResponsesKey_returnsEmptyResolution() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.products()).isEmpty();
    }

    @Test
    void fetch_nullPayloadInCampaign_returnsEmptyResolution() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "campaignResponses": [{ "campaignName": "Broken", "payload": null }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.products()).isEmpty();
    }

    @Test
    void fetch_emptyProductArrays_returnsEmptyProductList() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "campaignResponses": [{
                            "campaignName": "EmptyCarousel",
                            "payload": {
                              "title": "Nothing Here",
                              "productDataMapped": [],
                              "products": []
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.title()).isEqualTo("Nothing Here");
        assertThat(result.products()).isEmpty();
    }

    // ── HTTP error handling ───────────────────────────────────────────────────────────────────────

    @Test
    void fetch_salesforceReturns500_throwsDynamicBlockServiceUnavailableException() {
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID)))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .satisfies(ex -> {
                    DynamicBlockServiceUnavailableException e = (DynamicBlockServiceUnavailableException) ex;
                    assertThat(e.getBlockId()).isEqualTo(BLOCK_ID);
                    assertThat(e.getBlockType()).isEqualTo("products_list");
                });
    }

    // ── campaign / title extraction ──────────────────────────────────────────────────────────────

    @Test
    void fetch_onlyFirstCampaignConsumed() {
        // Salesforce may return multiple campaigns; only the first is consumed (BFF parity).
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "campaignResponses": [
                            {
                              "campaignName": "First",
                              "payload": {
                                "title": "First Title",
                                "productDataMapped": [{ "productId": "sku-1", "title": "P1", "priceInfo": null }]
                              }
                            },
                            {
                              "campaignName": "Second",
                              "payload": {
                                "title": "Second Title",
                                "productDataMapped": [{ "productId": "sku-2", "title": "P2", "priceInfo": null }]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.title()).isEqualTo("First Title");
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).sku()).isEqualTo("sku-1");
    }

    @Test
    void fetch_productDataMappedTakesPrecedenceOverRawProducts() {
        // When both arrays are present, productDataMapped is preferred.
        mockServer.expect(requestTo(BASE_URL + ACTIONS_PATH))
                .andRespond(withSuccess("""
                        {
                          "campaignResponses": [{
                            "campaignName": "Both",
                            "payload": {
                              "productDataMapped": [{ "productId": "mapped-sku", "title": "Mapped", "priceInfo": null }],
                              "products":          [{ "id": "raw-sku",    "attributes": {} }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductsListResolution result = adapter.fetch(new ProductsListQuery(BLOCK_ID, AUTH_SESSION, USER_ID));

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).sku()).isEqualTo("mapped-sku");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** Returns a block listing of products using the given IDs and titles to verify full list mapping. */
    private static List<ProductItem> productsOf(String... skus) {
        return java.util.Arrays.stream(skus)
                .map(sku -> new ProductItem(sku, "T-" + sku, "0.00"))
                .toList();
    }
}
