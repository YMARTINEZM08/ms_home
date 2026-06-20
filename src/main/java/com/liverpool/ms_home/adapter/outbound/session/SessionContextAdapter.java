package com.liverpool.ms_home.adapter.outbound.session;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.outbound.SessionContextPort;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Request-scoped adapter that satisfies {@link SessionContextPort} by reading upstream-validated
 * headers from the current HTTP request.
 *
 * <p>ms-home is stateless (Rule 5) and delegates authentication entirely to the upstream API
 * gateway. The gateway validates the OAuth token and propagates the session signal as trusted HTTP
 * headers before forwarding the request. This adapter reads those headers and builds the minimal
 * {@link SessionContext} the composition service needs — no token exchange, no identity details, no
 * personalization (Rule 0).</p>
 *
 * <h3>Expected upstream headers:</h3>
 * <ul>
 *   <li>{@code x-authenticated} — {@code true} when the session is logged in, otherwise guest</li>
 *   <li>{@code x-brand-id} — brand identifier; falls back to {@code content-service.default-brand}</li>
 *   <li>{@code x-channel} — originating channel ({@code WEB} by default)</li>
 *   <li>{@code x-locale} — content locale (e.g. {@code es-mx})</li>
 * </ul>
 */
@Component
@RequestScope
public class SessionContextAdapter implements SessionContextPort {

    static final String HEADER_AUTHENTICATED = "x-authenticated";
    static final String HEADER_BRAND_ID = "x-brand-id";
    static final String HEADER_CHANNEL = "x-channel";
    static final String HEADER_LOCALE = "x-locale";

    private static final String DEFAULT_CHANNEL = "WEB";
    private static final String DEFAULT_LOCALE = "es-mx";
    private static final String TRUE_VALUE = "true";

    private final HttpServletRequest request;
    private final ContentstackProperties properties;

    /**
     * @param request    the in-flight HTTP request (injected by Spring's request scope proxy)
     * @param properties content-service config supplying the brand fallback
     */
    public SessionContextAdapter(HttpServletRequest request, ContentstackProperties properties) {
        this.request = request;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads session signals from upstream-validated headers. Falls back to safe defaults when
     * optional headers are absent — brand from configuration, channel {@code WEB}, locale
     * {@code es-mx}. Authentication defaults to guest when the header is missing or not
     * {@code true}.</p>
     */
    @Override
    public SessionContext currentContext() {
        boolean authenticated = TRUE_VALUE.equalsIgnoreCase(request.getHeader(HEADER_AUTHENTICATED));
        String brand = headerOrDefault(HEADER_BRAND_ID, properties.defaultBrand());
        String channel = headerOrDefault(HEADER_CHANNEL, DEFAULT_CHANNEL);
        String locale = headerOrDefault(HEADER_LOCALE, DEFAULT_LOCALE);
        return new SessionContext(authenticated, brand, channel, locale);
    }

    private String headerOrDefault(String headerName, String defaultValue) {
        String value = request.getHeader(headerName);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
