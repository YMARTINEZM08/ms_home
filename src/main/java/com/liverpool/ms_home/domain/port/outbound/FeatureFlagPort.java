package com.liverpool.ms_home.domain.port.outbound;

/**
 * Outbound port for evaluating runtime feature flags. Enables per-block enable/disable without
 * redeployment (Rule 18 feature toggle).
 */
public interface FeatureFlagPort {

    /**
     * @param flagId the flag identifier
     * @return true if the feature is enabled at runtime
     */
    boolean isEnabled(String flagId);
}
