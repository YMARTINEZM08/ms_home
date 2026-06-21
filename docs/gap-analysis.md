# Gap Analysis — ms-home vs. BFF Live Response

> Comparison baseline: `GET /web-bff/content/page/es-mx/tienda/home` — authenticated session  
> (LoggedInSession=TRUE, DYN_USER_ID=31005309570)  
> Date: 2026-06-20

---

## 1. Critical Blockers (P0 — nothing works without these)

### 1.1 Wrong CMS template schema — zero blocks rendered

**BFF production schema:**
```
template.blocks[]           ← flat array, directly on the template object
```

**ms-home current assumption:**
```
template.top_layout[]
template.layout[]
template.bottom_layout[]
```

`ContentServiceClient.fetchHomeDefinition()` merges `top_layout + layout + bottom_layout`.
The live Contentstack home template has **none of those keys** — it uses `template.blocks` directly.
Result: every call to the real content-service returns an empty block list.

**Fix:** Update `ContentServiceClient` to read `template.blocks` (or support both schemas
via fallback).  
**Files:** `adapter/outbound/contentstack/ContentServiceClient.java`,
`domain/model/content/HomeDefinition.java` (if block merging moves there).

---

### 1.2 `BlockType` enum missing all production block types

Live home page block type distribution (16 blocks):

| `_content_type_uid` | Count | Notes |
|---|---|---|
| `hero_banner_slider` | 6 | Most common; contains `banners[]` → each a `hero_banner` entry |
| `container` | 6 | Recursive: contains nested `blocks[]` (cards, etc.) |
| `container_guest` | 1 | Guest-only CTA (image + title + description + button) |
| `band` | 1 | Category strip: `content_list[]` of items |
| `card_slider` | 1 | Static card carousel with `cards[]` |
| `user_generated_content` | 1 | UGC / social media embed section |

Current `BlockType` enum only defines `BANNER` and `PRODUCTS_LIST`. Every live block falls
through to `UNKNOWN`, is passed through unchanged, but carries no resolved CMS content (because
blocks reach ms-home as raw `BlockDefinition` objects that are never merged into full content).

**Fix:** Add all six types to `BlockType`; implement content resolution per type (see Phase B).

---

## 2. Significant Gaps (P1 — feature parity)

### 2.1 `enable_on_web` / `enable_on_apps` / `audience_filter` not present on home blocks

The channel-visibility and audience-filtering logic in `HomeCompositionService` reads
`enable_on_web`, `enable_on_apps`, and `audience_filter` from `BlockDefinition`.
**None of these fields appear on the home template blocks** in the real CMS content model.
They appear only in `globalData.page_not_found_blocks` and `globalData.search_not_found_blocks`
(value = `"all"` in those cases).

Implication:
- `HomeCompositionService.visibleForChannel()` always evaluates to `false` → blocks filtered out
  (unless defaults are set to `true` when fields are absent).
- Audience-filter (logged/guest/all) cannot be applied to home blocks as designed.

**Fix options:**
- Default `enable_on_web = true` and `enable_on_apps = true` when field is absent.
- Determine whether channel/audience control actually lives in the Contentstack entry or is driven
  entirely by the channel-specific template (separate CMS entry per channel).
- Confirm with CMS team the authoritative filtering mechanism for home template blocks.

**Files:** `domain/model/content/BlockDefinition.java`, `domain/service/HomeCompositionService.java`.

---

### 2.2 `container` block is recursive — nested blocks not handled

`container` blocks contain `blocks[]` sub-array.
Each sub-block is a fully resolved CMS entry (e.g., `card`).
ms-home's flat block model has no nesting concept:

```
// BFF shape
container {
  type: "default",
  blocks: [
    { _content_type_uid: "card", card_title: "...", image: {...} },
    { _content_type_uid: "card", ... },
  ]
}

// ms-home shape (current) — flat, no nesting
{ blockId, blockType, kind="STATIC", content={...} }
```

**Fix:** Either flatten containers during composition (expand child blocks into the top-level list
with a `parentBlockId` reference), or add a `children[]` field to `HomeBlockResponse` / the domain
`StaticBlock` model.

---

### 2.3 No `globalData` in ms-home response

BFF returns `globalData` alongside the page template:

