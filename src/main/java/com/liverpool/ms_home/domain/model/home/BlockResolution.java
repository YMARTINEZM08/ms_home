package com.liverpool.ms_home.domain.model.home;

/**
 * Resolution metadata for a dynamic block type: where its dedicated endpoint lives and which feature
 * flag governs it. Supplied to the composition service so the domain stays free of configuration
 * mechanics (the values originate from externalised config, mapped in the adapter/config layer).
 *
 * @param resolutionPath path of the dedicated per-block resolution endpoint
 * @param featureFlagId  id of the runtime feature flag controlling the block
 */
public record BlockResolution(String resolutionPath, String featureFlagId) {
}
