package com.liverpool.ms_home.domain.model.home;

/**
 * Inbound request for composing a Home page.
 *
 * @param locale  content locale (e.g. {@code es-mx})
 * @param path    page path within the brand site (defaults handled by the caller)
 * @param preview whether to request preview content from Contentstack
 * @param session resolved session context (login/guest, brand, channel)
 */
public record HomePageQuery(String locale, String path, boolean preview, SessionContext session) {
}
