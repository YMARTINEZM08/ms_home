package com.liverpool.ms_home.domain.model.home;

/**
 * Classifies whether a Home block is resolved at composition time or deferred to a dedicated endpoint.
 *
 * <ul>
 *   <li>{@link #STATIC} — no session/runtime dependency; resolved inline and eligible for caching.</li>
 *   <li>{@link #DYNAMIC} — session/runtime dependent; returned as a placeholder for a per-block endpoint.</li>
 * </ul>
 */
public enum BlockKind {
    STATIC,
    DYNAMIC
}
