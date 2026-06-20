package com.liverpool.ms_home.domain.model.content;

/**
 * Parameters to fetch a raw Home definition from the content-service proxy.
 *
 * @param brand   brand identifier sent as {@code x-brand-id}
 * @param locale  content locale
 * @param path    page path / entry id ({@code ""} for the root home)
 * @param preview whether to request preview content
 */
public record ContentQuery(String brand, String locale, String path, boolean preview) {
}
