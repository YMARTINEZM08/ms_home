package com.liverpool.ms_home.domain.port.outbound;

import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;

/**
 * Outbound port for the {@code products_list} block's backing data source. Owned by this block alone
 * (Rule 19.5); its adapter applies the circuit breaker and translates downstream failures into
 * domain exceptions.
 */
public interface ProductsListPort {

    /**
     * Fetches the product carousel detail from the backing source.
     *
     * @param query block id + session context
     * @return resolved carousel detail
     */
    ProductsListResolution fetch(ProductsListQuery query);
}
