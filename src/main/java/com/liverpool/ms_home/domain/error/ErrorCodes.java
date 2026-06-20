package com.liverpool.ms_home.domain.error;

/**
 * Stable, machine-readable error codes surfaced to API consumers (Rule 20 — no literal strings).
 *
 * <p>The three dynamic-block signals are intentionally distinct so the frontend can tell them apart:
 * {@link #BLOCK_DISABLED} (runtime off) vs {@link #BLOCK_SERVICE_UNAVAILABLE} (backing service down)
 * vs {@link #SERVICE_UNAVAILABLE} (circuit breaker open).</p>
 */
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String HOME_DEFINITION_NOT_FOUND = "HOME_DEFINITION_NOT_FOUND";
    public static final String CONTENT_SERVICE_UNAVAILABLE = "CONTENT_SERVICE_UNAVAILABLE";
    public static final String BLOCK_DISABLED = "BLOCK_DISABLED";
    public static final String BLOCK_SERVICE_UNAVAILABLE = "BLOCK_SERVICE_UNAVAILABLE";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String CONFIGURATION_ERROR = "CONFIGURATION_ERROR";
    public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

    private ErrorCodes() {
    }
}
