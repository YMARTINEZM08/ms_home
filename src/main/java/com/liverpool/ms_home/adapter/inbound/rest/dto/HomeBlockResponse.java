package com.liverpool.ms_home.adapter.inbound.rest.dto;

import java.util.Map;

/**
 * Unified block representation in the {@code GET /home} response.
 *
 * <p>A single flat record is used instead of a polymorphic hierarchy to keep JSON serialization
 * unambiguous across any HTTP client. The {@code kind} field acts as a discriminator:
 * <ul>
 *   <li>{@code "STATIC"} — {@code content} is populated; resolution fields are null.</li>
 *   <li>{@code "DYNAMIC"} — {@code resolutionPath}, {@code fallback}, {@code featureFlagId} and
 *       {@code status} are populated; {@code content} is null.</li>
 * </ul>
 *
 * <p>Three distinct {@code status} values for dynamic blocks (Rule 18 / locked decision 5):
 * {@code AVAILABLE} · {@code DISABLED} · {@code UNAVAILABLE} — the frontend must handle each
 * differently.</p>
 *
 * @param blockId        Contentstack block uid, unique within the page
 * @param blockType      classified block type (e.g. {@code BANNER}, {@code PRODUCTS_LIST})
 * @param kind           discriminator: {@code STATIC} or {@code DYNAMIC}
 * @param content        resolved content payload (STATIC only)
 * @param resolutionPath dedicated endpoint the frontend calls to resolve the block (DYNAMIC only)
 * @param fallback       content to render when the block is unavailable (DYNAMIC only)
 * @param featureFlagId  runtime feature flag controlling this block (DYNAMIC only)
 * @param status         dynamic-block status: {@code AVAILABLE} | {@code DISABLED} | {@code UNAVAILABLE}
 */
public record HomeBlockResponse(
        String blockId,
        String blockType,
        String kind,
        Map<String, Object> content,
        String resolutionPath,
        Object fallback,
        String featureFlagId,
        String status) {
}
