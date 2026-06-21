package com.liverpool.ms_home.adapter.inbound.rest.mapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_home.domain.model.home.BlockType;

import static org.assertj.core.api.Assertions.assertThat;

class BlockContentNormalizerTest {

    private BlockContentNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new BlockContentNormalizer();
    }

    // ── universal metadata stripping ──────────────────────────────────────────────────────────────

    @Test
    void normalize_stripsAllUniversalCmsMetadataFields() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "My Block");
        attributes.put("_version", 3);
        attributes.put("ACL", Map.of());
        attributes.put("_in_progress", false);
        attributes.put("created_by", "user-1");
        attributes.put("updated_by", "user-2");
        attributes.put("created_at", "2024-01-01T00:00:00.000Z");
        attributes.put("updated_at", "2024-01-01T00:00:00.000Z");
        attributes.put("publish_details", Map.of("locale", "es-mx"));
        attributes.put("audience_filter", "all");
        attributes.put("enable_on_web", true);
        attributes.put("enable_on_apps", true);

        Map<String, Object> result = normalizer.normalize(BlockType.UNKNOWN, attributes);

        assertThat(result).containsKey("title");
        assertThat(result).doesNotContainKeys(
                "_version", "ACL", "_in_progress",
                "created_by", "updated_by", "created_at", "updated_at", "publish_details",
                "audience_filter", "enable_on_web", "enable_on_apps");
    }

    @Test
    void normalize_unknownType_passesRemainingContentThroughAfterStrip() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("custom_field", "custom_value");
        attributes.put("nested", Map.of("a", 1));
        attributes.put("_version", 1);

        Map<String, Object> result = normalizer.normalize(BlockType.UNKNOWN, attributes);

        assertThat(result.get("custom_field")).isEqualTo("custom_value");
        assertThat(result.get("nested")).isEqualTo(Map.of("a", 1));
        assertThat(result).doesNotContainKey("_version");
    }

    // ── hero_banner_slider ────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void normalize_heroBannerSlider_extractsBannersWithAssetUrlsAndFieldRenaming() {
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("uid", "banner-1");
        banner.put("type", "full-width");
        banner.put("title", "Big Sale");
        banner.put("image", Map.of("url", "https://cdn.example.com/img.jpg", "title", "img"));
        banner.put("mobile_image", Map.of("url", "https://cdn.example.com/mobile.jpg"));
        banner.put("cta_button", Map.of("label", "Shop now", "url", "/sale"));
        banner.put("_version", 2);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "Hero Slider");
        attributes.put("banners", List.of(banner));
        attributes.put("_version", 5);

        Map<String, Object> result = normalizer.normalize(BlockType.HERO_BANNER_SLIDER, attributes);

        assertThat(result.get("title")).isEqualTo("Hero Slider");
        assertThat(result).doesNotContainKey("_version");

        List<Map<String, Object>> banners = (List<Map<String, Object>>) result.get("banners");
        assertThat(banners).hasSize(1);
        Map<String, Object> b = banners.get(0);
        assertThat(b.get("uid")).isEqualTo("banner-1");
        assertThat(b.get("type")).isEqualTo("full-width");
        assertThat(b.get("title")).isEqualTo("Big Sale");
        assertThat(b.get("imageUrl")).isEqualTo("https://cdn.example.com/img.jpg");
        assertThat(b.get("mobileImageUrl")).isEqualTo("https://cdn.example.com/mobile.jpg");
        assertThat(b.get("button")).isEqualTo(Map.of("label", "Shop now", "url", "/sale"));
        assertThat(b).doesNotContainKeys("image", "mobile_image", "cta_button", "_version");
    }

    @Test
    void normalize_heroBannerSlider_optionalVideoIncludedWhenPresent() {
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("uid", "b1");
        banner.put("video", Map.of("url", "https://cdn.example.com/vid.mp4"));

        Map<String, Object> attributes = Map.of("title", "Slider", "banners", List.of(banner));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> banners =
                (List<Map<String, Object>>) normalizer.normalize(BlockType.HERO_BANNER_SLIDER, attributes).get("banners");

        assertThat(banners.get(0).get("video")).isNotNull();
    }

    @Test
    void normalize_heroBannerSlider_absentBannersList_returnsEmptyList() {
        Map<String, Object> result = normalizer.normalize(BlockType.HERO_BANNER_SLIDER,
                Map.of("title", "Slider"));

        assertThat(result.get("title")).isEqualTo("Slider");
        assertThat((List<?>) result.get("banners")).isEmpty();
    }

    // ── container ────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void normalize_container_renamesBlocksToChildrenAndBuildsColumnsObject() {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("uid", "card-1");
        child.put("_content_type_uid", "card");
        child.put("title", "Card Title");
        child.put("_version", 1);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("type", "default");
        attributes.put("title", "Card Grid");
        attributes.put("desktop_columns", 4);
        attributes.put("mobile_columns", 1);
        attributes.put("tablet_columns", 2);
        attributes.put("blocks", List.of(child));

        Map<String, Object> result = normalizer.normalize(BlockType.CONTAINER, attributes);

        assertThat(result.get("type")).isEqualTo("default");
        assertThat(result.get("title")).isEqualTo("Card Grid");
        assertThat(result).doesNotContainKey("blocks");

        Map<String, Object> columns = (Map<String, Object>) result.get("columns");
        assertThat(columns.get("desktop")).isEqualTo(4);
        assertThat(columns.get("mobile")).isEqualTo(1);
        assertThat(columns.get("tablet")).isEqualTo(2);

        List<Map<String, Object>> children = (List<Map<String, Object>>) result.get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("title")).isEqualTo("Card Title");
        assertThat(children.get(0)).doesNotContainKey("_version");
    }

    @Test
    void normalize_container_absentBlocksList_returnsEmptyChildren() {
        Map<String, Object> result = normalizer.normalize(BlockType.CONTAINER,
                Map.of("type", "default", "title", "Empty Grid"));

        assertThat((List<?>) result.get("children")).isEmpty();
    }

    // ── container_guest ──────────────────────────────────────────────────────────────────────────

    @Test
    void normalize_containerGuest_extractsImageUrlAndRenamesButtonLabel() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "Join Now");
        attributes.put("description", "Sign up to unlock exclusive deals.");
        attributes.put("image", Map.of("url", "https://cdn.example.com/banner.jpg", "title", "promo"));
        attributes.put("button_label", "Register");
        attributes.put("_version", 2);

        Map<String, Object> result = normalizer.normalize(BlockType.CONTAINER_GUEST, attributes);

        assertThat(result.get("title")).isEqualTo("Join Now");
        assertThat(result.get("description")).isEqualTo("Sign up to unlock exclusive deals.");
        assertThat(result.get("imageUrl")).isEqualTo("https://cdn.example.com/banner.jpg");
        assertThat(result.get("buttonLabel")).isEqualTo("Register");
        assertThat(result).doesNotContainKeys("image", "button_label", "_version");
    }

    @Test
    void normalize_containerGuest_absentImage_imageUrlIsNull() {
        Map<String, Object> result = normalizer.normalize(BlockType.CONTAINER_GUEST,
                Map.of("title", "Join", "description", "desc"));

        assertThat(result.get("imageUrl")).isNull();
    }

    // ── band ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void normalize_band_renamesContentListToItemsAndExtractsImageUrl() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("image", Map.of("url", "https://cdn.example.com/cat.jpg"));
        item.put("label", "Zapatos");
        item.put("url", "/zapatos");

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "Categorías");
        attributes.put("content_list", List.of(item));
        attributes.put("_version", 3);

        Map<String, Object> result = normalizer.normalize(BlockType.BAND, attributes);

        assertThat(result.get("title")).isEqualTo("Categorías");
        assertThat(result).doesNotContainKeys("content_list", "_version");

        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("imageUrl")).isEqualTo("https://cdn.example.com/cat.jpg");
        assertThat(items.get(0).get("label")).isEqualTo("Zapatos");
        assertThat(items.get(0).get("url")).isEqualTo("/zapatos");
        assertThat(items.get(0)).doesNotContainKey("image");
    }

    @Test
    void normalize_band_absentContentList_returnsEmptyItems() {
        Map<String, Object> result = normalizer.normalize(BlockType.BAND, Map.of("title", "Cats"));

        assertThat((List<?>) result.get("items")).isEmpty();
    }

    // ── card_slider ───────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void normalize_cardSlider_extractsCardsWithImageUrlAndButton() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", "Liverpool Card");
        card.put("image", Map.of("url", "https://cdn.example.com/card.jpg"));
        card.put("url", "/tarjeta");
        card.put("cta_button", Map.of("label", "Ver más", "url", "/tarjeta/details"));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "Nuestras Tarjetas");
        attributes.put("cards", List.of(card));

        Map<String, Object> result = normalizer.normalize(BlockType.CARD_SLIDER, attributes);

        assertThat(result.get("title")).isEqualTo("Nuestras Tarjetas");

        List<Map<String, Object>> cards = (List<Map<String, Object>>) result.get("cards");
        assertThat(cards).hasSize(1);
        Map<String, Object> c = cards.get(0);
        assertThat(c.get("title")).isEqualTo("Liverpool Card");
        assertThat(c.get("imageUrl")).isEqualTo("https://cdn.example.com/card.jpg");
        assertThat(c.get("url")).isEqualTo("/tarjeta");
        assertThat(c.get("button")).isEqualTo(Map.of("label", "Ver más", "url", "/tarjeta/details"));
        assertThat(c).doesNotContainKeys("image", "cta_button");
    }

    @Test
    void normalize_cardSlider_absentCardButton_omittedFromResult() {
        Map<String, Object> card = Map.of("title", "Card", "url", "/card");
        Map<String, Object> attributes = Map.of("title", "Slider", "cards", List.of(card));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards =
                (List<Map<String, Object>>) normalizer.normalize(BlockType.CARD_SLIDER, attributes).get("cards");

        assertThat(cards.get(0)).doesNotContainKey("button");
    }

    // ── user_generated_content ────────────────────────────────────────────────────────────────────

    @Test
    void normalize_userGeneratedContent_extractsAllKnownFields() {
        Map<String, Object> cta = Map.of("label", "Ver feed", "url", "/instagram");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "Comparte con nosotros");
        attributes.put("hashtag", "#MiLiverpool");
        attributes.put("description", "Etiqueta tus fotos.");
        attributes.put("cta", cta);
        attributes.put("_version", 1);

        Map<String, Object> result = normalizer.normalize(BlockType.USER_GENERATED_CONTENT, attributes);

        assertThat(result.get("title")).isEqualTo("Comparte con nosotros");
        assertThat(result.get("hashtag")).isEqualTo("#MiLiverpool");
        assertThat(result.get("description")).isEqualTo("Etiqueta tus fotos.");
        assertThat(result.get("cta")).isEqualTo(cta);
        assertThat(result).doesNotContainKey("_version");
    }

    @Test
    void normalize_userGeneratedContent_absentCta_omittedFromResult() {
        Map<String, Object> result = normalizer.normalize(BlockType.USER_GENERATED_CONTENT,
                Map.of("title", "UGC", "hashtag", "#test", "description", "desc"));

        assertThat(result).doesNotContainKey("cta");
    }
}
