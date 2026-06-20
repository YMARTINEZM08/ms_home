package com.liverpool.ms_home.adapter.outbound.featureflag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_home.config.HomeProperties;
import com.liverpool.ms_home.domain.port.outbound.FeatureFlagPort;

/**
 * Configuration-backed adapter for runtime feature flag evaluation (Rule 18 feature toggle).
 *
 * <p>Flags are read from {@code home.feature-flags.*} in the application configuration and
 * overridable via environment variables — no redeploy is needed to toggle a block. Unknown flag ids
 * default to {@code false} (safe: a missing flag disables the block rather than enabling it
 * unexpectedly). This adapter has no external I/O; Spring Boot refreshable configuration or a remote
 * flag service can be wired in later without changing the port.</p>
 */
@Component
public class FeatureFlagAdapter implements FeatureFlagPort {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagAdapter.class);

    private final HomeProperties homeProperties;

    /**
     * @param homeProperties home configuration containing the {@code feature-flags} map
     */
    public FeatureFlagAdapter(HomeProperties homeProperties) {
        this.homeProperties = homeProperties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Looks up {@code flagId} in the {@code home.feature-flags} configuration map. Returns
     * {@code false} when the flag is absent (safe default). Logs a debug line when a flag is
     * evaluated so that toggling behaviour is always traceable.</p>
     *
     * @param flagId the flag identifier (e.g. {@code products-list-salesforce})
     */
    @Override
    public boolean isEnabled(String flagId) {
        boolean enabled = Boolean.TRUE.equals(homeProperties.featureFlags().get(flagId));
        log.debug("Feature flag '{}' evaluated to {}", flagId, enabled);
        return enabled;
    }
}