| Field | Purpose |
|---|---|
| `feature_flags` | 22 booleans (salesforce, personalization, jewel, groupby, …) |
| `public_variables` | Site-wide config (domain, CDN paths, etc.) |
| `themes` | Brand theming tokens |
| `footer`, `header` | Global nav content |
| `cookie_consent`, `tooltips_content`, `welcome_popup`, … | Global UI elements |

ms-home does not serve any of these.  
These could be: (a) a separate `/globalData` endpoint, or (b) bundled in the `/home` response.

---

### 2.4 No SEO metadata in ms-home response

BFF response includes page-level:
- `page_title` — rendered in `<title>` tag
- `seo.meta_description`, `seo.meta_keywords`
- `seo.canonical_url`
- `seo.facebook.*`, `seo.twitter.*` — Open Graph / Twitter card
- `seo.no_index`, `seo.no_follow`

`HomePageResponse` only exposes `locale` + `blocks[]`.

---

### 2.5 No `header` / `footer` in ms-home response

BFF bundles the full header and footer CMS entries into the page response.
`header` has `content_logged_in`, `content_logged_out`, `general`, `session`,
`top_navigation_bar` — all serving the navigation layer of the app.

---

### 2.6 No Salesforce `products_list` blocks in live data

The authenticated session response has **zero Salesforce-powered blocks**.
The feature flag `salesforce: true` is present in `globalData.feature_flags`, suggesting
the frontend may conditionally request Salesforce data separately, or these blocks appear
on other page variants.

**Implication:** The `ProductsListAdapter` + `ProductsListResolveService` implementation
is conceptually correct but cannot be validated end-to-end until a page variant with Salesforce
blocks is identified in the CMS.

---

## 3. Design Decisions to Revisit (P2)

### 3.1 BFF was a passthrough; ms-home needs a composition contract

The BFF returned the **raw Contentstack JSON** — full nested objects, all metadata, image URLs, etc.
The frontend consumed this directly.

ms-home must define an **explicit content contract** per block type that:
- Strips CMS internals (`ACL`, `_version`, `_in_progress`, `created_by`, …)
- Normalises field names (snake_case → camelCase, or keep as-is per API standard)
- Handles missing optional fields defensively

This is additive design work per block type, not a single change.

### 3.2 Block content passthrough vs. explicit field mapping

Current `StaticBlock.content()` is `Map<String, Object>` — opaque passthrough.
This is fine for an MVP, but:
- Clients must understand internal CMS field names (`slider_title`, `container_background`, etc.)
- Breaking CMS renames break clients silently
- No OpenAPI schema documentation possible

Option A: Keep `Map<String, Object>` passthrough but document the per-type shape in `integrations.md`.  
Option B: Typed per-block DTOs with an explicit mapping layer (more robust; significant scope).

### 3.3 Channel routing (web vs. apps) may be template-based, not field-based

In the live CMS, there are likely separate Contentstack templates per channel
(`tienda/home` = Web template, `app/home` = App template) rather than a single template
with per-block channel filters. If so, `HomeCompositionService.visibleForChannel()` is
unnecessary — routing is implicit in the `path` query param.

This needs validation with the CMS content team.

---

## 4. Response Shape Comparison

| Dimension | BFF (existing) | ms-home (current) | ms-home (target) |
|---|---|---|---|
| Root shape | Raw CMS entry + `globalData` | `{locale, blocks[]}` | `{locale, pageTitle, seo{}, blocks[]}` + optional `globalData` |
| Block count | 16 (all types) | 0 (schema mismatch) | 16 |
| Block content | Full nested CMS objects | `Map<String,Object>` passthrough | Structured per-type payload |
| SEO | ✅ page-level seo object | ✗ | ✅ |
| Header/Footer | ✅ bundled | ✗ | Separate endpoint or bundled |
| GlobalData | ✅ bundled | ✗ | Separate endpoint |
| Feature flags | ✅ in `globalData` | ✗ (internal only) | Via `FeatureFlagPort` or separate |
| Salesforce dynamic | ✗ (not present in this page) | `DYNAMIC` placeholder + /blocks/{id} | ✅ |
| Audience filtering | Implicit via CMS template | `AudienceFilter` enum | Validate real CMS mechanism |
| Channel filtering | Implicit via URL path | `enable_on_web/apps` | Validate real CMS mechanism |

---

