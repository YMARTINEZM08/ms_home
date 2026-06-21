# ms-home — External Integrations

This document describes every external system that `ms-home` calls or depends on, covering the HTTP
contract, credentials/headers, error handling, and the location of the implementing adapter.

---

## 1. content-service (CMS proxy)

### Role
Provides the Home page layout: ordered list of blocks with their `_content_type_uid`, `_uid`,
audience filter, and channel visibility flags. This service is the internal proxy in front of
Contentstack; ms-home never calls Contentstack directly.

### Contract

```
GET /content/{contentType}/{locale}/{id}
```

| Element | Value |
|---|---|
| Base URL | `CONTENT_SERVICE_BASE_URL` (default `http://localhost:8082`) |
| `{contentType}` | `CONTENT_SERVICE_HOME_TYPE` (default `page`) |
| `{locale}` | locale from session context (e.g., `es-mx`) |
| `{id}` | `CONTENT_SERVICE_HOME_ENTRY` (default `home`); overridden by `path` query param |
| `x-brand-id` header | brand from session (e.g., `LP`); appended with `-PREVIEW` when preview mode |

### Response structure

**Production schema** (`template.blocks[]` — flat array):

```json
{
  "page_title": "Liverpool — Tienda Online",
  "seo": { "meta_description": "...", "no_index": false },
  "template": {
    "blocks": [
      { "uid": "blt...", "_content_type_uid": "hero_banner_slider", "title": "...", "banners": [ ... ] },
      { "uid": "blt...", "_content_type_uid": "container", "type": "default", "blocks": [ ... ] }
    ]
  }
}
```

**Legacy schema** (falls back to merged `top_layout + layout + bottom_layout` with `_uid` field —
present in older Contentstack entries).

Top-level fields extracted:

| Field | Mapped to |
|---|---|
| `page_title` | `HomeDefinition.pageTitle()` → `HomePageResponse.pageTitle` |
| `seo` | `HomeDefinition.seo()` → `HomePageResponse.seo` (opaque map) |

Each block in `template.blocks[]` carries at minimum:

| Field | Description |
|---|---|
| `uid` | Contentstack block uid (used as `blockId`); legacy entries use `_uid` |
| `_content_type_uid` | Maps to `BlockType` (e.g., `hero_banner_slider`, `products_list`) |
| `audience_filter` | `"logged"`, `"guest"`, or `"all"` (absent = defaults to `all`) |
| `enable_on_web` | boolean; absent = defaults to `true` (home template omits these) |
| `enable_on_apps` | boolean; absent = defaults to `true` |

### Error handling

| Condition | Adapter behaviour |
|---|---|
| HTTP 404 | Throws `HomeDefinitionNotFoundException` (404 to client) |
| HTTP 4xx/5xx | Throws `ContentServiceUnavailableException` (502 to client) |
| I/O failure | Throws `ContentServiceUnavailableException` (502 to client) |
| CB open | Throws `ServiceUnavailableException` (503 to client) |

### Adapter
`adapter/outbound/contentstack/ContentServiceClient` — circuit breaker: `"content-service"`.

### Caching
Responses are cached in `StaticBlockCacheAdapter` (L1 Caffeine + L2 Redis).  
Key format: `home:def:{brand}:{locale}:{path}:{preview}`.  
TTL: Redis `CONTENT_SERVICE_CACHE_TTL` (default 5 min), Caffeine `CONTENT_SERVICE_L1_CACHE_TTL` (default 30 s).

---

## 2. content-service — GlobalData

### Role
Provides site-wide CMS configuration consumed by every page: feature flags, public runtime
variables, and brand theme tokens. Served via `GET /global-data`, separate from the Home page
layout so each has its own cache TTL and circuit breaker.

### Contract

```
GET /content/{globalDataContentType}/{locale}/{globalDataEntryId}
```

| Element | Value |
|---|---|
| Base URL | `CONTENT_SERVICE_BASE_URL` (shared with Home) |
| `{globalDataContentType}` | `CONTENT_SERVICE_GLOBAL_DATA_TYPE` (default `global_data`) |
| `{locale}` | locale from session context (e.g., `es-mx`) |
| `{globalDataEntryId}` | `CONTENT_SERVICE_GLOBAL_DATA_ENTRY` (default `global_data`) |
| `x-brand-id` header | brand from session; appended with `-PREVIEW` when preview mode |

### Response structure

```json
{
  "feature_flags":    { "salesforce": true, "personalization": false, ... },
  "public_variables": { "site_domain": "https://www.liverpool.com.mx", ... },
  "themes":           { "primary_color": "#E31837", ... }
}
```

