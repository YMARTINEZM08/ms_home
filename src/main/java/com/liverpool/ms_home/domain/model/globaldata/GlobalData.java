package com.liverpool.ms_home.domain.model.globaldata;

import java.util.Map;

/**
 * Immutable domain model for the CMS GlobalData entry (Rule 2 — no Spring, no infrastructure).
 *
 * <p>GlobalData holds site-wide configuration that every frontend page needs independent of the
 * user session: feature flags, public runtime variables, and brand theme tokens. Fields are
 * surfaced as opaque maps to avoid tight coupling to individual CMS field names — the frontend
 * consumes the entries by key.</p>
 *
 * <p>All three maps are guaranteed non-null (empty when the CMS entry omits the key); callers
 * do not need null-checks.</p>
 *
 * @param locale          locale for which this GlobalData was fetched (e.g. {@code es-mx})
 * @param featureFlags    runtime feature-flag booleans (e.g. {@code salesforce: true})
 * @param publicVariables site-wide config values (e.g. {@code site_domain}, CDN paths)
 * @param themes          brand theming tokens (e.g. primary / secondary colours)
 */
public record GlobalData(
        String locale,
        Map<String, Object> featureFlags,
        Map<String, Object> publicVariables,
        Map<String, Object> themes) {

    public GlobalData {
        featureFlags    = featureFlags    == null ? Map.of() : Map.copyOf(featureFlags);
        publicVariables = publicVariables == null ? Map.of() : Map.copyOf(publicVariables);
        themes          = themes          == null ? Map.of() : Map.copyOf(themes);
    }
}
