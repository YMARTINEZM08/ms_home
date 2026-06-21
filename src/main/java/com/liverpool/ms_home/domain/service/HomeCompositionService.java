package com.liverpool.ms_home.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.liverpool.ms_home.domain.model.content.BlockDefinition;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;
import com.liverpool.ms_home.domain.model.home.AudienceFilter;
import com.liverpool.ms_home.domain.model.home.BlockKind;
import com.liverpool.ms_home.domain.model.home.BlockResolution;
import com.liverpool.ms_home.domain.model.home.BlockResolutionCatalog;
import com.liverpool.ms_home.domain.model.home.BlockType;
import com.liverpool.ms_home.domain.model.home.DynamicBlockStatus;
import com.liverpool.ms_home.domain.model.home.DynamicPlaceholder;
import com.liverpool.ms_home.domain.model.home.HomeBlock;
import com.liverpool.ms_home.domain.model.home.HomePage;
import com.liverpool.ms_home.domain.model.home.SessionContext;
import com.liverpool.ms_home.domain.model.home.StaticBlock;
import com.liverpool.ms_home.domain.port.outbound.FeatureFlagPort;

/**
 * Pure domain logic that composes a {@link HomePage} from a raw {@link HomeDefinition} (Rule 18).
 *
 * <p>Responsibilities, and nothing more: preserve Contentstack order, apply audience/channel
 * visibility, classify each block as static or dynamic, resolve static content inline, and emit a
 * placeholder for each dynamic block with its endpoint, fallback, feature-flag id and status. It never
 * calls a data source, personalizes, or reorders. No Spring, no I/O — fully unit-testable (Rule 1).</p>
 */
public class HomeCompositionService {

    private static final String CHANNEL_WEB = "WEB";
    private static final String ATTR_FALLBACK = "fallback";

    private final FeatureFlagPort featureFlags;
    private final BlockResolutionCatalog resolutionCatalog;

    /**
     * @param featureFlags      runtime flag evaluation for dynamic blocks
     * @param resolutionCatalog endpoint + flag metadata per dynamic block type
     */
    public HomeCompositionService(FeatureFlagPort featureFlags, BlockResolutionCatalog resolutionCatalog) {
        this.featureFlags = featureFlags;
        this.resolutionCatalog = resolutionCatalog;
    }

    /**
     * Composes the page for a session, keeping only blocks visible to that session/channel.
     *
     * @param definition raw, ordered Home definition from the CMS
     * @param session    resolved session context
     * @return the composed, ordered Home page
     */
    public HomePage compose(HomeDefinition definition, SessionContext session) {
        List<HomeBlock> blocks = new ArrayList<>();
        for (BlockDefinition raw : definition.blocks()) {
            if (!isVisible(raw, session)) {
                continue;
            }
            blocks.add(toBlock(raw, session));
        }
        return new HomePage(definition.locale(), definition.pageTitle(), definition.seo(), blocks);
    }

    /**
     * A block is visible when its audience matches the session and it is enabled for the channel.
     */
    private boolean isVisible(BlockDefinition raw, SessionContext session) {
        AudienceFilter audience = AudienceFilter.from(raw.audienceFilter());
        if (!audience.visibleFor(session.authenticated())) {
            return false;
        }
        return CHANNEL_WEB.equalsIgnoreCase(session.channel()) ? raw.enabledOnWeb() : raw.enabledOnApps();
    }

    private HomeBlock toBlock(BlockDefinition raw, SessionContext session) {
        BlockType type = BlockType.fromContentTypeUid(raw.contentTypeUid());
        // A dynamic type without resolution config is treated as static pass-through so content is
        // never dropped; ordering and presence are always preserved.
        if (type.kind() == BlockKind.DYNAMIC) {
            Optional<BlockResolution> resolution = resolutionCatalog.forType(type);
            if (resolution.isPresent()) {
                return toPlaceholder(raw, type, resolution.get());
            }
        }
        return new StaticBlock(raw.uid(), type, raw.attributes());
    }

    private DynamicPlaceholder toPlaceholder(BlockDefinition raw, BlockType type, BlockResolution resolution) {
        boolean enabled = featureFlags.isEnabled(resolution.featureFlagId());
        DynamicBlockStatus status = enabled ? DynamicBlockStatus.AVAILABLE : DynamicBlockStatus.DISABLED;
        Object fallback = raw.attributes().get(ATTR_FALLBACK);
        return new DynamicPlaceholder(
                raw.uid(), type, resolution.resolutionPath(), fallback, resolution.featureFlagId(), status);
    }
}