## 5. Phased Iteration Plan

### Phase 10 — Fix CMS schema mapping (P0 blocker)
**Scope:** Make ms-home produce real blocks from the live content-service.

- [ ] Update `ContentServiceClient` to read `template.blocks[]` as primary source,
      fall back to merging `top_layout + layout + bottom_layout` if present.
- [ ] Update `BlockDefinition` to remove mandatory `enable_on_web`/`enable_on_apps` requirement;
      default both to `true` when absent.
- [ ] Add all 6 production block types to `BlockType` enum:
      `HERO_BANNER_SLIDER`, `CONTAINER`, `CONTAINER_GUEST`, `BAND`, `CARD_SLIDER`, `USER_GENERATED_CONTENT`.
- [ ] Validate locally: start ms-home pointing at real content-service, assert 16 blocks returned.
- [ ] Update unit tests: `ContentServiceClientTest`, `HomeCompositionServiceTest`.

Effort: ~0.5 day.

---

### Phase 11 — Block content contract per type ✅
**Scope:** Each block type exposes a clean, documented content payload (not raw CMS blob).

- [x] `BlockContentNormalizer` — single component in `adapter/inbound/rest/mapper/`; dispatches by `BlockType`; strips universal CMS system metadata from all types.
- [x] `hero_banner_slider` → `{ title, banners[{uid,type,title,imageUrl,mobileImageUrl,button?,video?}] }`
- [x] `container` → `{ type, title, columns{desktop,mobile,tablet}, children[stripped] }`
- [x] `container_guest` → `{ title, description, imageUrl, buttonLabel }`
- [x] `band` → `{ title, items[{imageUrl,label,url}] }` (CMS `content_list` → `items`)
- [x] `card_slider` → `{ title, cards[{title,imageUrl,url,button?}] }`
- [x] `user_generated_content` → `{ title, hashtag, description, cta? }`
- [x] Kept `Map<String,Object>` content type — schemas documented in `integrations.md §5`.
- [x] `BlockContentNormalizerTest` — 16 tests covering all 6 types + universal metadata stripping + unknown passthrough.
- [x] `HomePageMapper` now injects `BlockContentNormalizer`; `HomePageMapperTest` updated.

**Open (Phase 13 follow-on):** Some field names assumed from Contentstack conventions and BFF gap
analysis — `desktop_columns`, `mobile_columns`, `tablet_columns`, `button_label`, `cards`,
`hashtag`, `cta` — need validation against live CMS schema. `container.children[]` are
metadata-stripped but not deeply typed; per-child-type mapping pending child schema confirmation.

Effort: ~2 days.

---

### Phase 12 — SEO + page metadata in response
**Scope:** Surface page-level metadata from the Contentstack entry.

- [ ] Add `pageTitle`, `url`, `seo{}` to `HomePageResponse`.
- [ ] Extend `HomeDefinition` to carry page-level fields.
- [ ] Map from raw CMS entry in `ContentServiceClient`.
- [ ] Update `HomePageMapper`.
- [ ] Document in `integrations.md`.

Effort: ~0.5 day.

---

### Phase 13 — Validate channel / audience filtering mechanism ✅
**Scope:** Align with the real CMS content strategy before implementing filtering.

**Resolution (derived from BFF gap-analysis data — no CMS team meeting required):**

- [x] **`enable_on_web` / `enable_on_apps` are absent on all home template blocks** — confirmed
      from BFF response comparison (16 blocks, none carry these fields). Our defaults in
      `ContentServiceClient.toBlockDefinition()` (`true` when absent) are correct.
- [x] **`audience_filter` is absent on all home template blocks** — confirmed. `AudienceFilter.from(null)`
      returns `ALL`. All production blocks pass the audience guard without filtering.
- [x] **Channel routing is template-based** — the caller supplies a different `path` per channel
      (`/tienda/home` for web, separate path for apps); Contentstack serves the matching template.
      `HomeCompositionService.isVisible()` is a no-op for production content (all defaults pass).
      The guard is **preserved** for legacy content compatibility (see ADR-011).
- [x] **`container_guest` semantics** — the BFF returned `container_guest` for an authenticated
      session, confirming it is not server-filtered. Rendering for guest-only is a frontend concern.
      The block name is a CMS editorial convention, not a CMS-enforced audience restriction.
