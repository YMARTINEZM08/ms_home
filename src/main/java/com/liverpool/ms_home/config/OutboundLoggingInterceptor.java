package com.liverpool.ms_home.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Centralised logging for every outbound {@code RestClient} call (Rule 10).
 *
 * <p>Logs request method/URI, response status and latency at INFO; emits the equivalent cURL only at
 * DEBUG (incident use). Sensitive headers ({@code Authorization}, cookies, tokens) are always masked
 * before logging (Rule 10/11 + security): secrets must never reach the log pipeline.</p>
 */
@Component
public class OutboundLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OutboundLoggingInterceptor.class);

    /** Header names whose values are replaced with {@link #MASK} before logging. */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "delivery-token", "proxy-authorization");
    private static final String MASK = "***";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        long startNanos = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("outbound call method={} uri={} status={} elapsedMs={}",
                    request.getMethod(), request.getURI(), response.getStatusCode().value(), elapsedMs);
            if (log.isDebugEnabled()) {
                log.debug("outbound curl: {}", toCurl(request, body));
            }
            return response;
        } catch (IOException ex) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("outbound call failed method={} uri={} elapsedMs={} cause={}",
                    request.getMethod(), request.getURI(), elapsedMs, ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /**
     * Renders a masked cURL equivalent for DEBUG troubleshooting; never includes secret header values.
     *
     * @param request outbound request
     * @param body    request body bytes (may be empty)
     * @return a cURL command string safe to log
     */
    private String toCurl(HttpRequest request, byte[] body) {
        StringBuilder curl = new StringBuilder("curl -X ")
                .append(request.getMethod()).append(" '").append(request.getURI()).append('\'');
        request.getHeaders().forEach((name, values) ->
                curl.append(" -H '").append(name).append(": ").append(maskHeader(name, values)).append('\''));
        if (body.length > 0) {
            curl.append(" --data '").append(new String(body, StandardCharsets.UTF_8)).append('\'');
        }
        return curl.toString();
    }

    private String maskHeader(String name, List<String> values) {
        return SENSITIVE_HEADERS.contains(name.toLowerCase()) ? MASK : String.join(",", values);
    }
}
