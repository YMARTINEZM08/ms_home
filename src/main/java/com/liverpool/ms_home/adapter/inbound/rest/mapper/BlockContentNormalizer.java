package com.liverpool.ms_home.adapter.inbound.rest.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.liverpool.ms_home.domain.model.home.BlockType;

/**
 * Normalises raw Contentstack block attributes into a clean, client-facing content payload.
 *
 * <p>Applied per block in {@link HomePageMapper} before the content map is written to the
 * REST response. Responsibilities:
 * <ul>
 *   <li>Strip universal CMS system fields ({@code _version}, {@code ACL}, {@code created_by}, …)
 *       that are meaningless to API clients.</li>
 *   <li>Strip internal routing fields already lifted onto {@code BlockDefinition}
 *       ({@code audience_filter}, {@code enable_on_web}, {@code enable_on_apps}).</li>
 *   <li>Apply per-type field normalisation: rename snake_case CMS names to camelCase,
 *       extract asset URLs from Contentstack asset objects, rename known array keys.</li>
 * </ul>
 *
 * <p>Field names are derived from the gap-analysis.md §1.2 BFF response comparison and
 * standard Contentstack conventions. Names marked <em>assumed</em> in the method comments
 * should be validated against the live CMS schema (Phase 13 follow-up).
 *
 * <p>Unknown block types ({@link BlockType#UNKNOWN}) pass through with only metadata stripped
 * so content is never silently dropped (Rule 18).
 */
@Component
public class BlockContentNormalizer {

    /** Universal CMS system fields removed from every block payload. */
    private static final Set<String> CMS_SYSTEM_KEYS = Set.of(
            "_version", "ACL", "_in_progress",
            "created_at", "updated_at", "created_by", "updated_by", "publish_details",
            // routing flags already on BlockDefinition — excluded from the content contract:
            "audience_filter", "enable_on_web", "enable_on_apps"
    );

    /**
     * Normalises raw CMS attributes for the given block type.
     *
     * @param type       the resolved block type ({@code uid} and {@code _content_type_uid} already removed upstream)
     * @param attributes raw CMS attributes from {@code BlockDefinition.attributes()}
     * @return clean content map ready for JSON serialisation to the API client
     */
    public Map<String, Object> normalize(BlockType type, Map<String, Object> attributes) {
        Map<String, Object> stripped = strip(attributes);
        return switch (type) {
            case HERO_BANNER_SLIDER     -> normalizeHeroBannerSlider(stripped);
            case CONTAINER              -> normalizeContainer(stripped);
            case CONTAINER_GUEST        -> normalizeContainerGuest(stripped);
            case BAND                   -> normalizeBand(stripped);
            case CARD_SLIDER            -> normalizeCardSlider(stripped);
            case USER_GENERATED_CONTENT -> normalizeUserGeneratedContent(stripped);
            default                     -> stripped; // BANNER, UNKNOWN — metadata-stripped passthrough
        };
    }

    // ── per-type normalisation ────────────────────────────────────────────────────────────────────

