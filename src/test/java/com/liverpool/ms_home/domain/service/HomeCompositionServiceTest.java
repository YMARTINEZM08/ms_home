package com.liverpool.ms_home.domain.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.ms_home.domain.model.content.BlockDefinition;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;
import com.liverpool.ms_home.domain.model.home.BlockKind;
import com.liverpool.ms_home.domain.model.home.BlockResolution;
import com.liverpool.ms_home.domain.model.home.BlockResolutionCatalog;
import com.liverpool.ms_home.domain.model.home.BlockType;
import com.liverpool.ms_home.domain.model.home.DynamicBlockStatus;
import com.liverpool.ms_home.domain.model.home.DynamicPlaceholder;
import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.model.home.StaticBlock;
import com.liverpool.ms_home.domain.port.outbound.FeatureFlagPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeCompositionServiceTest {

    @Mock
    private FeatureFlagPort featureFlagPort;

    private HomeCompositionService service;

    private static final String FLAG_PRODUCTS_LIST = "products-list-salesforce";
    private static final String ENDPOINT = "/home/blocks/products-list";
    private static final SessionContext GUEST_WEB = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_WEB  = new SessionContext(true,  "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_APPS = new SessionContext(true,  "LP", "APPS", "es-mx");

    @BeforeEach
    void setUp() {
        BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
                BlockType.PRODUCTS_LIST, new BlockResolution(ENDPOINT, FLAG_PRODUCTS_LIST)));
        service = new HomeCompositionService(featureFlagPort, catalog);
    }

    // ── static blocks ────────────────────────────────────────────────────────────────────────────

    @Test
    void compose_staticBannerBlock_returnedAsStaticBlock() {
        HomeDefinition def = defWithBlocks(banner("uid-1", "all", true, true));

        HomePage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
        assertThat(page.blocks().get(0)).isInstanceOf(StaticBlock.class);
        StaticBlock block = (StaticBlock) page.blocks().get(0);
        assertThat(block.blockId()).isEqualTo("uid-1");
        assertThat(block.blockType()).isEqualTo(BlockType.BANNER);
        assertThat(block.blockType().kind()).isEqualTo(BlockKind.STATIC);
    }

    @Test
    void compose_unknownContentTypeUid_treatedAsStaticPassthrough() {
        HomeDefinition def = defWithBlocks(unknown("uid-x", "all"));

        HomePage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
        assertThat(page.blocks().get(0)).isInstanceOf(StaticBlock.class);
        assertThat(((StaticBlock) page.blocks().get(0)).blockType()).isEqualTo(BlockType.UNKNOWN);
    }

    // ── dynamic blocks ───────────────────────────────────────────────────────────────────────────

    @Test
    void compose_dynamicBlockWithFlagEnabled_returnsAvailablePlaceholder() {
        when(featureFlagPort.isEnabled(eq(FLAG_PRODUCTS_LIST))).thenReturn(true);
        HomeDefinition def = defWithBlocks(productsList("pl-1", "all"));

        HomePage page = service.compose(def, AUTH_WEB);

        DynamicPlaceholder ph = assertDynamic(page, 0);
        assertThat(ph.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(ph.resolutionPath()).isEqualTo(ENDPOINT);
        assertThat(ph.featureFlagId()).isEqualTo(FLAG_PRODUCTS_LIST);
        assertThat(ph.blockId()).isEqualTo("pl-1");
    }

    @Test
    void compose_dynamicBlockWithFlagDisabled_returnsDisabledPlaceholder() {
        when(featureFlagPort.isEnabled(eq(FLAG_PRODUCTS_LIST))).thenReturn(false);
        HomeDefinition def = defWithBlocks(productsList("pl-2", "all"));

        HomePage page = service.compose(def, AUTH_WEB);

        assertThat(assertDynamic(page, 0).status()).isEqualTo(DynamicBlockStatus.DISABLED);
    }

    // ── audience filtering ───────────────────────────────────────────────────────────────────────

    @Test
    void compose_loggedOnlyBlock_hiddenForGuestSession() {
        HomeDefinition def = defWithBlocks(banner("uid-auth", "logged", true, true));

        HomePage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_loggedOnlyBlock_visibleForAuthenticatedSession() {
        HomeDefinition def = defWithBlocks(banner("uid-auth", "logged", true, true));

        HomePage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).hasSize(1);
    }

    @Test
    void compose_guestOnlyBlock_hiddenForAuthenticatedSession() {
        HomeDefinition def = defWithBlocks(banner("uid-guest", "guest", true, true));

        HomePage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_guestOnlyBlock_visibleForGuestSession() {
        HomeDefinition def = defWithBlocks(banner("uid-guest", "guest", true, true));

        HomePage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
    }

    @Test
    void compose_allAudienceBlock_visibleForBothSessions() {
        HomeDefinition def = defWithBlocks(banner("uid-all", "all", true, true));

        assertThat(service.compose(def, GUEST_WEB).blocks()).hasSize(1);
        assertThat(service.compose(def, AUTH_WEB).blocks()).hasSize(1);
    }

    // ── channel visibility ───────────────────────────────────────────────────────────────────────

    @Test
    void compose_webDisabledBlock_hiddenForWebChannel() {
        HomeDefinition def = defWithBlocks(banner("uid-noweb", "all", false, true));

        HomePage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_appsDisabledBlock_hiddenForAppsChannel() {
        HomeDefinition def = defWithBlocks(banner("uid-noapps", "all", true, false));

        HomePage page = service.compose(def, AUTH_APPS);

        assertThat(page.blocks()).isEmpty();
    }

    // ── ordering ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void compose_preservesBlockOrder() {
        HomeDefinition def = defWithBlocks(
                banner("first", "all", true, true),
                banner("second", "all", true, true),
                banner("third", "all", true, true));

        HomePage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).extracting(b -> ((StaticBlock) b).blockId())
                .containsExactly("first", "second", "third");
    }

    @Test
    void compose_hiddenBlockDoesNotShiftRemainingOrder() {
        HomeDefinition def = defWithBlocks(
                banner("visible-1", "all", true, true),
                banner("guest-only", "guest", true, true),   // hidden for auth
                banner("visible-2", "all", true, true));

        HomePage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).extracting(b -> ((StaticBlock) b).blockId())
                .containsExactly("visible-1", "visible-2");
    }

    @Test
    void compose_setsLocaleFromDefinition() {
        HomeDefinition def = new HomeDefinition("page", "pt-br", List.of());

        HomePage page = service.compose(def, GUEST_WEB);

        assertThat(page.locale()).isEqualTo("pt-br");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static HomeDefinition defWithBlocks(BlockDefinition... blocks) {
        return new HomeDefinition("page", "es-mx", List.of(blocks));
    }

    private static BlockDefinition banner(String uid, String audience, boolean web, boolean apps) {
        return new BlockDefinition(uid, "banner", audience, web, apps, Map.of());
    }

    private static BlockDefinition productsList(String uid, String audience) {
        return new BlockDefinition(uid, "products_list", audience, true, true, Map.of());
    }

    private static BlockDefinition unknown(String uid, String audience) {
        return new BlockDefinition(uid, "unrecognised_type", audience, true, true, Map.of());
    }

    private DynamicPlaceholder assertDynamic(HomePage page, int index) {
        assertThat(page.blocks().get(index)).isInstanceOf(DynamicPlaceholder.class);
        return (DynamicPlaceholder) page.blocks().get(index);
    }
}