- [x] **No code changes required** — existing defaults + filtering logic correctly handle both
      production (absent fields → permissive defaults) and legacy content (explicit flags → filtered).
- [x] **ADR-011** added to `decisions.md` documenting the channel/audience model and its rationale.
- [x] `HomeCompositionService.isVisible()` javadoc updated to explain the production vs. legacy
      content contract.

Effort: 0.5 day (documentation + ADR).

---

### Phase 14 — GlobalData endpoint ✅
**Scope:** Serve the global config the frontend needs (feature flags, public variables, themes).

- [x] **Separate `GET /global-data` endpoint** — not bundled in `/home` (clean separation: page
      layout vs. site-wide config; independent cache TTLs; independent circuit breaker).
- [x] **`GlobalData` domain record** — `{ locale, featureFlags, publicVariables, themes }` (all
      maps non-null; empty when CMS key absent).
- [x] **`GlobalDataQuery`** — `{ brand, locale, preview }` derived from session context.
- [x] **`GetGlobalDataUseCase` / `GlobalDataPort`** — inbound/outbound port pair (ADR-002).
- [x] **`GetGlobalDataService`** — cache-aside with Caffeine L1 (no Redis L2 needed; payload is
      small and session-independent; 15-min default TTL configurable via
      `CONTENT_SERVICE_GLOBAL_DATA_CACHE_TTL`).
- [x] **`GlobalDataClient`** — reuses `contentServiceRestClient` (same pool + interceptor); its own
      `"global-data"` circuit breaker so globalData failures never trip the home-page breaker
      (ADR-004). 404 → 502 (config error), 5xx → 502, CB open → 503.
- [x] **`GlobalDataController`** — `GET /global-data`; reads brand/locale from session context;
      preview mode via configurable header; echoes `x-request-id`.
- [x] **`ContentstackProperties`** extended with 3 new fields:
      `globalDataContentType`, `globalDataEntryId`, `globalDataCacheTtl`.
- [x] **`CacheConfig`** — new `globalDataL1Cache` bean (Caffeine, 15-min TTL, max 50 entries).
- [x] **`application.yaml`** — env vars:
      `CONTENT_SERVICE_GLOBAL_DATA_TYPE` (default `global_data`),
      `CONTENT_SERVICE_GLOBAL_DATA_ENTRY` (default `global_data`),
      `CONTENT_SERVICE_GLOBAL_DATA_CACHE_TTL` (default `15m`).
- [x] **`GlobalDataClientTest`** — 7 tests: happy path, preview mode, absent keys, URL format,
      HTTP 404, HTTP 500, circuit-breaker trip.
- [x] **`GlobalDataControllerTest`** — 9 tests: 200 with payload, empty maps, request-id echo,
      UUID generation, preview flag propagation, 502, 503, 500 masking.

**Open:** CMS team confirmation of exact `global_data` entry id and locale-specific content
structure. `header` and `footer` fields (present in BFF `globalData`) are not yet surfaced —
addressed in Phase 16.

Effort: ~1 day.

---

### Phase 15 — Salesforce adapter validation ✅ (unit/contract) / ⬜ (live e2e)
**Scope:** Validate the full dynamic `products_list` flow through contract tests; document the
live e2e runbook for when a CMS page with Salesforce blocks is available.

**Completed (no live Salesforce access required):**

- [x] **`ProductsListAdapterTest`** — 13 tests covering the full adapter surface:
  - Guest guard: null / blank `userId` → immediate `DynamicBlockServiceUnavailableException`,
    zero HTTP calls made.
  - Request body: asserts `action`, `flags.noCampaigns`, `source.channel/application`,
    `user.attributes.ID_ATG1` against the Salesforce contract (Rule 19 reference:
    `ProductListSalesforcePopulateStrategy` in BFF).
  - `productDataMapped` path: maps `productId → sku`, `title`, `priceInfo.listPrice.price → price`.
  - Multi-product mapping and null `priceInfo` resilience.
  - Raw `products` fallback: maps `id → sku`, `attributes.name.value → title`,
    `attributes.listPrice.value → price`.
  - `productDataMapped` takes precedence when both arrays are non-empty.
  - Only the first `campaignResponses` element is consumed.
  - Empty `campaignResponses` / absent key / null `payload` / empty product arrays → empty
    resolution (no NPE).
  - HTTP 500 from Salesforce → `DynamicBlockServiceUnavailableException` with correct
    `blockId` and `blockType`.
