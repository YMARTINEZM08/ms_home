package com.liverpool.ms_home.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Populates the MDC (Mapped Diagnostic Context) for every inbound request so that all log lines
 * emitted during request processing carry consistent correlation fields (Rule 11).
 *
 * <p>Fields set per request:
 * <ul>
 *   <li>{@code requestId} — echoed from {@code x-request-id} or generated as a UUID.</li>
 *   <li>{@code correlationId} — propagated from {@code x-correlation-id} or falls back to
 *       {@code requestId} so a single upstream trace can span multiple downstream hops.</li>
 *   <li>{@code service} — bound to {@code spring.application.name}; constant per deployment.</li>
 *   <li>{@code operation} — HTTP method + raw request URI (e.g. {@code "GET /home"}).</li>
 * </ul>
 *
 * <p>{@code traceId} and {@code spanId} are managed automatically by Micrometer Tracing + Brave via
 * {@code MDCScopeDecorator} and do not require manual population here.</p>
 *
 * <p>All four fields are unconditionally removed in a {@code finally} block so they never leak
 * across requests when the underlying thread (or virtual thread) is reused.</p>
 *
 * <p>The filter runs at {@link Ordered#HIGHEST_PRECEDENCE} + 10, before the Spring Security filter
 * chain, so that security-related events are also tagged with the correlation fields.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcRequestContextFilter extends OncePerRequestFilter {

    static final String HEADER_REQUEST_ID = "x-request-id";
    static final String HEADER_CORRELATION_ID = "x-correlation-id";

    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_SERVICE = "service";
    private static final String MDC_OPERATION = "operation";

    private final String applicationName;

    public MdcRequestContextFilter(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolveOrGenerate(request.getHeader(HEADER_REQUEST_ID));
        String correlationId = resolveOrFallback(request.getHeader(HEADER_CORRELATION_ID), requestId);
        String operation = request.getMethod() + " " + request.getRequestURI();

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_SERVICE, applicationName);
        MDC.put(MDC_OPERATION, operation);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_SERVICE);
            MDC.remove(MDC_OPERATION);
        }
    }

    private static String resolveOrGenerate(String header) {
        return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
    }

    private static String resolveOrFallback(String header, String fallback) {
        return (header != null && !header.isBlank()) ? header : fallback;
    }
}
