package com.liverpool.ms_home.domain.model.content;

import java.util.List;

/**
 * The raw Home page definition retrieved from the content-service proxy: ordered block definitions
 * for a content type and locale. Technology-free — the adapter maps the proxy's JSON into this.
 *
 * @param contentType the Contentstack content type (e.g. {@code page})
 * @param locale      content locale
 * @param blocks      ordered raw block definitions (order preserved from Contentstack)
 */
public record HomeDefinition(String contentType, String locale, List<BlockDefinition> blocks) {

    public HomeDefinition {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
