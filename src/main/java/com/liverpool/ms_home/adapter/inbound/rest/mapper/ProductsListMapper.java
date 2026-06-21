package com.liverpool.ms_home.adapter.inbound.rest.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.liverpool.ms_home.adapter.inbound.rest.dto.ProductItemResponse;
import com.liverpool.ms_home.adapter.inbound.rest.dto.ProductsListResponse;
import com.liverpool.ms_home.domain.model.block.productslist.ProductItem;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;

/**
 * Maps the resolved {@link ProductsListResolution} domain object to the REST response DTO.
 */
@Component
public class ProductsListMapper {

    /**
     * Converts a {@link ProductsListResolution} to its REST representation.
     *
     * @param resolution the fully resolved product carousel
     * @return the response DTO ready for serialisation
     */
    public ProductsListResponse toResponse(ProductsListResolution resolution) {
        List<ProductItemResponse> products = resolution.products().stream()
                .map(this::toItemResponse)
                .toList();
        return new ProductsListResponse(resolution.blockId(), resolution.title(), products);
    }

    private ProductItemResponse toItemResponse(ProductItem item) {
        return new ProductItemResponse(item.sku(), item.title(), item.price());
    }
}
