package com.liverpool.ms_home.domain.model.content;

import java.util.Map;

/**
 * A raw, unclassified block as delivered by Contentstack, before the composition service decides
 * whether it resolves statically or becomes a dynamic placeholder.
 *
 * @param uid            Contentstack block uid ({@code _uid})
 * @param contentTypeUid Contentstack content type ({@code _content_type_uid})
 * @param audienceFilter raw {@code audience_filter} value (may be null)
 * @param enabledOnWeb   whether the block is enabled for web
 * @param enabledOnApps  whether the block is enabled for apps
 * @param attributes     remaining raw block attributes
 */
public record BlockDefinition(
        String uid,
        String contentTypeUid,
        String audienceFilter,
        boolean enabledOnWeb,
        boolean enabledOnApps,
        Map<String, Object> attributes) {

    public BlockDefinition {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
