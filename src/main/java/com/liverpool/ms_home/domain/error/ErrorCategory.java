package com.liverpool.ms_home.domain.error;

/**
 * Centralised exception categories with default metadata (status + retryability) to remove
 * duplication across concrete exceptions (logger-handler skill). Concrete exceptions may override the
 * default status when a category spans several HTTP semantics.
 */
public enum ErrorCategory {

    VALIDATION(400, false),
    BUSINESS(422, false),
    RESOURCE_NOT_FOUND(404, false),
    EXTERNAL_SERVICE(502, true),
    TIMEOUT(504, true),
    CONFIGURATION(500, false),
    DATABASE(500, true),
    INFRASTRUCTURE(500, true),
    UNEXPECTED(500, false);

    private final int defaultStatus;
    private final boolean defaultRetryable;

    ErrorCategory(int defaultStatus, boolean defaultRetryable) {
        this.defaultStatus = defaultStatus;
        this.defaultRetryable = defaultRetryable;
    }

    public int defaultStatus() {
        return defaultStatus;
    }

    public boolean defaultRetryable() {
        return defaultRetryable;
    }
}
