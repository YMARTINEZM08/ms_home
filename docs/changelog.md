# ms-home — Changelog

## [0.1.0] — 2026-06-20

Initial migration from the legacy `digital_bff` NestJS monolith to a standalone Java 21 /
Spring Boot 4.1 microservice using Hexagonal Architecture.

### Added

**Foundation (Phase 0–1)**
- Java 21 / Spring Boot 4.1 project scaffold with virtual threads (`spring.threads.virtual.enabled: true`).
- Gradle → Maven migration; removed unused MongoDB dependency.
- ECS-structured JSON logging (`logging.structured.format.console: ecs`).
- Graceful shutdown (`server.shutdown: graceful`).
- Spring Boot Actuator with liveness/readiness probes on dedicated management port (default 8081).
- `SecurityConfig`: stateless, CSRF disabled, HSTS, `X-Frame-Options: DENY`, CSP
  `default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`.
- `OutboundLoggingInterceptor`: logs method/URI/status/latency at INFO; emits masked cURL at DEBUG.
  Masks `Authorization`, cookies, tokens before any log output.
- `ResilienceProperties` + `Resilience4jConfig`: programmatic circuit-breaker registry, 5% failure
  threshold, no retries, Micrometer metrics binding.

**Domain layer (Phase 2)**
- `domain/model/home`: `HomePage`, `HomeBlock` (sealed), `StaticBlock`, `DynamicPlaceholder`,
  `BlockType` (BANNER, PRODUCTS_LIST, UNKNOWN), `BlockKind` (STATIC, DYNAMIC), `AudienceFilter`,
  `DynamicBlockStatus`, `SessionContext`, `HomePageQuery`, `BlockResolution`, `BlockResolutionCatalog`.
- `domain/model/content`: `ContentQuery`, `BlockDefinition`, `HomeDefinition`.
- `domain/model/block/productslist`: `ProductsListQuery`, `ProductItem`, `ProductsListResolution`.
- `domain/error`: `HomeException` base, `ErrorCategory`, `ErrorCodes`, `ValidationException`,
  `HomeDefinitionNotFoundException`, `ContentServiceUnavailableException`,
  `DynamicBlockServiceUnavailableException`, `ServiceUnavailableException`.
- `domain/port/inbound`: `GetHomePageUseCase`, `ResolveProductsListUseCase`.
- `domain/port/outbound`: `ContentPort`, `SessionContextPort`, `FeatureFlagPort`, `ProductsListPort`,
  `StaticBlockCachePort`.
- `domain/service/HomeCompositionService`: audience filtering, channel visibility, feature-flag-driven
  placeholder status. Zero Spring annotations; pure Java logic.

**Outbound adapters (Phase 3)**
- `ContentServiceClient`: `GET /content/{type}/{locale}/{id}` via `RestClient`; circuit breaker
  `"content-service"`; maps `top_layout + layout + bottom_layout`; 404 → `HomeDefinitionNotFoundException`.
- `SessionContextAdapter` (`@RequestScope`): reads `x-authenticated`, `x-brand-id`, `x-channel`,
  `x-locale` from upstream-validated gateway headers.
- `FeatureFlagAdapter`: maps `FeatureFlagPort.isEnabled(flagId)` to `home.feature-flags.*` config map.
- `StaticBlockCacheAdapter`: two-tier cache; L1 Caffeine (30 s default); L2 Redis StringRedisTemplate
  + Jackson 3 (`tools.jackson.*`); key `home:def:{brand}:{locale}:{path}:{preview}`; Redis errors
  handled defensively (warn + continue).
- `HomeProperties`, `CacheConfig`, `ContentstackProperties` with cache TTL fields.

**Application use cases (Phase 4)**
- `GetHomePageService`: cache-aside (L1 → L2 → content-service → cache populate → compose).
- `ProductsListResolveService`: circuit breaker `"products-list-salesforce"` wraps
  `ProductsListPort.fetch`; maps `CallNotPermittedException` → `ServiceUnavailableException`.
- `DomainBeansConfig`: wires `HomeCompositionService` (POJO) and `BlockResolutionCatalog`.

**Inbound REST (Phase 5)**
- `HomeController`: `GET /home`; reads session via `SessionContextPort`; preview from configurable
  header; echoes `x-request-id`.
- `ProductsListResolveController`: `GET /home/blocks/products-list/{blockId}`; `@NotBlank @Size(max=128)`
  on `blockId`; reads `x-user-id`.
- DTOs: `HomePageResponse`, `HomeBlockResponse` (kind discriminator), `ProductsListResponse`,
  `ProductItemResponse`.
- Mappers: `HomePageMapper` (Java 21 sealed `switch`), `ProductsListMapper`.
- `GlobalExceptionHandler`: RFC 7807 `ProblemDetail`; 4 handlers; no internal detail leakage.

**Representative blocks (Phase 6)**
- Static `banner`: no extra code; handled by existing composition pipeline.
- Dynamic `products_list` (Salesforce Evergage):
  - `SalesforceProperties`, `SalesforceConfig` (dedicated connection pool, auth header).
  - `ProductsListAdapter`: `POST /api2/authevent/liverpool`; maps `productDataMapped` → preferred,
    `products` → fallback; guest guard (no `userId` → immediate `DynamicBlockServiceUnavailableException`).
  - `ProductsListQuery.userId` added; relay via `x-user-id` header.

**Cross-cutting hardening (Phase 7)**
- `MdcRequestContextFilter` (`@Order(HIGHEST_PRECEDENCE + 10)`): sets `requestId`, `correlationId`,
  `service`, `operation` in MDC per request; clears in `finally`. Runs before Spring Security.
- Actuator management port separated: `management.server.port: ${MANAGEMENT_PORT:8081}`.
- `SecurityConfig`: added `.requestMatchers("/actuator/**").denyAll()` on main port as
  belt-and-suspenders.
- `GET /home` `path` param hardened with `@Size(max=256)`.
- `requestId` echo in both controllers now reads from `MDC.get("requestId")` — single source of truth.

### Platform notes
- Spring Boot 4.1 uses Jackson 3 (`tools.jackson.*` packages). All Jackson imports migrated.
- No `spring-boot-starter-aop` in Boot 4.1 BOM → Resilience4j used programmatically.
- springdoc-openapi has no verified Boot 4.1 release → OpenApiConfig deferred.