Top-level keys extracted:

| CMS key | Mapped to |
|---|---|
| `feature_flags` | `GlobalData.featureFlags()` → `GlobalDataResponse.featureFlags` |
| `public_variables` | `GlobalData.publicVariables()` → `GlobalDataResponse.publicVariables` |
| `themes` | `GlobalData.themes()` → `GlobalDataResponse.themes` |

Absent keys default to empty maps (`{}`). Additional top-level keys (e.g. `header`, `footer`) are
not yet mapped — planned in Phase 16.

### Error handling

| Condition | Adapter behaviour |
|---|---|
| HTTP 404 | Throws `ContentServiceUnavailableException` (502) — 404 indicates a config error (wrong entry id or locale), not a user error |
| HTTP 4xx/5xx | Throws `ContentServiceUnavailableException` (502) |
| I/O failure | Throws `ContentServiceUnavailableException` (502) |
| CB open | Throws `ServiceUnavailableException` (503) |

### Adapter
`adapter/outbound/contentstack/GlobalDataClient` — circuit breaker: `"global-data"` (independent
from the Home-page `"content-service"` breaker so a GlobalData failure never affects page loads).

### Caching
Caffeine L1 only (no Redis L2). Key: `global:def:{brand}:{locale}:{preview}`.
TTL: `CONTENT_SERVICE_GLOBAL_DATA_CACHE_TTL` (default 15 min).
GlobalData changes rarely and the payload is small (~1–5 KB) — one origin miss per instance per
TTL period is acceptable, making Redis L2 an unnecessary dependency.

---

## 4. Salesforce Evergage (personalised product recommendations)

### Role
Returns a personalised product carousel for the `products_list` block. Requires an authenticated
ATG user id; guest sessions cannot call this endpoint.

### Contract

```
POST /api2/authevent/liverpool
```

| Element | Value |
|---|---|
| Base URL | `SALESFORCE_BASE_URL` (default `https://serviciosliverpoolsadecv.us-4.evergage.com`) |
| Path | `SALESFORCE_ACTIONS_PATH` (default `/api2/authevent/liverpool`) |
| `Authorization` | `SALESFORCE_AUTHORIZATION` — **must be set at deploy time; never committed** |
| Content-Type | `application/json` |
| Timeout | `SALESFORCE_TIMEOUT` (default 4 s) + connect 2 s fixed |

### Request body

```json
{
  "action": "<SALESFORCE_DEFAULT_CAROUSEL_ACTION>",
  "flags":  { "noCampaigns": false },
  "source": { "channel": "Server", "application": "<SALESFORCE_APPLICATION>" },
  "user":   { "attributes": { "ID_ATG1": "<userId>" } }
}
```

`userId` is the ATG profile id passed in the `x-user-id` header by the upstream API gateway.

### Response mapping

The adapter reads `campaignResponses[0].payload` and maps products in priority order:

| Source field | Preferred | Fallback |
|---|---|---|
| Product array | `productDataMapped` | `products` |
| SKU | `productId` | `id` |
| Title | `title` | `attributes.name.value` |
| Price | `priceInfo.listPrice.price` | `attributes.listPrice.value` |

### Error handling

| Condition | Adapter behaviour |
|---|---|
| `userId` blank / null | Throws `DynamicBlockServiceUnavailableException` immediately (guest guard) |
| HTTP / I/O failure | Throws `DynamicBlockServiceUnavailableException` (502 to client) |
| CB open | `ProductsListResolveService` catches `CallNotPermittedException` → `ServiceUnavailableException` (503) |
| Empty `campaignResponses` | Returns `ProductsListResolution(blockId, "", [])` — empty but valid |

### Adapter
`adapter/outbound/salesforce/ProductsListAdapter` — circuit breaker: `"products-list-salesforce"`
(applied at `ProductsListResolveService`, not the adapter).

### Credentials note
The `Authorization` header value is injected from `SALESFORCE_AUTHORIZATION` at startup via
`SalesforceProperties`. The default in `application.yaml` is intentionally empty. The
`OutboundLoggingInterceptor` always masks `Authorization` before any log output.

---

## 5. Redis (L2 cache)

### Role
Shared, durable cache for `HomeDefinition` objects. Prevents cache stampede on pod restart and
provides cross-instance consistency.

### Connection

| Parameter | Env var | Default |
|---|---|---|
| Host | `REDIS_HOST` | `localhost` |
| Port | `REDIS_PORT` | `6379` |
| Timeout | `REDIS_TIMEOUT` | `2s` |

### Key scheme

