package com.liverpool.ms_home.adapter.inbound.rest.dto;

import java.util.Map;

/**
 * REST DTO for the {@code GET /global-data} response.
 *
 * <p>All three payload maps are opaque passthroughs from the CMS GlobalData entry so that the
 * frontend can consume entries by key without ms-home enforcing a rigid schema. Absent CMS fields
 * yield an empty map (never null).</p>
 *
 * @param locale          locale for which this GlobalData was fetched (e.g. {@code es-mx})
 * @param featureFlags    runtime feature-flag booleans keyed by flag name
 * @param publicVariables site-wide configuration values keyed by variable name
 * @param themes          brand theming tokens keyed by token name
 */
public record GlobalDataResponse(
        String locale,
        Map<String, Object> featureFlags,
        Map<String, Object> publicVariables,
        Map<String, Object> themes) {}
