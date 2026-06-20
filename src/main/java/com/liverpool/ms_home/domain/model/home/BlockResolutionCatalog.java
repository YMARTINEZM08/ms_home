package com.liverpool.ms_home.domain.model.home;

import java.util.Map;
import java.util.Optional;

/**
 * Immutable lookup from a dynamic {@link BlockType} to its {@link BlockResolution} (endpoint + flag).
 *
 * <p>Lets the composition service build placeholders without knowing how endpoints/flags are
 * configured. New dynamic blocks are added by extending the backing map (Open/Closed).</p>
 *
 * @param resolutions per-type resolution metadata
 */
public record BlockResolutionCatalog(Map<BlockType, BlockResolution> resolutions) {

    public BlockResolutionCatalog {
        resolutions = resolutions == null ? Map.of() : Map.copyOf(resolutions);
    }

    /**
     * @param type the dynamic block type
     * @return the resolution metadata if configured for this type
     */
    public Optional<BlockResolution> forType(BlockType type) {
        return Optional.ofNullable(resolutions.get(type));
    }
}
