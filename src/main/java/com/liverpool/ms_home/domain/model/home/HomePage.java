package com.liverpool.ms_home.domain.model.home;

import java.util.List;

/**
 * The composed Home page: an ordered list of blocks for a locale. Order mirrors Contentstack exactly
 * and is never rearranged (Rule 18).
 *
 * @param locale content locale the page was composed for
 * @param blocks blocks in Contentstack order (static resolved, dynamic as placeholders)
 */
public record HomePage(String locale, List<HomeBlock> blocks) {

    public HomePage {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
