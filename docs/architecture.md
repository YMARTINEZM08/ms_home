# ms-home — Architecture

## Overview

`ms-home` is a Java 21 / Spring Boot 4.1 microservice that composes the Liverpool e-commerce Home
page. Its only job is to determine **what is on the page and in what order** — it fetches the layout
from a CMS proxy, resolves static block content inline, and returns dynamic block *placeholders* that
the frontend resolves independently. It never holds user state, never writes to any store, and never
personalises the page layout itself.

## Hexagonal Architecture (Ports & Adapters)

The codebase follows the Hexagonal / Clean Architecture pattern strictly. The single rule that makes
it work: **all source-code dependencies point inward**. Infrastructure depends on the domain; the
domain depends on nothing.

```
┌─────────────────────────────────────────────────────────┐
│                      Adapters (inbound)                  │
│   HomeController  ·  ProductsListResolveController       │
│   GlobalExceptionHandler  ·  MdcRequestContextFilter     │
├─────────────────────────────────────────────────────────┤
│                 Application (use cases)                  │
│   GetHomePageService  ·  ProductsListResolveService      │
├─────────────────────────────────────────────────────────┤
│                        Domain                            │
│   HomeCompositionService  ·  Models  ·  Ports            │
│   (zero Spring annotations; pure Java records + logic)   │
├─────────────────────────────────────────────────────────┤
│                    Adapters (outbound)                   │
│  ContentServiceClient · StaticBlockCacheAdapter          │
│  SessionContextAdapter · FeatureFlagAdapter              │
│  ProductsListAdapter (Salesforce)                        │
└─────────────────────────────────────────────────────────┘
```

### Layers

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

## Package layout

```
com.liverpool.ms_home
├── MsHomeApplication                        Spring Boot entry point
├── config/                                  Cross-cutting infrastructure
│   ├── CacheConfig                          Caffeine L1 bean
│   ├── ContentstackProperties               content-service config
│   ├── DomainBeansConfig                    Wires domain POJOs as Spring beans
│   ├── HomeProperties                       Feature flags + block endpoint paths
│   ├── MdcRequestContextFilter              MDC population per request
│   ├── OutboundLoggingInterceptor           Logs all RestClient calls; masks secrets
│   ├── Resilience4jConfig                   CircuitBreakerRegistry + Micrometer binding
│   ├── ResilienceProperties                 CB tuning (5% threshold, no retries)
│   ├── RestClientConfig                     Shared content-service RestClient
│   ├── SalesforceConfig                     Dedicated Salesforce RestClient
│   ├── SalesforceProperties                 Salesforce connection settings
│   └── SecurityConfig                       Stateless; secure headers; actuator deny
│
├── domain/
│   ├── error/                               Exception hierarchy (HomeException base)
│   ├── model/
│   │   ├── home/                            Core page model (HomePage, HomeBlock sealed, ...)
│   │   ├── content/                         CMS contract (HomeDefinition, BlockDefinition, ...)
│   │   └── block/productslist/              Products-list domain model
│   ├── port/
│   │   ├── inbound/                         Use-case interfaces (GetHomePageUseCase, ...)
│   │   └── outbound/                        Adapter interfaces (ContentPort, CachePort, ...)
│   └── service/
│       └── HomeCompositionService           Page composition (audience, channel, feature flags)
│
├── application/
│   └── usecase/
│       ├── GetHomePageService               Cache-aside; delegates composition to domain
│       └── ProductsListResolveService       CB wrap around ProductsListPort.fetch
│
└── adapter/
    ├── inbound/rest/
    │   ├── controller/                      HomeController, ProductsListResolveController
    │   ├── dto/                             Response records (HomePageResponse, ...)
    │   ├── mapper/                          HomePageMapper, ProductsListMapper
    │   └── GlobalExceptionHandler           RFC 7807 ProblemDetail translation
    └── outbound/
        ├── contentstack/ContentServiceClient  HTTP → content-service proxy
        ├── featureflag/FeatureFlagAdapter     Reads home.feature-flags.* config map
        ├── redis/StaticBlockCacheAdapter      L1 Caffeine + L2 Redis cache
        ├── salesforce/ProductsListAdapter     POST → Salesforce Evergage
        └── session/SessionContextAdapter      Reads upstream-gateway headers; @RequestScope
```

## Request flow — GET /home

```
Browser/BFF
  → API Gateway (validates token, sets x-authenticated, x-brand-id, x-channel, x-locale)
    → MdcRequestContextFilter  (sets requestId/correlationId/service/operation in MDC)
      → SecurityConfig filter chain
        → HomeController.getHomePage()
            SessionContextAdapter.currentContext()  ← reads gateway-set headers
            GetHomePageService.getHomePage(query)
              StaticBlockCacheAdapter.get(contentQuery)  ← L1 Caffeine → L2 Redis
              [on miss] ContentServiceClient.fetchHomeDefinition()  ← CB "content-service"
              StaticBlockCacheAdapter.put()
              HomeCompositionService.compose()
                ↳ audience filtering, channel visibility, feature-flag placeholder status
            HomePageMapper.toResponse()
          → 200 JSON (static blocks resolved; dynamic blocks as placeholders)
```

## Request flow — GET /home/blocks/products-list/{blockId}

```
Frontend (per-placeholder, independent of the /home call)
  → API Gateway (sets x-authenticated, x-brand-id, x-channel, x-locale, x-user-id)
    → MdcRequestContextFilter
      → ProductsListResolveController.resolveProductsList()
          SessionContextAdapter.currentContext()
          ProductsListResolveService.resolve(query)  ← CB "products-list-salesforce"
            ProductsListAdapter.fetch(query)
              POST https://serviciosliverpoolsadecv.us-4.evergage.com/api2/authevent/liverpool
          ProductsListMapper.toResponse()
        → 200 JSON (sku, title, price per product)
```

## Key design constraints

- **No personalisation in GET /home.** Layout ordering and block presence are identical for all
  users of the same brand/locale. `SessionContext` carries auth/brand/channel for filtering only.
- **Static blocks are cached; dynamic blocks are not.** `HomeDefinition` (the raw CMS layout) is
  stored in Caffeine L1 (short TTL) and Redis L2 (longer TTL). Product lists are personalised and
  must never be cached at this layer.
- **Virtual threads (Java 21 Loom).** `spring.threads.virtual.enabled: true`. Each request runs
  on a lightweight virtual thread — no thread-pool sizing required for I/O-bound blocking calls.
- **Circuit breakers are programmatic.** Core Resilience4j (not the Spring AOP starter) is used
  at the call site in each adapter/service. See [decisions.md](decisions.md) ADR-008.

## Adding a new block type

1. Add a `BlockType` constant with its `_content_type_uid` and `BlockKind` (STATIC or DYNAMIC).
2. For STATIC: no extra code — `HomeCompositionService` passes unknown static blocks through unchanged.
3. For DYNAMIC: add a domain port + application use case + outbound adapter + inbound controller,
   register the `BlockResolution` in `DomainBeansConfig`. No edits to existing blocks.
