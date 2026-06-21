package com.liverpool.ms_home.domain.model.content;

import java.util.List;
import java.util.Map;

/**
 * The raw Home page definition retrieved from the content-service proxy: ordered block definitions
 * for a content type and locale. Technology-free — the adapter maps the proxy's JSON into this.
 *
 * @param contentType the Contentstack content type (e.g. {@code page})
 * @param locale      content locale
 * @param pageTitle   page title from the CMS entry ({@code page_title}); null when absent
 * @param seo         SEO metadata map from the CMS entry ({@code seo}); empty when absent
 * @param blocks      ordered raw block definitions (order preserved from Contentstack)
 */
public record HomeDefinition(String contentType, String locale, String pageTitle,
                              Map<String, Object> seo, List<BlockDefinition> blocks) {

    public HomeDefinition {
        seo    = seo    == null ? Map.of()   : Map.copyOf(seo);
        blocks = blocks == null ? List.of()  : List.copyOf(blocks);
    }
}
