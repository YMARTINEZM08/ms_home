package com.liverpool.ms_home.domain.model.globaldata;

/**
 * Inbound query for the GlobalData use case.
 *
 * @param brand   brand identifier for the {@code x-brand-id} header (e.g. {@code LP})
 * @param locale  locale determining which CMS entry is fetched (e.g. {@code es-mx})
 * @param preview when {@code true} fetches the Contentstack preview variant
 */
public record GlobalDataQuery(String brand, String locale, boolean preview) {}
