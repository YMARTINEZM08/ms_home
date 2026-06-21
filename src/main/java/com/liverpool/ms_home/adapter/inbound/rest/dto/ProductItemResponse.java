package com.liverpool.ms_home.adapter.inbound.rest.dto;

/**
 * A single product in the resolved {@code products_list} carousel response.
 *
 * @param sku   product SKU
 * @param title display title
 * @param price formatted display price
 */
public record ProductItemResponse(String sku, String title, String price) {
}
