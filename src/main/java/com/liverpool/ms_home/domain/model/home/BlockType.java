package com.liverpool.ms_home.domain.model.home;

import java.util.Arrays;

/**
 * Known Home block types and their classification, keyed by the Contentstack {@code _content_type_uid}.
 *
 * <p>Unknown types map to {@link #UNKNOWN} and are treated as static pass-through so unrecognised
 * content is never dropped — ordering and presence are always preserved (Rule 18). Adding a block
 * type here, plus its resolution config, is the only change needed for a new block (Open/Closed).</p>
 */
public enum BlockType {

    /** Static promotional banner; fully resolved from Contentstack and cacheable. */
    BANNER("banner", BlockKind.STATIC),

    /** Personalized product carousel; session-dependent, resolved by a dedicated endpoint. */
    PRODUCTS_LIST("products_list", BlockKind.DYNAMIC),

    /** Any content type not explicitly modelled; passed through statically to preserve the page. */
    UNKNOWN("", BlockKind.STATIC);

    private final String contentTypeUid;
    private final BlockKind kind;

    BlockType(String contentTypeUid, BlockKind kind) {
        this.contentTypeUid = contentTypeUid;
        this.kind = kind;
    }

    public String contentTypeUid() {
        return contentTypeUid;
    }

    public BlockKind kind() {
        return kind;
    }

    /**
     * Resolves a block type from its Contentstack content-type uid.
     *
     * @param contentTypeUid the {@code _content_type_uid} from Contentstack (may be null/blank)
     * @return the matching type, or {@link #UNKNOWN} when unrecognised
     */
    public static BlockType fromContentTypeUid(String contentTypeUid) {
        return Arrays.stream(values())
                .filter(type -> type != UNKNOWN && type.contentTypeUid.equals(contentTypeUid))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
