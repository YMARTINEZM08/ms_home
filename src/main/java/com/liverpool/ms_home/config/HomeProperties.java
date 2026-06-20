package com.liverpool.ms_home.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for home-page behaviour: feature flags and dynamic block resolution paths.
 *
 * <p>Feature flags allow per-block enable/disable without redeployment (Rule 18 feature toggle).
 * Block endpoint paths are advertised in dynamic placeholders so the frontend knows where to resolve
 * each block independently. Both maps are overridable via environment variables.</p>
 *
 * @param featureFlags map of feature-flag id to enabled state (e.g. {@code products-list-salesforce=true})
 * @param blocks       map of block configuration keys to values (e.g. resolution endpoint paths)
 */
@ConfigurationProperties(prefix = "home")
public record HomeProperties(Map<String, Boolean> featureFlags, Map<String, String> blocks) {

    public HomeProperties {
        featureFlags = featureFlags == null ? Map.of() : Map.copyOf(featureFlags);
        blocks = blocks == null ? Map.of() : Map.copyOf(blocks);
    }
}
