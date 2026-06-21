package com.liverpool.ms_home.adapter.inbound.rest.controller;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.liverpool.ms_home.adapter.inbound.rest.dto.ProductItemResponse;
import com.liverpool.ms_home.adapter.inbound.rest.dto.ProductsListResponse;
import com.liverpool.ms_home.adapter.inbound.rest.mapper.ProductsListMapper;
import com.liverpool.ms_home.config.MdcRequestContextFilter;
import com.liverpool.ms_home.config.SecurityConfig;
import com.liverpool.ms_home.domain.error.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.block.productslist.ProductItem;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.inbound.ResolveProductsListUseCase;
import com.liverpool.ms_home.domain.port.outbound.SessionContextPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link ProductsListResolveController} — loads only the web layer.
 */
@WebMvcTest(ProductsListResolveController.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class})
class ProductsListResolveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolveProductsListUseCase resolveProductsListUseCase;

    @MockitoBean
    private SessionContextPort sessionContextPort;

    @MockitoBean
    private ProductsListMapper productsListMapper;

    private static final SessionContext AUTH_SESSION  = new SessionContext(true,  "LP", "WEB", "es-mx");
    private static final SessionContext GUEST_SESSION = new SessionContext(false, "LP", "WEB", "es-mx");

    private static final String BLOCK_ID = "bltabc123def456";
    private static final String USER_ID  = "31005309570";

    private static final ProductsListResolution RESOLUTION = new ProductsListResolution(
            BLOCK_ID, "Best Sellers",
            List.of(new ProductItem("sku-001", "Product A", "99.99")));

    private static final ProductsListResponse RESPONSE_DTO = new ProductsListResponse(
            BLOCK_ID, "Best Sellers",
            List.of(new ProductItemResponse("sku-001", "Product A", "99.99")));

    @BeforeEach
    void setUp() {
        when(sessionContextPort.currentContext()).thenReturn(AUTH_SESSION);
    }

    // ── 200 happy paths ──────────────────────────────────────────────────────────────────────────

    @Test
    void resolveProductsList_success_returns200WithProductItems() throws Exception {
        when(resolveProductsListUseCase.resolve(any())).thenReturn(RESOLUTION);
        when(productsListMapper.toResponse(RESOLUTION)).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID)
                        .header("x-user-id", USER_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.blockId").value(BLOCK_ID))
                .andExpect(jsonPath("$.title").value("Best Sellers"))
                .andExpect(jsonPath("$.products[0].sku").value("sku-001"))
                .andExpect(jsonPath("$.products[0].title").value("Product A"))
                .andExpect(jsonPath("$.products[0].price").value("99.99"));
    }

    @Test
    void resolveProductsList_noProducts_returns200WithEmptyList() throws Exception {
        ProductsListResolution emptyResolution = new ProductsListResolution(BLOCK_ID, "", List.of());
        ProductsListResponse emptyDto = new ProductsListResponse(BLOCK_ID, "", List.of());
        when(resolveProductsListUseCase.resolve(any())).thenReturn(emptyResolution);
        when(productsListMapper.toResponse(emptyResolution)).thenReturn(emptyDto);

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").isArray())
                .andExpect(jsonPath("$.products").isEmpty());
    }

    @Test
    void resolveProductsList_requestIdEchoedInResponseHeader() throws Exception {
        when(resolveProductsListUseCase.resolve(any())).thenReturn(RESOLUTION);
        when(productsListMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID)
                        .header("x-request-id", "pl-req-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", "pl-req-1"));
    }

    @Test
    void resolveProductsList_noRequestId_generatesUuidInResponseHeader() throws Exception {
        when(resolveProductsListUseCase.resolve(any())).thenReturn(RESOLUTION);
        when(productsListMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", notNullValue()));
    }

    // ── userId forwarding ────────────────────────────────────────────────────────────────────────

    @Test
    void resolveProductsList_userIdHeader_forwardedToUseCase() throws Exception {
        when(resolveProductsListUseCase.resolve(any())).thenReturn(RESOLUTION);
        when(productsListMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID)
                        .header("x-user-id", USER_ID))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductsListQuery> captor = ArgumentCaptor.forClass(ProductsListQuery.class);
        Mockito.verify(resolveProductsListUseCase).resolve(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().blockId()).isEqualTo(BLOCK_ID);
    }

    @Test
    void resolveProductsList_noUserIdHeader_nullForwardedToUseCase() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(resolveProductsListUseCase.resolve(any())).thenReturn(RESOLUTION);
        when(productsListMapper.toResponse(any())).thenReturn(RESPONSE_DTO);

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductsListQuery> captor = ArgumentCaptor.forClass(ProductsListQuery.class);
        Mockito.verify(resolveProductsListUseCase).resolve(captor.capture());
        assertThat(captor.getValue().userId()).isNull();
    }

    // ── error scenarios ───────────────────────────────────────────────────────────────────────────

    @Test
    void resolveProductsList_dynamicBlockServiceUnavailable_returns502WithBlockContext() throws Exception {
        when(resolveProductsListUseCase.resolve(any()))
                .thenThrow(new DynamicBlockServiceUnavailableException(
                        BLOCK_ID, "products_list", "Salesforce returned 500", null));

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID)
                        .header("x-user-id", USER_ID))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("BLOCK_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.blockId").value(BLOCK_ID))
                .andExpect(jsonPath("$.blockType").value("products_list"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void resolveProductsList_circuitBreakerOpen_returns503() throws Exception {
        when(resolveProductsListUseCase.resolve(any()))
                .thenThrow(new ServiceUnavailableException("CB open", null));

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void resolveProductsList_unexpectedError_returns500WithoutInternalDetail() throws Exception {
        when(resolveProductsListUseCase.resolve(any()))
                .thenThrow(new RuntimeException("internal: secret=xyz"));

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", BLOCK_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UNEXPECTED_ERROR"))
                .andExpect(jsonPath("$.detail").value(
                        "An unexpected error occurred. Please try again later."));
    }

    // ── input validation ─────────────────────────────────────────────────────────────────────────

    @Test
    void resolveProductsList_blockIdTooLong_returns400ValidationError() throws Exception {
        String tooLong = "b".repeat(129);

        mockMvc.perform(get("/home/blocks/products-list/{blockId}", tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }
}
