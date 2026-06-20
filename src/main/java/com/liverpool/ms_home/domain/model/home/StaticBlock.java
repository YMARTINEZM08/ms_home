package com.liverpool.ms_home.domain.model.home;

import java.util.Map;

/**
 * A block with no session/runtime dependency, fully resolved from Contentstack at composition time
 * and eligible for long-lived caching (Rule 18).
 *
 * @param blockId  Contentstack block uid
 * @param blockType classified type (e.g. {@link BlockType#BANNER})
 * @param content  resolved, immutable content payload as delivered by Contentstack
 */
public record StaticBlock(String blockId, BlockType blockType, Map<String, Object> content)
        implements HomeBlock {

    public StaticBlock {
        content = content == null ? Map.of() : Map.copyOf(content);
    }
}
