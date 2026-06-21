# ms-home — Architecture

## Overview

`ms-home` is a Java 21 / Spring Boot 4.1 microservice that composes the Liverpool e-commerce Home
page and serves site-wide global data. Its jobs:

1. **Home page composition** — fetch the CMS layout, resolve static block content inline, and return
   dynamic block *placeholders* the frontend resolves independently via a second call.
2. **Dynamic block resolution** — receive a placeholder ID from the frontend, call Salesforce
   Evergage, and return personalised product data.
3. **Global data** — serve feature flags, public runtime variables, brand themes, and the global
   navigation header/footer as a single cached document.

The service never holds user state, never writes to any store, and never personalises the page
layout itself.

---

## Hexagonal Architecture (Ports & Adapters)

All source-code dependencies point inward — infrastructure depends on the domain; the domain depends
on nothing.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Adapters (inbound)                               │
│  HomeController · ProductsListResolveController · GlobalDataController   │
│  GlobalExceptionHandler · MdcRequestContextFilter                        │
├──────────────────────────────────────────────────────────────────────────┤
│                       Application (use cases)                            │
│   GetHomePageService · ProductsListResolveService · GetGlobalDataService │
├──────────────────────────────────────────────────────────────────────────┤
│                             Domain                                       │
│  HomeCompositionService · Models · Ports                                 │
│  (zero Spring annotations; pure Java records + sealed interfaces)        │
├──────────────────────────────────────────────────────────────────────────┤
│                         Adapters (outbound)                              │
│  ContentServiceClient · GlobalDataClient · StaticBlockCacheAdapter       │
│  SessionContextAdapter · FeatureFlagAdapter · ProductsListAdapter        │
└──────────────────────────────────────────────────────────────────────────┘
```

### Layer responsibilities

| Layer | Package | Role |
|---|---|---|
| Domain | `domain/model`, `domain/error`, `domain/port`, `domain/service` | Pure business logic; no Spring; no I/O. Records and sealed interfaces. |
| Application | `application/usecase` | Orchestrates domain + ports for a single use case. `@Service` beans that implement inbound ports. |
| Inbound adapters | `adapter/inbound/rest` | HTTP entry points (`@RestController`), DTOs, mappers, exception translation. |
| Outbound adapters | `adapter/outbound/**` | Implement outbound domain ports; talk to external systems. |
| Config | `config/` | Spring beans that wire the above: `@Configuration`, `@ConfigurationProperties`, filters, security. |

### Domain layer rules

- Zero Spring annotations (`@Component`, `@Service`, etc.) — the domain is framework-agnostic.
- All types are records or sealed interfaces; no mutable state.
- Domain services (`HomeCompositionService`) are plain POJOs wired via `DomainBeansConfig`.
- Domain ports are Java interfaces; the domain never references adapter implementations.

---

## Package layout

```
com.liverpool.ms_home
├── MsHomeApplication                        Spring Boot entry point
│
├── config/
│   ├── CacheConfig                          Caffeine L1 beans (home page + global data)
│   ├── ContentstackProperties               content-service config (URLs, TTLs, entry IDs)
│   ├── DomainBeansConfig                    Wires domain POJOs (HomeCompositionService, BlockResolutionCatalog)
│   ├── HomeProperties                       Feature flags + block endpoint paths
│   ├── MdcRequestContextFilter              requestId / correlationId / service / operation in MDC
│   ├── OutboundLoggingInterceptor           Logs all RestClient calls; masks secrets
│   ├── Resilience4jConfig                   CircuitBreakerRegistry + Micrometer binding
│   ├── ResilienceProperties                 CB tuning (5% threshold, no retries)
│   ├── RestClientConfig                     Shared content-service RestClient (pool + interceptor)
│   ├── SalesforceConfig                     Dedicated Salesforce RestClient
│   ├── SalesforceProperties                 Salesforce connection settings
│   └── SecurityConfig                       Stateless; secure headers; /actuator/** deny on port 8080
│
├── domain/
│   ├── error/
│   │   ├── HomeException                    Base exception (consumer message + technical detail)
│   │   ├── ContentServiceUnavailableException  502 — CMS proxy unreachable or error
│   │   ├── DynamicBlockServiceUnavailableException  502 — Salesforce/block resolver error
│   │   ├── HomeDefinitionNotFoundException  404 — page path not found in CMS
│   │   ├── ServiceUnavailableException      503 — circuit breaker open
│   │   ├── ValidationException              400
│   │   ├── ErrorCodes                       String constants for errorCode fields
│   │   └── ErrorCategory                   RETRYABLE / NON_RETRYABLE classification
│   │
│   ├── model/
│   │   ├── home/
│   │   │   ├── HomePage                     Composed page: pageTitle, seo{}, blocks[]
│   │   │   ├── HomeBlock (sealed)           permits StaticBlock | DynamicPlaceholder
│   │   │   ├── StaticBlock                  Resolved block with CMS content map
│   │   │   ├── DynamicPlaceholder           Unresolved block: blockId, resolutionUrl, status
│   │   │   ├── BlockType                    Enum: UID → (name, BlockKind, endpoint path)
│   │   │   ├── BlockKind                    STATIC | DYNAMIC
│   │   │   ├── BlockResolution              Wires a BlockType to its resolution strategy
│   │   │   ├── BlockResolutionCatalog       Registry of all known BlockResolutions
│   │   │   ├── DynamicBlockStatus          AVAILABLE | DISABLED (feature-flag gated)
│   │   │   ├── AudienceFilter               AUTHENTICATED | GUEST | ALL
│   │   │   ├── HomePageQuery               brand, locale, path, preview, sessionContext
│   │   │   └── SessionContext              authenticated, brand, channel, locale
│   │   ├── content/
│   │   │   ├── HomeDefinition              CMS page: pageTitle, seo{}, blocks[]
│   │   │   ├── BlockDefinition             CMS block: uid, contentTypeUid, content map, filters
│   │   │   └── ContentQuery                brand, locale, path, preview
│   │   └── globaldata/
│   │       ├── GlobalData                  locale, featureFlags{}, publicVariables{},
│   │       │                               themes{}, header{}, footer{}
│   │       └── GlobalDataQuery             brand, locale, preview
│   │
│   ├── port/
│   │   ├── inbound/
│   │   │   ├── GetHomePageUseCase
│   │   │   ├── ResolveProductsListUseCase
│   │   │   └── GetGlobalDataUseCase
│   │   └── outbound/
│   │       ├── ContentPort                 fetchHomeDefinition(ContentQuery)
│   │       ├── StaticBlockCachePort        get / put for HomeDefinition (L1+L2)
│   │       ├── FeatureFlagPort             isEnabled(flagName)
│   │       ├── SessionContextPort          currentContext()
│   │       ├── ProductsListPort            fetch(ProductsListQuery)
│   │       └── GlobalDataPort              fetchGlobalData(GlobalDataQuery)
│   │
│   └── service/
│       └── HomeCompositionService          Audience filter, channel visibility, feature-flag status
│
├── application/
│   └── usecase/
│       ├── GetHomePageService              Cache-aside; delegates composition to HomeCompositionService
│       ├── ProductsListResolveService      CB "products-list-salesforce" → ProductsListPort
│       └── GetGlobalDataService           L1 Caffeine cache-aside → GlobalDataPort
│
└── adapter/
    ├── inbound/rest/
    │   ├── controller/
    │   │   ├── HomeController              GET /home
    │   │   ├── ProductsListResolveController  GET /home/blocks/products-list/{blockId}
    │   │   └── GlobalDataController        GET /global-data
    │   ├── dto/
    │   │   ├── HomePageResponse            pageTitle, seo{}, blocks[]
    │   │   ├── HomeBlockResponse           kind, blockId, content | resolutionUrl, status
    │   │   ├── GlobalDataResponse          locale, featureFlags{}, publicVariables{},
    │   │   │                               themes{}, header{}, footer{}
    │   │   ├── ProductsListResponse        blockId, title, products[]
    │   │   └── ProductItemResponse         sku, title, price
    │   ├── mapper/
    │   │   ├── HomePageMapper              HomePage → HomePageResponse
    │   │   ├── BlockContentNormalizer      Strips CMS metadata; renames fields per block type
    │   │   ├── GlobalDataMapper            GlobalData → GlobalDataResponse
    │   │   └── ProductsListMapper          ProductsListResolution → ProductsListResponse
    │   └── GlobalExceptionHandler          RFC 7807 ProblemDetail; never leaks internal detail
    └── outbound/
        ├── contentstack/
        │   ├── ContentServiceClient        GET /content/{type}/{locale}/{id} — CB "content-service"
        │   └── GlobalDataClient            GET /content/{type}/{locale}/{id} — CB "global-data"
        ├── featureflag/FeatureFlagAdapter  Reads home.feature-flags.* config map
        ├── redis/StaticBlockCacheAdapter   L1 Caffeine → L2 Redis for HomeDefinition
        ├── salesforce/ProductsListAdapter  POST Salesforce Evergage — CB "products-list-salesforce"
        └── session/SessionContextAdapter   Reads API-gateway headers; @RequestScope
```

---

## Circuit breakers

Three independent circuit breakers are registered in `Resilience4jConfig`. They share the same
tuning from `ResilienceProperties` but are isolated so a trip in one never affects the others.

```
┌────────────────────────────────────────────────────────────────────┐
│                     CircuitBreakerRegistry                         │
│                                                                    │
│  "content-service"          "products-list-salesforce"  "global-data"  │
│   ContentServiceClient       ProductsListAdapter        GlobalDataClient │
│   5% failure / 20-call       5% failure / 20-call       5% failure / 20-call │
│   window, no retry           window, no retry            window, no retry │
│   OPEN → 502                 OPEN → 503                  OPEN → 503  │
└────────────────────────────────────────────────────────────────────┘
```

---

## Caching

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Cache topology                              │
│                                                                     │
│  GET /home                           GET /global-data               │
│  ─────────────────────────           ──────────────────────────     │
│  homeL1Cache (Caffeine)              globalDataL1Cache (Caffeine)   │
│    key: home:{brand}:{locale}          key: global:def:{b}:{l}:{p}  │
│    TTL: l1-cache-ttl (30 s)            TTL: global-data-cache-ttl   │
│    ↓ miss                              (15 min); no Redis L2        │
│  homeL2Cache (Redis)                                                │
│    key: home:def:{brand}:{locale}                                   │
│    TTL: cache-ttl (5 min)                                           │
│    ↓ miss                                                           │
│  ContentServiceClient                GlobalDataClient               │
│  → content-service proxy             → content-service proxy        │
│                                                                     │
│  Dynamic blocks (products_list) are NEVER cached at this layer.     │
└─────────────────────────────────────────────────────────────────────┘
```

**GlobalData uses L1 only** — the payload is small (~1–5 KB), changes rarely, and one origin
miss per instance per 15-minute TTL is acceptable. Adding Redis L2 would add latency and
operational overhead with no meaningful benefit.

---

## Request flow — GET /home

```
Browser / BFF
  │
  ▼
API Gateway
  sets: x-authenticated, x-brand-id, x-channel, x-locale
  │
  ▼
MdcRequestContextFilter
  populates MDC: requestId, correlationId, service, operation
  │
  ▼
SecurityConfig filter chain
  │
  ▼
HomeController.getHomePage(path)
  │
  ├─ SessionContextAdapter.currentContext()
  │    reads gateway-injected headers → SessionContext
  │
  └─ GetHomePageService.getHomePage(query)
       │
       ├─ StaticBlockCacheAdapter.get(contentQuery)     ← L1 Caffeine
       │     hit → return cached HomeDefinition
       │     miss ↓
       │
       ├─ StaticBlockCacheAdapter.get(contentQuery)     ← L2 Redis
       │     hit → populate L1, return HomeDefinition
       │     miss ↓
       │
       ├─ ContentServiceClient.fetchHomeDefinition()    ← CB "content-service"
       │     GET /content/{type}/{locale}/{id}
       │     404 → HomeDefinitionNotFoundException (404 to caller)
       │     5xx / network → ContentServiceUnavailableException (502)
       │     CB open → ServiceUnavailableException (503)
       │
       ├─ StaticBlockCacheAdapter.put()                 ← populate L1 + L2
       │
       └─ HomeCompositionService.compose()
            ├─ audience filtering  (AUTHENTICATED / GUEST / ALL per block)
            ├─ channel visibility  (WEB / APP flags per block)
            ├─ feature-flag check  (FeatureFlagAdapter → HomeProperties config map)
            └─ static → content passthrough; dynamic → DynamicPlaceholder

  → HomePageMapper.toResponse()   (BlockContentNormalizer strips CMS metadata)
  → 200 JSON  { pageTitle, seo{}, blocks[] }
```

---

## Request flow — GET /home/blocks/products-list/{blockId}

```
Frontend (one call per dynamic placeholder, independent of GET /home)
  │
  ▼
API Gateway
  sets: x-authenticated, x-brand-id, x-channel, x-locale, x-user-id
  │
  ▼
MdcRequestContextFilter
  │
  ▼
ProductsListResolveController.resolveProductsList(blockId)
  │
  ├─ SessionContextAdapter.currentContext()
  │
  └─ ProductsListResolveService.resolve(query)          ← CB "products-list-salesforce"
       │
       └─ ProductsListAdapter.fetch(query)
            guest guard: null / blank userId
              → DynamicBlockServiceUnavailableException (502) — no HTTP call
            │
            POST https://…evergage.com/api2/authevent/liverpool
              body: { action, flags, source, user.attributes.ID_ATG1 }
            │
            maps campaignResponses[0].payload
              productDataMapped[] (preferred) or products[] (fallback)
              → ProductsListResolution { blockId, title, products[] }
            │
            5xx → DynamicBlockServiceUnavailableException (502)
            CB open → ServiceUnavailableException (503)

  → ProductsListMapper.toResponse()
  → 200 JSON  { blockId, title, products[{ sku, title, price }] }
```

---

## Request flow — GET /global-data

```
Browser / BFF (one call per page load, shared across components)
  │
  ▼
API Gateway
  sets: x-brand-id, x-locale  (no auth required — session-independent)
  │
  ▼
MdcRequestContextFilter
  │
  ▼
GlobalDataController.getGlobalData()
  │
  ├─ SessionContextAdapter.currentContext()    ← brand + locale
  ├─ preview flag = x-preview header presence
  │
  └─ GetGlobalDataService.getGlobalData(query)
       │
       ├─ globalDataL1Cache.getIfPresent(key)
       │     key: global:def:{brand}:{locale}:{preview}
       │     hit → return GlobalData (no downstream call)
       │     miss ↓
       │
       └─ GlobalDataClient.fetchGlobalData(query)       ← CB "global-data"
            GET /content/{globalDataContentType}/{locale}/{globalDataEntryId}
            header x-brand-id: {brand}[-PREVIEW]
            │
            extracts top-level keys as opaque maps:
              feature_flags, public_variables, themes, header, footer
            absent key → Map.of() (never null)
            │
            404 → ContentServiceUnavailableException (502) — config error
            5xx / network → ContentServiceUnavailableException (502)
            CB open → ServiceUnavailableException (503)

  → GlobalDataMapper.toResponse()
  → 200 JSON  { locale, featureFlags{}, publicVariables{}, themes{}, header{}, footer{} }
```

---

## Error handling

All exceptions translate to RFC 7807 `ProblemDetail` in `GlobalExceptionHandler`. Internal
details (stack traces, upstream error messages) are never exposed to callers.

```
Exception type                        HTTP    errorCode
────────────────────────────────────  ──────  ─────────────────────────────
HomeDefinitionNotFoundException       404     HOME_DEFINITION_NOT_FOUND
ContentServiceUnavailableException    502     CONTENT_SERVICE_UNAVAILABLE
DynamicBlockServiceUnavailableException 502   BLOCK_SERVICE_UNAVAILABLE
ServiceUnavailableException           503     SERVICE_UNAVAILABLE
ValidationException                   400     VALIDATION_ERROR
ConstraintViolationException          400     VALIDATION_ERROR
RuntimeException (unhandled)          500     UNEXPECTED_ERROR
```

`retryable: true` is set on all 502 and 503 responses. The 500 body contains only a generic
consumer message — no internal detail is ever surfaced.

---

## Key design constraints

- **No personalisation in GET /home.** Layout ordering and block presence are identical for all
  users of the same brand/locale/path. `SessionContext` carries auth/brand/channel for filtering
  only.
- **Static blocks are cached; dynamic blocks are not.** `HomeDefinition` uses Caffeine L1 + Redis
  L2. `GlobalData` uses Caffeine L1 only. Product lists are personalised and must never be cached
  at this layer.
- **Virtual threads (Java 21 Loom).** `spring.threads.virtual.enabled: true`. No thread-pool
  sizing required for I/O-bound blocking calls.
- **Circuit breakers are programmatic.** Core Resilience4j (not the Spring AOP starter) is used
  at the call site in each adapter/service. See [decisions.md](decisions.md) ADR-008.
- **Secrets never in source.** `SALESFORCE_AUTHORIZATION`, `REDIS_*`, and other credentials are
  injected via environment variables at deploy time. Defaults in `application.yaml` are
  intentionally empty. `OutboundLoggingInterceptor` masks `authorization`, `cookie`,
  `set-cookie`, `x-api-key`, `delivery-token`, and `proxy-authorization` headers in logs.

---

## Adding a new block type

1. Add a `BlockType` constant with its `_content_type_uid` and `BlockKind` (STATIC or DYNAMIC).
2. **STATIC:** `BlockContentNormalizer` strips CMS metadata automatically. Add a `normalize`
   branch only if the block needs field renaming. No other changes.
3. **DYNAMIC:** Add a domain port + application use case + outbound adapter + inbound controller;
   register the `BlockResolution` in `DomainBeansConfig`. No edits to existing blocks.

## Adding a new global-data field

1. Add the field to `GlobalData` (domain record) and `GlobalDataResponse` (DTO).
2. Extract it in `GlobalDataClient.doFetch()` using `extractMap()` (or a typed extractor for
   non-map fields).
3. Pass it through in `GlobalDataMapper.toResponse()`.
4. No cache or circuit-breaker changes needed — the same key, TTL, and CB govern all fields.
