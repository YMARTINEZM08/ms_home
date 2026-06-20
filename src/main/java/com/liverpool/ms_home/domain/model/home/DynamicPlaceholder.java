package com.liverpool.ms_home.domain.model.home;

/**
 * A placeholder for a session/runtime-dependent block (Rule 18). The Home endpoint never resolves the
 * dynamic detail itself; instead it advertises the dedicated endpoint the frontend should call.
 *
 * @param blockId        Contentstack block uid
 * @param blockType      classified type (e.g. {@link BlockType#PRODUCTS_LIST})
 * @param resolutionPath path of the dedicated per-block endpoint that resolves the detail
 * @param fallback       fallback content to render when the block is unavailable
 * @param featureFlagId  id of the flag controlling this block's runtime enablement
 * @param status         why the block is/ isn't resolvable (see {@link DynamicBlockStatus})
 */
public record DynamicPlaceholder(
        String blockId,
        BlockType blockType,
        String resolutionPath,
        Object fallback,
        String featureFlagId,
        DynamicBlockStatus status) implements HomeBlock {

    /**
     * Returns a copy of this placeholder with a different status, keeping all other fields.
     *
     * @param newStatus the status to set
     * @return a new placeholder instance
     */
    public DynamicPlaceholder withStatus(DynamicBlockStatus newStatus) {
        return new DynamicPlaceholder(blockId, blockType, resolutionPath, fallback, featureFlagId, newStatus);
    }
}