    /**
     * hero_banner_slider: full-width image/video carousel.
     * CMS field {@code banners[]} → output {@code banners[]}, each item expanded from Contentstack
     * asset objects to flat URL strings.
     * Field names: {@code banners}, {@code image}, {@code mobile_image}, {@code cta_button} — confirmed
     * from gap analysis. {@code type} values (e.g. "full-width") assumed from BFF comparison.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeHeroBannerSlider(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", raw.get("title"));
        result.put("banners", mapList(raw, "banners",
                item -> normalizeBannerItem((Map<String, Object>) item)));
        return result;
    }

    private Map<String, Object> normalizeBannerItem(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", raw.get("uid"));
        result.put("type", raw.get("type"));
        result.put("title", raw.get("title"));
        result.put("imageUrl", extractAssetUrl(raw, "image"));
        result.put("mobileImageUrl", extractAssetUrl(raw, "mobile_image"));
        Object button = raw.get("cta_button");
        if (button != null) {
            result.put("button", button);
        }
        Object video = raw.get("video");
        if (video != null) {
            result.put("video", video);
        }
        return result;
    }

    /**
     * container: responsive grid wrapping child blocks.
     * CMS field {@code blocks[]} renamed to {@code children[]}; column counts restructured under
     * {@code columns{desktop,mobile,tablet}}.
     * Field names {@code desktop_columns}, {@code mobile_columns}, {@code tablet_columns} assumed
     * from Contentstack convention and gap analysis target shape — validate with CMS team (Phase 13).
     * Child entries are metadata-stripped but not deeply typed; per-child-type mapping is a
     * follow-on once child {@code _content_type_uid} schemas are confirmed.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeContainer(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", raw.get("type"));
        result.put("title", raw.get("title"));
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("desktop", raw.get("desktop_columns"));
        columns.put("mobile", raw.get("mobile_columns"));
        columns.put("tablet", raw.get("tablet_columns"));
        result.put("columns", columns);
        result.put("children", mapList(raw, "blocks",
                item -> strip((Map<String, Object>) item)));
        return result;
    }

    /**
     * container_guest: guest-only promotional widget.
     * CMS {@code image} asset → {@code imageUrl} string; {@code button_label} → {@code buttonLabel}.
     * Field name {@code button_label} assumed from Contentstack convention — validate with CMS team.
     */
    private Map<String, Object> normalizeContainerGuest(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", raw.get("title"));
        result.put("description", raw.get("description"));
        result.put("imageUrl", extractAssetUrl(raw, "image"));
        result.put("buttonLabel", raw.get("button_label"));
        return result;
    }

    /**
     * band: horizontal category-strip with icon/label items.
     * CMS field {@code content_list[]} renamed to {@code items[]}, each item's {@code image} asset
     * extracted to {@code imageUrl}.
     * Field name {@code content_list} confirmed from gap analysis §2.1.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeBand(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", raw.get("title"));
        result.put("items", mapList(raw, "content_list",
                item -> normalizeBandItem((Map<String, Object>) item)));
        return result;
    }

    private Map<String, Object> normalizeBandItem(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageUrl", extractAssetUrl(raw, "image"));
        result.put("label", raw.get("label"));
        result.put("url", raw.get("url"));
        return result;
    }

    /**
     * card_slider: static card carousel.
     * CMS field {@code cards[]} preserved; each card's {@code image} asset extracted to
     * {@code imageUrl}, {@code cta_button} renamed to {@code button}.
     * Field name {@code cards} assumed from Contentstack convention — validate with CMS team.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeCardSlider(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", raw.get("title"));
        result.put("cards", mapList(raw, "cards",
                item -> normalizeCard((Map<String, Object>) item)));
        return result;
    }

    private Map<String, Object> normalizeCard(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", raw.get("title"));
        result.put("imageUrl", extractAssetUrl(raw, "image"));
        result.put("url", raw.get("url"));
        Object button = raw.get("cta_button");
        if (button != null) {
            result.put("button", button);
        }
        return result;
    }

    /**
     * user_generated_content: social media / hashtag embed section.
     * Field names {@code hashtag}, {@code cta} assumed from Contentstack convention.
     */
    private Map<String, Object> normalizeUserGeneratedContent(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", raw.get("title"));
        result.put("hashtag", raw.get("hashtag"));
        result.put("description", raw.get("description"));
        Object cta = raw.get("cta");
        if (cta != null) {
            result.put("cta", cta);
        }
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Returns a new mutable map with all CMS system keys removed. */
    private Map<String, Object> strip(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>(raw);
        CMS_SYSTEM_KEYS.forEach(result::remove);
        return result;
    }

    /**
     * Iterates a list under {@code key}, applies {@code mapper} to each
     * {@code Map} entry, and returns the results. Returns an empty list when the key is absent
     * or its value is not a list.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Map<String, Object> raw, String key,
                                              Function<Object, Map<String, Object>> mapper) {
        Object value = raw.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                result.add(mapper.apply(item));
            }
        }
        return result;
    }

    /**
     * Extracts the {@code url} string from a Contentstack asset object stored under {@code key}.
     * Returns {@code null} when the key is absent, the value is not a map, or the URL is not a string.
     */
    @SuppressWarnings("unchecked")
    private String extractAssetUrl(Map<String, Object> raw, String key) {
        Object asset = raw.get(key);
        if (asset instanceof Map<?, ?> m) {
            Object url = ((Map<String, Object>) m).get("url");
            return url instanceof String s ? s : null;
        }
        return null;
    }
}
