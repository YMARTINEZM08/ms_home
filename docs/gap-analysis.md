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

### Phase 13 — Validate channel / audience filtering mechanism
**Scope:** Align with the real CMS content strategy before implementing filtering.

- [ ] Confirm with CMS team: are `enable_on_web` / `enable_on_apps` used on home blocks?
- [ ] If filtering is template-based (separate CMS template per channel): remove
      `visibleForChannel()` guard and rely on `path` param routing.
- [ ] If per-block: confirm field names and default values when absent.
- [ ] Validate `container_guest` audience semantics — does it map to `guest` audience or
      is it always rendered (frontend decides via session)?
- [ ] Update `HomeCompositionService` and `BlockDefinition` accordingly.

Effort: ~0.5 day (excluding CMS team coordination).

---

### Phase 14 — GlobalData endpoint
**Scope:** Serve the global config the frontend needs (feature flags, public variables, themes).

- [ ] Design: should globalData be bundled in `GET /home` response or a separate `GET /global`?
- [ ] Add `globalData` to `ContentPort` or a new `GlobalDataPort`.
- [ ] Serve at minimum: `feature_flags`, `public_variables`, `site_domain`.
- [ ] Consider caching strategy (globalData changes rarely; long L1+L2 TTL appropriate).

Effort: ~1 day.

---

### Phase 15 — Salesforce block end-to-end validation
**Scope:** Validate the full dynamic products_list flow with a real page variant.

- [ ] Identify a Contentstack page entry that contains Salesforce blocks.
- [ ] Confirm the `salesforce_experience_id` field exists on the block and maps to Evergage campaign.
- [ ] Run end-to-end: `GET /home?path=<that-page>` → `GET /home/blocks/{blockId}`.
- [ ] Validate circuit breaker behavior against real Salesforce sandbox.
- [ ] Add integration test or contract test for the Salesforce adapter.

Effort: ~1 day.

---

### Phase 16 — Header / footer serving strategy
**Scope:** Decide and implement how the frontend gets header/footer.

- [ ] Option A: Bundle in `/home` response (matches BFF behavior).
- [ ] Option B: Dedicated `GET /navigation` endpoint cached long-term.
- [ ] Whichever is chosen: add `header{}` and `footer{}` to the appropriate response.

Effort: ~0.5–1 day.

---

## 6. Immediate Next Step

**Phase 10** is the P0 gate: without fixing `template.blocks` parsing, the service returns an
empty block list for all production content. Everything else is built on top of that.

Start with `ContentServiceClient.java` → update `mergeLayouts()` to read `template.blocks` first.
