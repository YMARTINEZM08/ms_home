package com.liverpool.ms_home.domain.model.block.productslist;

import com.liverpool.ms_home.domain.model.home.SessionContext;

/**
 * Request to resolve the dynamic {@code products_list} block via its dedicated endpoint.
 *
 * <p>The {@code userId} carries the ATG profile identifier validated by the upstream API gateway.
 * It is required by the Salesforce recommendation engine to personalise the carousel. It is absent
 * ({@code null}) for guest sessions, in which case the adapter must not call Salesforce (Rule 0 —
 * composition never personalises; this query is for the independent resolution endpoint only).</p>
 *
 * @param blockId the placeholder block id being resolved
 * @param session session context (the block is personalised only for authenticated sessions)
 * @param userId  the ATG user profile id injected by the gateway; {@code null} for guests
 */
public record ProductsListQuery(String blockId, SessionContext session, String userId) {
}
