package com.liverpool.ms_home.adapter.outbound.salesforce;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.liverpool.ms_home.config.SalesforceProperties;
import com.liverpool.ms_home.domain.error.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_home.domain.model.block.productslist.ProductItem;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;
import com.liverpool.ms_home.domain.model.home.BlockType;
import com.liverpool.ms_home.domain.port.outbound.ProductsListPort;

import org.springframework.web.client.RestClient;

/**
 * Outbound adapter for the Salesforce Evergage recommendation engine (Rule 19 reference:
 * {@code ProductListSalesforcePopulateStrategy} in digital_bff).
 *
 * <p>Calls the Salesforce actions endpoint ({@code POST /api2/authevent/liverpool}) with the ATG
 * user profile id and the configured carousel action. Maps the {@code campaignResponses[0].payload}
 * to the domain {@link ProductsListResolution}. The circuit breaker is applied at the use-case
 * layer — this adapter intentionally has no retry logic (locked decision 4).</p>
 *
 * <h3>Guest / unauthenticated sessions:</h3>
 * <p>Salesforce requires a valid ATG user id. When {@link ProductsListQuery#userId()} is blank the
 * adapter throws {@link DynamicBlockServiceUnavailableException} immediately, consistent with the
 * BFF's {@code SalesforceNoUserIdError} guard.</p>
 *
 * <h3>Response mapping (Rule 19 — read-only reference from BFF):</h3>
 * <pre>
 * campaignResponses[0].payload.productDataMapped  → preferred (pre-mapped product records)
 * campaignResponses[0].payload.products            → fallback (raw Salesforce records)
 * </pre>
 */
@Component
public class ProductsListAdapter implements ProductsListPort {

    private static final Logger log = LoggerFactory.getLogger(ProductsListAdapter.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final String KEY_CAMPAIGN_RESPONSES = "campaignResponses";
    private static final String KEY_CAMPAIGN_NAME = "campaignName";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_TITLE = "title";
    private static final String KEY_PRODUCT_DATA_MAPPED = "productDataMapped";
    private static final String KEY_PRODUCTS = "products";

    private static final String SALESFORCE_CHANNEL = "Server";
    private static final String ATG_USER_ATTR = "ID_ATG1";

    private final RestClient salesforceRestClient;
    private final SalesforceProperties properties;

    /**
     * @param salesforceRestClient pre-configured client bound to the Salesforce base URL
     * @param properties           Salesforce connection and carousel action settings
     */
    public ProductsListAdapter(@Qualifier("salesforceRestClient") RestClient salesforceRestClient,
                               SalesforceProperties properties) {
        this.salesforceRestClient = salesforceRestClient;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rejects the call immediately when the user id is missing — Salesforce personalisation
     * requires a valid ATG profile id. On HTTP / I/O failure wraps the error in
     * {@link DynamicBlockServiceUnavailableException} so the use-case circuit breaker can record
     * the failure and trip when the threshold is exceeded.</p>
     *
     * @throws DynamicBlockServiceUnavailableException when userId is absent, or the Salesforce
     *                                                 call fails
     */
    @Override
    public ProductsListResolution fetch(ProductsListQuery query) {
        if (query.userId() == null || query.userId().isBlank()) {
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    "Salesforce requires an authenticated user id (x-user-id); absent for this session.",
                    null);
        }

        Map<String, Object> requestBody = buildRequestBody(query);

        Map<String, Object> response;
        try {
            response = salesforceRestClient.post()
                    .uri(properties.actionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientException e) {
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    "Salesforce call failed: " + e.getMessage(),
                    e);
        }

        return mapResponse(query.blockId(), response);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(ProductsListQuery query) {
        return Map.of(
                "action", properties.defaultCarouselAction(),
                "flags", Map.of("noCampaigns", false),
                "source", Map.of(
                        "channel", SALESFORCE_CHANNEL,
                        "application", properties.application()),
                "user", Map.of(
                        "attributes", Map.of(ATG_USER_ATTR, query.userId())));
    }

    @SuppressWarnings("unchecked")
    private ProductsListResolution mapResponse(String blockId, Map<String, Object> response) {
        if (response == null) {
            log.warn("Salesforce returned null response for blockId={}", blockId);
            return new ProductsListResolution(blockId, "", List.of());
        }

        List<Map<String, Object>> campaigns = getList(response, KEY_CAMPAIGN_RESPONSES);
        if (campaigns.isEmpty()) {
            log.warn("Salesforce returned no campaignResponses for blockId={}", blockId);
            return new ProductsListResolution(blockId, "", List.of());
        }

        Map<String, Object> campaign = campaigns.get(0);
        String campaignName = (String) campaign.getOrDefault(KEY_CAMPAIGN_NAME, "");
        Map<String, Object> payload = (Map<String, Object>) campaign.get(KEY_PAYLOAD);

        if (payload == null) {
            log.warn("Salesforce campaign has no payload blockId={} campaign={}", blockId, campaignName);
            return new ProductsListResolution(blockId, "", List.of());
        }

        String title = (String) payload.getOrDefault(KEY_TITLE, "");

        List<Map<String, Object>> rawProducts = getList(payload, KEY_PRODUCT_DATA_MAPPED);
        boolean isMapped = !rawProducts.isEmpty();
        if (!isMapped) {
            rawProducts = getList(payload, KEY_PRODUCTS);
        }

        List<ProductItem> products = rawProducts.stream()
                .map(p -> isMapped ? fromMapped(p) : fromRaw(p))
                .toList();

        log.info("Salesforce resolved blockId={} campaign={} title='{}' productCount={}",
                blockId, campaignName, title, products.size());

        return new ProductsListResolution(blockId, title, products);
    }

    /**
     * Maps a pre-mapped product record ({@code productDataMapped}) to a {@link ProductItem}.
     * Fields: {@code productId}, {@code title}, {@code priceInfo.listPrice.price}.
     */
    @SuppressWarnings("unchecked")
    private ProductItem fromMapped(Map<String, Object> raw) {
        String sku = stringOrEmpty(raw.get("productId"));
        String title = stringOrEmpty(raw.get("title"));

        Map<String, Object> priceInfo = (Map<String, Object>) raw.get("priceInfo");
        String price = "";
        if (priceInfo != null) {
            Map<String, Object> listPrice = (Map<String, Object>) priceInfo.get("listPrice");
            if (listPrice != null) {
                price = stringOrEmpty(listPrice.get("price"));
            }
        }
        return new ProductItem(sku, title, price);
    }

    /**
     * Maps a raw Salesforce product record ({@code products}) to a {@link ProductItem}.
     * Fields: {@code id}, {@code attributes.name.value}, {@code attributes.listPrice.value}.
     */
    @SuppressWarnings("unchecked")
    private ProductItem fromRaw(Map<String, Object> raw) {
        String sku = stringOrEmpty(raw.get("id"));

        Map<String, Object> attributes = (Map<String, Object>) raw.get("attributes");
        String title = "";
        String price = "";
        if (attributes != null) {
            title = nestedValue(attributes, "name");
            price = nestedValue(attributes, "listPrice");
        }
        return new ProductItem(sku, title, price);
    }

    @SuppressWarnings("unchecked")
    private String nestedValue(Map<String, Object> attributes, String key) {
        Object attr = attributes.get(key);
        if (attr instanceof Map<?, ?> map) {
            return stringOrEmpty(((Map<String, Object>) map).get("value"));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?>) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    private String stringOrEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
