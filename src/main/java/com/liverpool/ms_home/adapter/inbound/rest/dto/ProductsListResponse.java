package com.liverpool.ms_home.adapter.inbound.rest.dto;

import java.util.List;

/**
 * Response body for {@code GET /home/blocks/products-list/{blockId}}.
 *
 * @param blockId  the resolved block id echoed back for correlation
 * @param title    carousel display title
 * @param products ordered list of products in the carousel
 */
public record ProductsListResponse(String blockId, String title, List<ProductItemResponse> products) {
}
