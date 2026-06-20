package com.liverpool.ms_home.domain.model.home;

/**
 * Status carried by a {@link DynamicPlaceholder} so the frontend can tell <em>why</em> a dynamic block
 * is or isn't resolvable — these three states must never collapse into one another.
 */
public enum DynamicBlockStatus {

    /** The block is enabled; the frontend should call its resolution endpoint. */
    AVAILABLE,

    /** The block is intentionally turned off at runtime (feature flag); not an error — skip it. */
    DISABLED,

    /** The block's backing service is known to be unhealthy; render the fallback instead. */
    UNAVAILABLE
}
