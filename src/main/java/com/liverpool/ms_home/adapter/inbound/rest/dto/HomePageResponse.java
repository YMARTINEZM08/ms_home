package com.liverpool.ms_home.adapter.inbound.rest.dto;

import java.util.List;
import java.util.Map;

/**
 * Response body for {@code GET /home}.
 *
 * <p>Blocks are in the exact Contentstack order — never reordered (Rule 18). Static blocks carry
 * their resolved content; dynamic blocks carry placeholders so the frontend can call each block's
 * dedicated resolution endpoint independently.</p>
 *
 * @param locale    content locale the page was composed for (e.g. {@code es-mx})
 * @param pageTitle page title from the CMS entry ({@code page_title}); null when absent
 * @param seo       SEO metadata map ({@code seo}); empty when the CMS entry has no SEO block
 * @param blocks    ordered list of composed blocks
 */
public record HomePageResponse(String locale, String pageTitle, Map<String, Object> seo,
                                List<HomeBlockResponse> blocks) {
}
