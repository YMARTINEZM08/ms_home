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

```json
{
  "template": {
    "top_layout":    [ { "_uid": "...", "_content_type_uid": "banner", ... } ],
    "layout":        [ { ... } ],
    "bottom_layout": [ { ... } ]
  }
}
```

Blocks from `top_layout`, `layout`, and `bottom_layout` are merged in that order. Each block
carries at least:

| Field | Description |
|---|---|
| `_uid` | Contentstack block uid (used as `blockId`) |
| `_content_type_uid` | Maps to `BlockType` (e.g., `banner`, `products_list`) |
| `audience_filter` | `"logged"`, `"guest"`, or `"all"` (absent = `all`) |
| `enable_on_web` | boolean; blocks with `false` are excluded from the WEB channel |
| `enable_on_apps` | boolean; blocks with `false` are excluded from the APPS channel |

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

## 2. Salesforce Evergage (personalised product recommendations)

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

## 3. Redis (L2 cache)

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

## 4. Upstream API Gateway (inbound headers)

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
