package com.liverpool.ms_home.domain.error;

/**
 * Thrown by a dynamic block's resolution endpoint when its backing service is unhealthy. Distinct
 * from a runtime-disabled block and from a tripped circuit breaker — carries the block id/type so the
 * frontend can mark exactly that block unavailable while rendering the rest of the page.
 */
public final class DynamicBlockServiceUnavailableException extends HomeException {

    private final String blockId;
    private final String blockType;

    /**
     * @param blockId   the block being resolved
     * @param blockType the block's type
     * @param detail    technical detail (downstream endpoint, status, cause)
     * @param cause     underlying error (may be null)
     */
    public DynamicBlockServiceUnavailableException(String blockId, String blockType, String detail,
                                                   Throwable cause) {
        super(ErrorCodes.BLOCK_SERVICE_UNAVAILABLE, ErrorCategory.EXTERNAL_SERVICE,
                "The requested block could not be resolved at this moment.", detail, cause);
        this.blockId = blockId;
        this.blockType = blockType;
    }

    public String getBlockId() {
        return blockId;
    }

    public String getBlockType() {
        return blockType;
    }
}
