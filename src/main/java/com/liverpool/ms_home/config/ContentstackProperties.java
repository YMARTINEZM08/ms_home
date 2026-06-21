package com.liverpool.ms_home.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the {@code content-service} proxy that fronts Contentstack (Rule 2/6/17).
 *
 * <p>All values originate from environment variables so no URL, brand, or timeout is ever hardcoded
 * in application logic (Twelve-Factor / Rule 6). The Contentstack SDK never leaks into the codebase;
 * ms-home only knows this HTTP contract.</p>
 *
 * @param baseUrl               base URL of the content-service proxy
 * @param defaultBrand          brand identifier sent in the {@code x-brand-id} header when none is supplied
 * @param previewHeader         inbound request header whose presence switches the brand to preview mode
 * @param connectTimeout        TCP connect timeout for outbound calls
 * @param readTimeout           response read timeout for outbound calls
 * @param homeContentType       Contentstack content-type uid for the Home page entry (e.g. {@code page})
 * @param homeEntryId           Contentstack entry id for the Home root (e.g. {@code home})
 * @param cacheTtl              Redis L2 cache TTL for static Home definitions
 * @param l1CacheTtl            Caffeine L1 cache TTL for static Home definitions (in-process)
 * @param globalDataContentType Contentstack content-type uid for the GlobalData entry
 * @param globalDataEntryId     Contentstack entry id for the GlobalData root
 * @param globalDataCacheTtl    Caffeine L1 cache TTL for GlobalData (changes rarely; long TTL acceptable)
 */
@ConfigurationProperties(prefix = "content-service")
public record ContentstackProperties(
        String baseUrl,
        String defaultBrand,
        String previewHeader,
        Duration connectTimeout,
        Duration readTimeout,
        String homeContentType,
        String homeEntryId,
        Duration cacheTtl,
        Duration l1CacheTtl,
        String globalDataContentType,
        String globalDataEntryId,
        Duration globalDataCacheTtl) {
}
