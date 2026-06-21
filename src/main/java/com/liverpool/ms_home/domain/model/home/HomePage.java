package com.liverpool.ms_home.domain.model.home;

import java.util.List;
import java.util.Map;

/**
 * The composed Home page: an ordered list of blocks for a locale. Order mirrors Contentstack exactly
 * and is never rearranged (Rule 18).
 *
 * @param locale    content locale the page was composed for
 * @param pageTitle page title from the CMS entry; null when absent
 * @param seo       SEO metadata map from the CMS entry; empty when absent
 * @param blocks    blocks in Contentstack order (static resolved, dynamic as placeholders)
 */
public record HomePage(String locale, String pageTitle, Map<String, Object> seo, List<HomeBlock> blocks) {

    public HomePage {
        seo    = seo    == null ? Map.of()  : Map.copyOf(seo);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
