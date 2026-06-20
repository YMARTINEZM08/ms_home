package com.liverpool.ms_home.domain.model.home;

/**
 * A single block in the composed Home page, in Contentstack order.
 *
 * <p>Sealed so the rendering/mapping logic can pattern-match exhaustively over the only two shapes a
 * block can take: a fully-resolved {@link StaticBlock} or a {@link DynamicPlaceholder} pointing at a
 * dedicated resolution endpoint.</p>
 */
public sealed interface HomeBlock permits StaticBlock, DynamicPlaceholder {

    /** @return the Contentstack block uid, unique within the page */
    String blockId();

    /** @return the classified block type */
    BlockType blockType();
}
