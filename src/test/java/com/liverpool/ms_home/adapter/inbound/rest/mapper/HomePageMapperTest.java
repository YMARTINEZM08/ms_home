package com.liverpool.ms_home.adapter.inbound.rest.mapper;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_home.adapter.inbound.rest.dto.HomeBlockResponse;
import com.liverpool.ms_home.adapter.inbound.rest.dto.HomePageResponse;
import com.liverpool.ms_home.domain.model.home.BlockType;
import com.liverpool.ms_home.domain.model.home.DynamicBlockStatus;
import com.liverpool.ms_home.domain.model.home.DynamicPlaceholder;
import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.StaticBlock;

import static org.assertj.core.api.Assertions.assertThat;

class HomePageMapperTest {

    private HomePageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new HomePageMapper();
    }

    // ── static blocks ────────────────────────────────────────────────────────────────────────────

    @Test
    void toResponse_staticBlock_kindIsStatic() {
        StaticBlock block = new StaticBlock("uid-1", BlockType.BANNER, Map.of("title", "Hello"));
        HomePageResponse response = mapper.toResponse(new HomePage("es-mx", List.of(block)));

        HomeBlockResponse dto = response.blocks().get(0);
        assertThat(dto.kind()).isEqualTo("STATIC");
        assertThat(dto.blockId()).isEqualTo("uid-1");
        assertThat(dto.blockType()).isEqualTo("BANNER");
        assertThat(dto.content()).containsEntry("title", "Hello");
    }

    @Test
    void toResponse_staticBlock_dynamicFieldsAreNull() {
        StaticBlock block = new StaticBlock("uid-2", BlockType.BANNER, Map.of());
        HomePageResponse response = mapper.toResponse(new HomePage("es-mx", List.of(block)));

        HomeBlockResponse dto = response.blocks().get(0);
        assertThat(dto.resolutionPath()).isNull();
        assertThat(dto.fallback()).isNull();
        assertThat(dto.featureFlagId()).isNull();
        assertThat(dto.status()).isNull();
    }

    // ── dynamic placeholders ─────────────────────────────────────────────────────────────────────

    @Test
    void toResponse_dynamicPlaceholder_kindIsDynamic() {
        DynamicPlaceholder ph = new DynamicPlaceholder(
                "uid-3", BlockType.PRODUCTS_LIST,
                "/home/blocks/products-list", "fallback-payload",
                "products-list-salesforce", DynamicBlockStatus.AVAILABLE);

        HomePageResponse response = mapper.toResponse(new HomePage("es-mx", List.of(ph)));

        HomeBlockResponse dto = response.blocks().get(0);
        assertThat(dto.kind()).isEqualTo("DYNAMIC");
        assertThat(dto.blockId()).isEqualTo("uid-3");
        assertThat(dto.blockType()).isEqualTo("PRODUCTS_LIST");
        assertThat(dto.resolutionPath()).isEqualTo("/home/blocks/products-list");
        assertThat(dto.fallback()).isEqualTo("fallback-payload");
        assertThat(dto.featureFlagId()).isEqualTo("products-list-salesforce");
        assertThat(dto.status()).isEqualTo("AVAILABLE");
    }

    @Test
    void toResponse_dynamicPlaceholder_contentIsNull() {
        DynamicPlaceholder ph = new DynamicPlaceholder(
                "uid-4", BlockType.PRODUCTS_LIST,
                "/home/blocks/products-list", null,
                "products-list-salesforce", DynamicBlockStatus.DISABLED);

        HomePageResponse response = mapper.toResponse(new HomePage("es-mx", List.of(ph)));

        assertThat(response.blocks().get(0).content()).isNull();
    }

    @Test
    void toResponse_disabledPlaceholder_statusIsDisabled() {
        DynamicPlaceholder ph = new DynamicPlaceholder(
                "uid-5", BlockType.PRODUCTS_LIST,
                "/home/blocks/products-list", null,
                "flag-id", DynamicBlockStatus.DISABLED);

        HomeBlockResponse dto = mapper.toResponse(new HomePage("es-mx", List.of(ph))).blocks().get(0);

        assertThat(dto.status()).isEqualTo("DISABLED");
    }

    // ── page-level fields ────────────────────────────────────────────────────────────────────────

    @Test
    void toResponse_preservesLocale() {
        HomePageResponse response = mapper.toResponse(new HomePage("pt-br", List.of()));

        assertThat(response.locale()).isEqualTo("pt-br");
    }

    @Test
    void toResponse_emptyPage_returnsEmptyBlockList() {
        HomePageResponse response = mapper.toResponse(new HomePage("es-mx", List.of()));

        assertThat(response.blocks()).isEmpty();
    }

    @Test
    void toResponse_preservesBlockOrderFromDomainPage() {
        StaticBlock first  = new StaticBlock("first",  BlockType.BANNER, Map.of());
        StaticBlock second = new StaticBlock("second", BlockType.BANNER, Map.of());
        StaticBlock third  = new StaticBlock("third",  BlockType.BANNER, Map.of());

        HomePageResponse response = mapper.toResponse(new HomePage("es-mx", List.of(first, second, third)));

        assertThat(response.blocks()).extracting(HomeBlockResponse::blockId)
                .containsExactly("first", "second", "third");
    }
}