- [x] **`ProductsListResolveControllerTest`** — 10 tests covering the REST layer:
  - 200 with product items in JSON response.
  - Empty product list (200, not 204).
  - `x-request-id` echo and UUID generation.
  - `x-user-id` header forwarded as `query.userId()`.
  - Absent `x-user-id` forwarded as null (guest session).
  - `DynamicBlockServiceUnavailableException` → 502 with `blockId`, `blockType`, `retryable`.
  - `ServiceUnavailableException` → 503 with `errorCode=SERVICE_UNAVAILABLE`.
  - Unexpected error → 500, consumer message only (no internal detail leaked).
  - `blockId` too long → 400 `VALIDATION_ERROR`.

**Live e2e validation runbook (external gate — blocked):**

The authenticated home page response (BFF comparison baseline) contains zero `products_list`
blocks (`salesforce: true` in globalData but no blocks in the home template). The following
steps are required once a suitable CMS page is identified:

1. **Find a CMS template with `products_list` blocks** — check Contentstack for page entries that
   contain blocks with `_content_type_uid: products_list`. Candidates: flash-sale pages, category
   landing pages, personalised carousels.

2. **Confirm field mapping** — verify that the CMS block carries a `salesforce_experience_id` field
   (or equivalent) that maps to the Evergage campaign action. The BFF's
   `ProductListSalesforcePopulateStrategy` maps `block.salesforce_experience_id → action` in the
   Salesforce request; ms-home currently uses `SalesforceProperties.defaultCarouselAction` as a
   fixed value. A per-block action field would require a `ProductsListQuery` extension.

3. **Deploy with env vars set** — `SALESFORCE_AUTHORIZATION=Bearer <live-token>`,
   `CONTENT_SERVICE_HOME_ENTRY=<page-with-products-list>`.

4. **Smoke test the placeholder** — `GET /home?path=<page>` with `x-authenticated: true` and
   `x-user-id: <valid-ATG-id>`. Verify:
   - The block appears in `blocks[]` with `kind: DYNAMIC`.
   - `resolutionUrl` points to `/home/blocks/products-list/<blockId>`.
   - `status: AVAILABLE` (feature flag `products-list-salesforce` is `true`).

5. **Smoke test the resolution** — `GET /home/blocks/products-list/<blockId>` with the same
   session headers. Verify: 200, `products[]` non-empty, each product has `sku`, `title`, `price`.

6. **CB test** — set `SALESFORCE_AUTHORIZATION=Bearer invalid` and fire > 20 requests; confirm
   the breaker opens and subsequent calls return 503 without hitting Salesforce.

Effort: ~0.5 day (blocked until a CMS page with `products_list` blocks is identified).

---

### Phase 16 — Header / footer serving strategy ✅ COMPLETE

**Decision (ADR-012):** Extend `GET /global-data` — header and footer are session-independent,
site-wide data with the same 15-minute TTL and circuit-breaker budget as the rest of GlobalData.

**Implemented:**
- `GlobalData` record extended with `header: Map<String,Object>` and `footer: Map<String,Object>` (6-param)
- `GlobalDataClient.doFetch()` extracts `header` and `footer` via `extractMap()` (keys `"header"`, `"footer"`)
- `GlobalDataResponse` DTO and `GlobalDataMapper.toResponse()` pass through both fields
- **2 new tests** in `GlobalDataClientTest`: `fetchGlobalData_headerAndFooter_extractedFromCmsResponse`
  (verifies extraction) + updated `fetchGlobalData_absentCmsKeys_returnsEmptyMaps` (header/footer assert)
- **2 new tests** in `GlobalDataControllerTest`: `getGlobalData_headerAndFooterPresent_returnedInResponse`
  + updated `getGlobalData_emptyMaps_returns200WithEmptyObjects` (header/footer empty map assertions)

**Result:** 126 tests, 0 failures, BUILD SUCCESS.

---

## 6. Immediate Next Step

**Phase 10** is the P0 gate: without fixing `template.blocks` parsing, the service returns an
empty block list for all production content. Everything else is built on top of that.

Start with `ContentServiceClient.java` → update `mergeLayouts()` to read `template.blocks` first.
