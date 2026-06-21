package com.liverpool.ms_home.adapter.inbound.rest.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.liverpool.ms_home.adapter.inbound.rest.dto.HomeBlockResponse;
import com.liverpool.ms_home.adapter.inbound.rest.dto.HomePageResponse;
import com.liverpool.ms_home.domain.model.home.DynamicPlaceholder;
import com.liverpool.ms_home.domain.model.home.HomeBlock;
import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.StaticBlock;

/**
 * Maps the composed {@link HomePage} domain object to the REST response DTO.
 *
 * <p>Uses sealed-interface pattern matching (Java 21) to exhaustively convert each {@link HomeBlock}
 * variant — a compile-time guarantee that no new block shape can be silently ignored (Rule 15).
 * No business logic lives here: ordering from the domain is preserved as-is (Rule 18).</p>
 */
@Component
public class HomePageMapper {

    private static final String KIND_STATIC = "STATIC";
    private static final String KIND_DYNAMIC = "DYNAMIC";

    /**
     * Converts a composed {@link HomePage} to its REST representation.
     *
     * @param page the domain page (locale + ordered blocks)
     * @return the response DTO ready for serialisation
     */
    public HomePageResponse toResponse(HomePage page) {
        List<HomeBlockResponse> blocks = page.blocks().stream()
                .map(this::toBlockResponse)
                .toList();
        return new HomePageResponse(page.locale(), blocks);
    }

    /**
     * Converts a single {@link HomeBlock} — sealed pattern match is exhaustive; the compiler
     * enforces that all permitted subtypes ({@link StaticBlock} and {@link DynamicPlaceholder})
     * are handled.
     *
     * @param block a block from the composed page
     * @return the flat block DTO with {@code kind} discriminator
     */
    private HomeBlockResponse toBlockResponse(HomeBlock block) {
        return switch (block) {
            case StaticBlock s -> new HomeBlockResponse(
                    s.blockId(),
                    s.blockType().name(),
                    KIND_STATIC,
                    s.content(),
                    null,
                    null,
                    null,
                    null);
            case DynamicPlaceholder d -> new HomeBlockResponse(
                    d.blockId(),
                    d.blockType().name(),
                    KIND_DYNAMIC,
                    null,
                    d.resolutionPath(),
                    d.fallback(),
                    d.featureFlagId(),
                    d.status().name());
        };
    }
}
