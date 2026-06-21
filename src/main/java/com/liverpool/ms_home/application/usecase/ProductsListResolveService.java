package com.liverpool.ms_home.application.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.liverpool.ms_home.domain.error.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_home.domain.error.ServiceUnavailableException;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListQuery;
import com.liverpool.ms_home.domain.model.block.productslist.ProductsListResolution;
import com.liverpool.ms_home.domain.model.home.BlockType;
import com.liverpool.ms_home.domain.port.inbound.ResolveProductsListUseCase;
import com.liverpool.ms_home.domain.port.outbound.ProductsListPort;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Application use case for resolving the dynamic {@code products_list} block via its dedicated
 * endpoint (Rule 18 — independent per-block resolution, Rule 19.5 — block-owned use case).
 *
 * <p>Applies the block's own circuit breaker ({@value #CB_NAME}) so that Salesforce instability
 * affects only the products carousel and never the rest of the Home page. The breaker inherits the
 * global 5%-threshold, no-retry policy from the shared {@link CircuitBreakerRegistry} (Rule 4).
 * Three distinct failure signals are propagated to the caller:</p>
 * <ul>
 *   <li>{@link ServiceUnavailableException} — circuit breaker open (load-shedding)</li>
 *   <li>{@link DynamicBlockServiceUnavailableException} — Salesforce returned an error (block down)</li>
 * </ul>
 */
@Service
public class ProductsListResolveService implements ResolveProductsListUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductsListResolveService.class);

    static final String CB_NAME = "products-list-salesforce";

    private final ProductsListPort productsListPort;
    private final CircuitBreaker circuitBreaker;

    /**
     * @param productsListPort the outbound port connecting to the backing data source (Salesforce)
     * @param cbRegistry       shared circuit-breaker registry; a named breaker is created on first use
     */
    public ProductsListResolveService(ProductsListPort productsListPort,
                                      CircuitBreakerRegistry cbRegistry) {
        this.productsListPort = productsListPort;
        this.circuitBreaker = cbRegistry.circuitBreaker(CB_NAME);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The circuit breaker wraps the entire {@link ProductsListPort#fetch} call. When the breaker
     * is open it short-circuits immediately without calling Salesforce, protecting the downstream
     * service from amplified load during an outage.</p>
     *
     * @param query block id and session context identifying the carousel to resolve
     * @return the fully resolved product carousel
     * @throws ServiceUnavailableException              when the circuit breaker is open
     * @throws DynamicBlockServiceUnavailableException  when the backing service returns an error
     */
    @Override
    public ProductsListResolution resolve(ProductsListQuery query) {
        log.info("Resolving products list blockId={} authenticated={}",
                query.blockId(), query.session().authenticated());
        try {
            return circuitBreaker.executeSupplier(() -> productsListPort.fetch(query));
        } catch (CallNotPermittedException e) {
            throw new ServiceUnavailableException(
                    "Circuit breaker '" + CB_NAME + "' is open; products-list call rejected.", e);
        } catch (DynamicBlockServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    "Unexpected error resolving products_list from backing service: " + e.getMessage(),
                    e);
        }
    }
}
