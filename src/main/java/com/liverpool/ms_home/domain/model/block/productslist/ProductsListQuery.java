package com.liverpool.ms_home.domain.model.block.productslist;

import com.liverpool.ms_home.domain.model.home.SessionContext;

/**
 * Request to resolve the dynamic {@code products_list} block via its dedicated endpoint.
 *
 * @param blockId the placeholder block id being resolved
 * @param session session context (the block is personalized only for authenticated sessions)
 */
public record ProductsListQuery(String blockId, SessionContext session) {
}
