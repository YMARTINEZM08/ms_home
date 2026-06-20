package com.liverpool.ms_home.domain.port.inbound;

import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;

/**
 * Inbound port for the dedicated {@code products_list} resolution endpoint. Block-specific by design
 * (Rule 19.5) — each dynamic block owns its own use case rather than sharing a generic one.
 */
public interface ResolveProductsListUseCase {

    /**
     * Resolves the dynamic product carousel for a placeholder.
     *
     * @param query block id + session context
     * @return the resolved carousel detail
     */
    ProductsListResolution resolve(ProductsListQuery query);
}
