package com.liverpool.ms_home.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.liverpool.ms_home.domain.model.home.BlockResolution;
import com.liverpool.ms_home.domain.model.home.BlockResolutionCatalog;
import com.liverpool.ms_home.domain.model.home.BlockType;
import com.liverpool.ms_home.domain.port.outbound.FeatureFlagPort;
import com.liverpool.ms_home.domain.service.HomeCompositionService;

/**
 * Wires pure domain objects as Spring beans. The domain layer has no Spring annotations (Rule 1), so
 * this configuration class is the single point where domain instances enter the application context.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Build the {@link BlockResolutionCatalog} from externalised configuration, mapping each
 *       dynamic {@link BlockType} to its resolution endpoint and feature-flag id.</li>
 *   <li>Instantiate {@link HomeCompositionService} by constructor injection of the two outbound
 *       port beans it needs.</li>
 * </ul>
 * Adding a new dynamic block type only requires extending the catalog map here — no other
 * infrastructure change is needed (Open/Closed, Rule 19.5).</p>
 */
@Configuration
public class DomainBeansConfig {

    static final String FLAG_PRODUCTS_LIST = "products-list-salesforce";
    static final String BLOCK_KEY_PRODUCTS_LIST_ENDPOINT = "products-list-salesforce-endpoint";
    static final String DEFAULT_PRODUCTS_LIST_ENDPOINT = "/home/blocks/products-list";

    /**
     * Builds the catalog of dynamic-block resolution metadata from externalised config.
     *
     * <p>The endpoint path and feature-flag id for each dynamic block type originate from
     * {@code home.blocks.*} and {@code home.feature-flags.*} — overridable per environment without
     * redeployment (Rule 18). Unknown flag ids in the catalog default to {@code false} via
     * {@link FeatureFlagPort}.</p>
     *
     * @param homeProperties home-page configuration containing blocks and feature-flag maps
     * @return an immutable catalog covering all currently modelled dynamic block types
     */
    @Bean
    public BlockResolutionCatalog blockResolutionCatalog(HomeProperties homeProperties) {
        String productsListEndpoint = homeProperties.blocks()
                .getOrDefault(BLOCK_KEY_PRODUCTS_LIST_ENDPOINT, DEFAULT_PRODUCTS_LIST_ENDPOINT);

        return new BlockResolutionCatalog(Map.of(
                BlockType.PRODUCTS_LIST, new BlockResolution(productsListEndpoint, FLAG_PRODUCTS_LIST)));
    }

    /**
     * Instantiates the pure {@link HomeCompositionService} — a POJO with no Spring lifecycle
     * annotations. Receives its two collaborators via constructor injection (Rule 4 — no field
     * injection).
     *
     * @param featureFlagPort   runtime flag evaluation (satisfied by {@code FeatureFlagAdapter})
     * @param resolutionCatalog endpoint + flag metadata for all dynamic block types
     * @return the composition service ready to be injected into application use cases
     */
    @Bean
    public HomeCompositionService homeCompositionService(FeatureFlagPort featureFlagPort,
                                                         BlockResolutionCatalog resolutionCatalog) {
        return new HomeCompositionService(featureFlagPort, resolutionCatalog);
    }
}
