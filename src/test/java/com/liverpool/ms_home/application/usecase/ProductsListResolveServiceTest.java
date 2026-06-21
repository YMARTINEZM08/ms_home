package com.liverpool.ms_home.application.usecase;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.ms_home.domain.error.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.block.productslist.ProductItem;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.port.outbound.ProductsListPort;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductsListResolveServiceTest {

    @Mock
    private ProductsListPort productsListPort;

    private ProductsListResolveService service;

    private static final SessionContext SESSION = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final ProductsListQuery QUERY  = new ProductsListQuery("block-1", SESSION, "user-123");

    @BeforeEach
    void setUp() {
        service = new ProductsListResolveService(productsListPort, CircuitBreakerRegistry.ofDefaults());
    }

    // ── success path ─────────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_portSucceeds_returnsResolution() {
        ProductsListResolution expected = new ProductsListResolution(
                "block-1", "Best Sellers",
                List.of(new ProductItem("sku-001", "Product A", "99.99")));
        when(productsListPort.fetch(any())).thenReturn(expected);

        ProductsListResolution result = service.resolve(QUERY);

        assertThat(result).isEqualTo(expected);
    }

    // ── exception passthrough ─────────────────────────────────────────────────────────────────────

    @Test
    void resolve_portThrowsDomainException_rethrownUnchanged() {
        DynamicBlockServiceUnavailableException ex =
                new DynamicBlockServiceUnavailableException("block-1", "products_list", "Salesforce down", null);
        when(productsListPort.fetch(any())).thenThrow(ex);

        assertThatThrownBy(() -> service.resolve(QUERY))
                .isSameAs(ex);
    }

    @Test
    void resolve_portThrowsUnexpectedRuntime_wrappedInDynamicBlockException() {
        when(productsListPort.fetch(any())).thenThrow(new RuntimeException("unexpected"));

        assertThatThrownBy(() -> service.resolve(QUERY))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ── circuit breaker ──────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_circuitBreakerOpensAfterThresholdFailures_throwsServiceUnavailableException() {
        // Tight CB: 2-call window, ≥50% failure rate opens the breaker, minimum 2 calls.
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build();
        service = new ProductsListResolveService(productsListPort, CircuitBreakerRegistry.of(config));

        DynamicBlockServiceUnavailableException ex =
                new DynamicBlockServiceUnavailableException("block-1", "products_list", "down", null);
        when(productsListPort.fetch(any())).thenThrow(ex);

        // Two failures record a 100% failure rate against the 50% threshold; breaker opens.
        assertThatThrownBy(() -> service.resolve(QUERY)).isInstanceOf(DynamicBlockServiceUnavailableException.class);
        assertThatThrownBy(() -> service.resolve(QUERY)).isInstanceOf(DynamicBlockServiceUnavailableException.class);

        // Third call — breaker is open; call is short-circuited → ServiceUnavailableException.
        assertThatThrownBy(() -> service.resolve(QUERY))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
    }

    @Test
    void resolve_circuitBreakerOpen_doesNotCallPort() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .build();
        service = new ProductsListResolveService(productsListPort, CircuitBreakerRegistry.of(config));

        when(productsListPort.fetch(any())).thenThrow(
                new DynamicBlockServiceUnavailableException("b", "t", "d", null));

        // Trip the breaker.
        try { service.resolve(QUERY); } catch (Exception ignored) {}
        try { service.resolve(QUERY); } catch (Exception ignored) {}

        // Breaker open: ServiceUnavailableException thrown immediately, port is NOT called a 3rd time.
        assertThatThrownBy(() -> service.resolve(QUERY)).isInstanceOf(ServiceUnavailableException.class);

        // Verify the port was invoked exactly twice (not 3 times).
        org.mockito.Mockito.verify(productsListPort, org.mockito.Mockito.times(2)).fetch(any());
    }
}
