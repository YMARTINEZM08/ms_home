package com.liverpool.ms_home.domain.error;

/**
 * Thrown when the content-service has no Home definition for the requested brand/locale/path (404).
 */
public final class HomeDefinitionNotFoundException extends HomeException {

    /**
     * @param message concise, consumer-facing message
     * @param detail  technical detail naming the brand/locale/path that was not found
     */
    public HomeDefinitionNotFoundException(String message, String detail) {
        super(ErrorCodes.HOME_DEFINITION_NOT_FOUND, ErrorCategory.RESOURCE_NOT_FOUND, message, detail, null);
    }
}
