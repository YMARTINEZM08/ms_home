package com.liverpool.ms_home.domain.model.block.productslist;

import java.util.List;

/**
 * Resolved detail returned by the dedicated {@code products_list} endpoint.
 *
 * @param blockId  the resolved block id
 * @param title    carousel title
 * @param products products to render
 */
public record ProductsListResolution(String blockId, String title, List<ProductItem> products) {

    public ProductsListResolution {
        products = products == null ? List.of() : List.copyOf(products);
    }
}
