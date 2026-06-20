package com.liverpool.ms_home.domain.port.outbound;

import com.liverpool.ms_home.domain.model.home.SessionContext;

/**
 * Outbound port exposing the current request's session context (login/guest + brand/channel/locale),
 * derived from the upstream-validated token. Keeps request-scoped, framework-specific access out of
 * the domain.
 */
public interface SessionContextPort {

    /**
     * @return the session context for the in-flight request
     */
    SessionContext currentContext();
}
