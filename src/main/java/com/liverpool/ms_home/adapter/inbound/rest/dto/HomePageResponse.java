package com.liverpool.ms_home.adapter.inbound.rest.dto;

import java.util.List;

/**
 * Response body for {@code GET /home}.
 *
 * <p>Blocks are in the exact Contentstack order — never reordered (Rule 18). Static blocks carry
 * their resolved content; dynamic blocks carry placeholders so the frontend can call each block's
 * dedicated resolution endpoint independently.</p>
 *
 * @param locale content locale the page was composed for (e.g. {@code es-mx})
 * @param blocks ordered list of composed blocks
 */
public record HomePageResponse(String locale, List<HomeBlockResponse> blocks) {
}
