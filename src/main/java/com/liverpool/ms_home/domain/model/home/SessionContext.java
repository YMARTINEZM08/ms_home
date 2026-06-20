package com.liverpool.ms_home.domain.model.home;

/**
 * Minimal, immutable session context the Home service needs (Rule 0).
 *
 * <p>ms-home does not perform token exchange; it consumes only the login/guest signal already
 * validated upstream, plus brand/channel/locale for content selection. Identity details are
 * deliberately absent — composition never personalizes.</p>
 *
 * @param authenticated true when the session is logged in; false for guests
 * @param brand         brand identifier (e.g. {@code LP})
 * @param channel       originating channel (e.g. {@code WEB})
 * @param locale        content locale (e.g. {@code es-mx})
 */
public record SessionContext(boolean authenticated, String brand, String channel, String locale) {

    /**
     * @return true when the session is a guest (not authenticated)
     */
    public boolean isGuest() {
        return !authenticated;
    }
}
