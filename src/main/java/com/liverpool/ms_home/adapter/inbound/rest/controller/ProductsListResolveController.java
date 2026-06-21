package com.liverpool.ms_home.adapter.inbound.rest.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_home.adapter.inbound.rest.dto.ProductsListResponse;
import com.liverpool.ms_home.adapter.inbound.rest.mapper.ProductsListMapper;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.inbound.ResolveProductsListUseCase;
import com.liverpool.ms_home.domain.port.outbound.SessionContextPort;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound adapter for the {@code products_list} block's dedicated resolution endpoint.
 *
 * <p><strong>Contract:</strong> {@code GET /home/blocks/products-list/{blockId}}</p>
 *
 * <p>Dynamic blocks are not resolved by {@code GET /home} — they return placeholders. The frontend
 * calls this endpoint independently for each products-list block it encounters in the page layout.
 * This separation ensures: one block failure cannot prevent the rest of the page from rendering;
 * each block can be toggled and observed independently (Rule 18, locked decision 3).</p>
 *
 * <p>The block's own circuit breaker is applied inside {@link ResolveProductsListUseCase}. When the
 * breaker is open this endpoint returns HTTP 503; when the backing service is unhealthy it returns
 * HTTP 502 — the frontend must render the block's fallback in both cases.</p>
 */
@RestController
@RequestMapping("/home/blocks/products-list")
@Validated
public class ProductsListResolveController {

    private static final Logger log = LoggerFactory.getLogger(ProductsListResolveController.class);

    private static final int BLOCK_ID_MAX_LENGTH = 128;

    private final ResolveProductsListUseCase resolveProductsListUseCase;
    private final SessionContextPort sessionContextPort;
    private final ProductsListMapper productsListMapper;

    /**
     * @param resolveProductsListUseCase resolves the carousel behind its own circuit breaker
     * @param sessionContextPort         resolves the session from upstream-validated headers
     * @param productsListMapper         converts domain resolution to REST DTO
     */
    public ProductsListResolveController(ResolveProductsListUseCase resolveProductsListUseCase,
                                         SessionContextPort sessionContextPort,
                                         ProductsListMapper productsListMapper) {
        this.resolveProductsListUseCase = resolveProductsListUseCase;
        this.sessionContextPort = sessionContextPort;
        this.productsListMapper = productsListMapper;
    }

    static final String HEADER_USER_ID = "x-user-id";

    /**
     * Resolves the dynamic {@code products_list} block for the given block id and session.
     *
     * <p>The {@code x-user-id} header carries the ATG profile id set by the upstream API gateway
     * after validating the session token. It is required by Salesforce to personalise the carousel;
     * the adapter returns a 502 when the session is authenticated but the header is missing.</p>
     *
     * @param blockId the Contentstack uid of the placeholder block (non-blank, max 128 chars)
     * @param userId  ATG profile id from the gateway; absent for guest sessions
     * @return 200 with the resolved product carousel; error cases handled by
     *         {@link GlobalExceptionHandler}
     */
    @GetMapping("/{blockId}")
    public ResponseEntity<ProductsListResponse> resolveProductsList(
            @PathVariable @NotBlank @Size(max = BLOCK_ID_MAX_LENGTH) String blockId,
            @RequestHeader(value = HEADER_USER_ID, required = false) String userId) {

        SessionContext session = sessionContextPort.currentContext();

        log.info("GET /home/blocks/products-list/{} brand={} authenticated={} hasUserId={}",
                blockId, session.brand(), session.authenticated(), userId != null && !userId.isBlank());

        ProductsListQuery query = new ProductsListQuery(blockId, session, userId);
        ProductsListResolution resolution = resolveProductsListUseCase.resolve(query);
        ProductsListResponse response = productsListMapper.toResponse(resolution);

        return ResponseEntity.ok()
                .header(HomeController.HEADER_REQUEST_ID, MDC.get("requestId"))
                .body(response);
    }
}