```
home:def:{brand}:{locale}:{path}:{preview}
```

Example: `home:def:LP:es-mx:_:false` (root home, no preview, LP brand).

### Failure behaviour
Redis read errors → cache miss; origin fetch proceeds.  
Redis write errors → logged at WARN; L1 Caffeine is still populated.  
No hard dependency: a Redis outage degrades to content-service-only (slightly higher latency) with
no error surfaced to the client.

---

## 6. Upstream API Gateway (inbound headers)

ms-home trusts the API gateway to authenticate the session and forward context headers. It never
validates a JWT or session token itself.

| Header | Description | Default when absent |
|---|---|---|
| `x-authenticated` | `"true"` when the user is logged in | `"false"` |
| `x-brand-id` | Brand identifier (e.g., `LP`) | `CONTENT_SERVICE_BRAND` (config) |
| `x-channel` | Request channel (`WEB`, `APPS`) | `WEB` |
| `x-locale` | Locale (e.g., `es-mx`) | `es-mx` |
| `x-user-id` | ATG profile id (products-list endpoint only) | `null` (guest) |
| `x-request-id` | Correlation id; echoed in response header | Generated UUID |
| `x-correlation-id` | Upstream trace id propagated in MDC | Falls back to `requestId` |
| `x-preview` | Presence triggers Contentstack preview content | Absent = live content |

Adapter: `adapter/outbound/session/SessionContextAdapter` (`@RequestScope`).

---

## 7. Block content schemas (`GET /home` response)

`BlockContentNormalizer` (`adapter/inbound/rest/mapper/`) strips CMS system metadata and normalises
field names before the block is serialised. The table below documents the content contract per
`blockType`. Fields marked **assumed** are based on the BFF gap-analysis comparison and standard
Contentstack conventions — validate against live CMS schema when integrating (see Phase 13).

### Universal fields stripped from all blocks

`_version`, `ACL`, `_in_progress`, `created_at`, `updated_at`, `created_by`, `updated_by`,
`publish_details`, `audience_filter`, `enable_on_web`, `enable_on_apps`.

---

### `HERO_BANNER_SLIDER`

```json
{
  "title": "string | null",
  "banners": [
    {
      "uid":            "string — Contentstack block uid",
      "type":           "string — e.g. \"full-width\" (assumed)",
      "title":          "string | null",
      "imageUrl":       "string — extracted from CMS asset object (image.url)",
      "mobileImageUrl": "string — extracted from CMS asset object (mobile_image.url)",
      "button":         { "label": "string", "url": "string" },
      "video":          "object | omitted — present only when the CMS entry has a video"
    }
  ]
}
```

CMS source fields renamed: `image` → `imageUrl`, `mobile_image` → `mobileImageUrl`,
`cta_button` → `button`.

---

### `CONTAINER`

```json
{
  "type":    "string — e.g. \"default\" (assumed)",
  "title":   "string | null",
  "columns": { "desktop": "number | null", "mobile": "number | null", "tablet": "number | null" },
  "children": [
    "object — CMS system fields stripped; per-child-type field mapping is a follow-on (Phase 13)"
  ]
}
```

CMS source fields renamed: `blocks` → `children`, `desktop_columns / mobile_columns /
tablet_columns` → `columns.{desktop,mobile,tablet}` (assumed column field names).

---

### `CONTAINER_GUEST`

```json
{
  "title":       "string | null",
  "description": "string | null",
  "imageUrl":    "string — extracted from CMS asset object (image.url)",
  "buttonLabel": "string | null (assumed CMS field: button_label)"
}
```

---

### `BAND`

```json
{
  "title": "string | null",
  "items": [
    {
      "imageUrl": "string — extracted from CMS asset object (image.url)",
      "label":    "string | null",
      "url":      "string | null"
    }
  ]
}
```

CMS source field renamed: `content_list` → `items`.

---

### `CARD_SLIDER`

```json
{
  "title": "string | null",
  "cards": [
    {
      "title":    "string | null",
      "imageUrl": "string — extracted from CMS asset object (image.url)",
      "url":      "string | null",
      "button":   { "label": "string", "url": "string" }
    }
  ]
}
```

CMS source field renamed: `cta_button` → `button` (omitted when absent).

---

### `USER_GENERATED_CONTENT`

```json
{
  "title":       "string | null",
  "hashtag":     "string | null (assumed)",
  "description": "string | null",
  "cta":         "object | omitted — present only when the CMS entry has a CTA (assumed)"
}
```

---

### `BANNER` / `UNKNOWN`

Passthrough: CMS system fields stripped; all remaining fields forwarded as-is. No field renaming.
