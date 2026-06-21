package com.liverpool.ms_home.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the Salesforce recommendation engine (read-only reference: Rule 19).
 *
 * <p>All values are externalised — no URL, credential, or action name is ever hardcoded in
 * application logic (Rule 6). The authorization value must be injected via environment variable;
 * it is never committed to source control.</p>
 *
 * @param baseUrl              Salesforce Evergage base URL
 * @param actionsPath          path segment for the actions endpoint
 * @param authorizationValue   value of the {@code Authorization} header (env var only — no default)
 * @param timeout              HTTP read timeout for Salesforce calls
 * @param application          source application label sent in request body ({@code Web} or {@code App})
 * @param defaultCarouselAction Salesforce action name used when the block does not specify one
 */
@ConfigurationProperties(prefix = "salesforce")
public record SalesforceProperties(
        String baseUrl,
        String actionsPath,
        String authorizationValue,
        Duration timeout,
        String application,
        String defaultCarouselAction) {
}
