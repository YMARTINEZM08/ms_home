package com.liverpool.ms_home.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the dedicated {@link RestClient} for the Salesforce recommendation engine.
 *
 * <p>A separate client (rather than sharing the content-service one) keeps connection pools,
 * timeouts, and auth headers isolated per external system — a failure in one pool cannot starve
 * the other (Rule 4). The {@link OutboundLoggingInterceptor} is applied so all Salesforce calls
 * are observable; the interceptor automatically masks the {@code Authorization} header before
 * logging (Rule 10).</p>
 */
@Configuration
public class SalesforceConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Creates a connection-pooled, interceptor-wired {@link RestClient} bound to the Salesforce
     * Evergage endpoint.
     *
     * @param properties  Salesforce connection settings (URL, auth, timeout)
     * @param interceptor outbound logging/masking interceptor shared across all clients
     * @return a ready-to-use client named {@code salesforceRestClient}
     */
    @Bean
    public RestClient salesforceRestClient(SalesforceProperties properties,
                                           OutboundLoggingInterceptor interceptor) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(CONNECT_TIMEOUT, properties.timeout());
        ClientHttpRequestFactory factory =
                new BufferingClientHttpRequestFactory(
                        ClientHttpRequestFactoryBuilder.detect().build(settings));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, properties.authorizationValue())
                .requestFactory(factory)
                .requestInterceptor(interceptor)
                .build();
    }
}
