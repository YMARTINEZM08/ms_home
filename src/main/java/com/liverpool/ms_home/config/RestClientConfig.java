package com.liverpool.ms_home.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the single pooled {@link RestClient} used for all outbound HTTP (Rule 3.1/4/10).
 *
 * <p>{@code RestClient} replaces {@code RestTemplate}; connection reuse plus virtual threads give
 * low-latency, low-overhead I/O. The factory is buffered so {@link OutboundLoggingInterceptor} can
 * inspect bodies without consuming the stream.</p>
 */
@Configuration
public class RestClientConfig {

    /**
     * Creates the content-service {@link RestClient} with externalised timeouts and centralised logging.
     *
     * @param properties  content-service connection settings
     * @param interceptor outbound logging/masking interceptor
     * @return a ready-to-use, base-URL-bound client
     */
    @Bean
    public RestClient contentServiceRestClient(ContentstackProperties properties,
                                               OutboundLoggingInterceptor interceptor) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(properties.connectTimeout(), properties.readTimeout());
        ClientHttpRequestFactory factory =
                new BufferingClientHttpRequestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .requestInterceptor(interceptor)
                .build();
    }
}
