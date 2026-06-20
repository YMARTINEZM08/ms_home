package com.liverpool.ms_home.domain.model.block.productslist;

/**
 * A single product in a resolved {@code products_list} carousel. Owned by the products-list block
 * (no generic cross-block DTO — Rule 19.5).
 *
 * @param sku   product sku
 * @param title display title
 * @param price formatted price
 */
public record ProductItem(String sku, String title, String price) {
}
