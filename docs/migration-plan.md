# ms-home Migration Plan (Phased)

> Home page composition microservice. Clean Hexagonal (Ports & Adapters) rebuild guided by the
> `java-code-quality` and `logger-handler` skills. The legacy NestJS `digital_bff` is **read-only
> reference** for external integration contracts only (Rule 19) — no code is copied from it.

## Locked decisions
1. **CMS access** via the existing `content-service` HTTP proxy (`GET /content/{type}/{locale}/{id}`,
   header `x-brand-id` + `-PREVIEW`) — not the Contentstack SDK.
2. **Scope** = Hexagonal foundation + **2 representative blocks** (one static, one dynamic).
3. **Modular topology (Rule 18)**: `GET /home` resolves layout/ordering only — static blocks fully
   resolved, dynamic blocks returned as **placeholders** (id, type, resolution endpoint, fallback,
   featureFlagId, status). **Independent per-block endpoints** resolve dynamic detail, each
   independently toggleable and observable.
4. **Resilience**: circuit breaker on every endpoint + outbound call, **no retries**, **5% failure-rate
   threshold**; open-state fallback returns a custom `SERVICE_UNAVAILABLE` ProblemDetail.
5. **Three distinct block signals** so the frontend always knows *why* a block isn't rendering:
   `BLOCK_DISABLED` (runtime flag off) · `BLOCK_SERVICE_UNAVAILABLE` (backing endpoint unhealthy) ·
   `SERVICE_UNAVAILABLE` (circuit breaker open).
6. **Security is cross-cutting** — applied in every phase (input validation, stateless auth from
   validated upstream token, secure headers, secrets via env, minimal downstream header allow-list,
   restricted actuator, dependency scanning).

## Current status — snapshot 2026-06-20

| Phase | State | Notes |
|---|---|---|
| 0 — Foundation | ✅ Complete | pom + yaml + 4 profiles compile cleanly. |
| 1 — Config | ✅ Complete (1 deferred) | Properties, Resilience4jConfig, RestClientConfig + interceptor, SecurityConfig done & compiling. **OpenApiConfig deferred** — springdoc has no verified Spring Boot 4.1 release. |
| 2 — Domain | ✅ Complete | All domain sources written and compiling cleanly. |
| 3 — Outbound adapters | ✅ Complete | ContentServiceClient (ContentPort + CB), SessionContextAdapter, FeatureFlagAdapter, StaticBlockCacheAdapter (Redis L2 + Caffeine L1). Also added: StaticBlockCachePort, HomeProperties, CacheConfig, cache TTL fields in ContentstackProperties. Compiles clean. |
| 4 — Application use cases | ⬜ Pending | GetHomePageService, ProductsListResolveService (+ DomainBeansConfig to wire the pure composition service). |
| 5 — Inbound REST | ⬜ Pending | HomeController, ProductsListResolveController, DTOs, mappers, RestControllerAdvice. |
| 6 — Representative blocks | ⬜ Pending | Static banner (cached) + dynamic products_list (placeholder + endpoint + CB). |
| 7 — Cross-cutting hardening | ⬜ Pending | Final security/logging/observability pass. |
| 8 — Docs | 🟡 Partial | This migration-plan.md exists; architecture/decisions/integrations/deployment/changelog/error-handling not yet written. |
| 9 — Tests & verify | ⬜ Pending | Unit/WireMock/Testcontainers/CB tests; `./mvnw clean verify`. |

**Files written so far (Phases 0–3):**
- Config: `config/{ContentstackProperties,ResilienceProperties,Resilience4jConfig,OutboundLoggingInterceptor,RestClientConfig,SecurityConfig,HomeProperties,CacheConfig}.java`
- Domain models: `domain/model/home/*` (BlockKind, BlockType, AudienceFilter, DynamicBlockStatus, SessionContext, HomeBlock, StaticBlock, DynamicPlaceholder, HomePage, HomePageQuery, BlockResolution, BlockResolutionCatalog), `domain/model/content/*` (ContentQuery, BlockDefinition, HomeDefinition), `domain/model/block/productslist/*` (ProductsListQuery, ProductItem, ProductsListResolution)
- Domain errors: `domain/error/*` (ErrorCategory, ErrorCodes, HomeException, ValidationException, HomeDefinitionNotFoundException, ContentServiceUnavailableException, DynamicBlockServiceUnavailableException, ServiceUnavailableException)
- Ports: `domain/port/inbound/*` (GetHomePageUseCase, ResolveProductsListUseCase), `domain/port/outbound/*` (ContentPort, SessionContextPort, FeatureFlagPort, ProductsListPort, **StaticBlockCachePort**)
- Domain service: `domain/service/HomeCompositionService.java`
- Adapters: `adapter/outbound/contentstack/ContentServiceClient.java`, `adapter/outbound/session/SessionContextAdapter.java`, `adapter/outbound/featureflag/FeatureFlagAdapter.java`, `adapter/outbound/redis/StaticBlockCacheAdapter.java`
- App: `MsHomeApplication` annotated with `@ConfigurationPropertiesScan`.

**Immediate next step:** Phase 4 — application use cases (`GetHomePageService`, `ProductsListResolveService`, `DomainBeansConfig`).

## Platform notes (discovered during build)
- Spring Boot **4.1.0** / Java 21. Module naming differs from Boot 3.x (`spring-boot-starter-webmvc`,
  `spring-boot-starter-restclient`, `spring-boot-micrometer-tracing-brave`).
- No `spring-boot-starter-aop` and no managed `micrometer-registry-prometheus` in the 4.1 BOM →
  **Resilience4j is used programmatically (core lib)**, not via the Spring-AOP annotation starter.
  Prometheus export deferred; Actuator metrics + Micrometer tracing cover core observability for now.

---

## Phases & tracking

### Phase 0 — Foundation ✅
- [x] `pom.xml`: drop unused MongoDB; add `spring-boot-starter-validation`, Caffeine,
      `resilience4j-circuitbreaker` + `resilience4j-micrometer` (core, programmatic).
- [x] `application.yaml` + `application-{dev,qa,staging,prod}.yaml`: virtual threads, graceful
      shutdown, ECS JSON logging, actuator health probes, content-service + circuit-breaker +
      feature-flag config (all env-overridable, no secrets in code).
- [ ] Test deps (WireMock, Testcontainers) — added in Phase 8.

### Phase 1 — Config layer
- [ ] `ContentstackProperties` (`@ConfigurationProperties(prefix="content-service")`).
- [ ] `ResilienceProperties` + `Resilience4jConfig` (CircuitBreakerRegistry: 5% threshold, no retries).
- [ ] `RestClientConfig`: pooled `RestClient` + outbound logging interceptor (Rule 10 — requestId/
      correlationId/latency/status, DEBUG cURL, mask Authorization/cookies/tokens).
- [ ] `SecurityConfig`: stateless, secure response headers, restricted actuator, Swagger non-prod.
- [ ] `OpenApiConfig` (deferred/optional pending springdoc↔Boot4 compatibility — see Phase 8 note).

### Phase 2 — Domain layer (no Spring; records, sealed interfaces, pure logic) ✅
- [x] `domain/model/home`: `HomePage`, `HomeBlock` (sealed) + `StaticBlock`/`DynamicPlaceholder`,
      `BlockType`/`BlockKind`, `AudienceFilter`, `DynamicBlockStatus`, `SessionContext`, `HomePageQuery`,
      `BlockResolution`/`BlockResolutionCatalog`.
- [x] `domain/model/content`: `ContentQuery`, `BlockDefinition`, `HomeDefinition` (raw CMS contract).
- [x] `domain/model/block/productslist`: `ProductsListQuery`, `ProductItem`, `ProductsListResolution`.
- [x] `domain/error`: `HomeException` base (errorCode/category/status/message/detail/retryable/cause),
      `ErrorCategory`, `ErrorCodes`, and concrete exceptions (Validation, NotFound, ContentServiceUnavailable,
      DynamicBlockServiceUnavailable, ServiceUnavailable).
- [x] `domain/port/inbound`: `GetHomePageUseCase`, `ResolveProductsListUseCase`.
- [x] `domain/port/outbound`: `ContentPort`, `SessionContextPort`, `FeatureFlagPort`, `ProductsListPort`.
- [x] `domain/service`: `HomeCompositionService` (ordering, audience/channel visibility, static/dynamic
      classification, feature-flag-driven placeholder status). Pure — no Spring, no I/O.
- [x] `./mvnw compile` clean.

### Phase 3 — Outbound adapters ✅
- [x] `ContentServiceClient` — RestClient → content-service proxy; URL-encodes path via `UriComponentsBuilder`; maps `top_layout + layout + bottom_layout` → `HomeDefinition`; circuit breaker `content-service` (5% threshold, no retries); 404→`HomeDefinitionNotFoundException`, open CB→`ServiceUnavailableException`, I/O→`ContentServiceUnavailableException`.
- [x] `SessionContextAdapter` — `@RequestScope` bean; reads `x-authenticated`, `x-brand-id`, `x-channel`, `x-locale` headers set by the upstream API gateway; safe defaults.
- [x] `FeatureFlagAdapter` — maps `FeatureFlagPort.isEnabled(flagId)` to `home.feature-flags.*` config map; unknown flag defaults to false.
- [x] `StaticBlockCacheAdapter` — `StaticBlockCachePort`; L1 Caffeine (configured via `CacheConfig`); L2 Redis (`StringRedisTemplate` + Jackson 3 `ObjectMapper`); key `home:def:{brand}:{locale}:{path}:{preview}`; Redis errors on read/write are defensive (warn + continue).
- [x] Added: `domain/port/outbound/StaticBlockCachePort.java`, `config/HomeProperties.java`, `config/CacheConfig.java`; updated `ContentstackProperties` with `homeContentType`, `homeEntryId`, `cacheTtl`, `l1CacheTtl`; updated `application.yaml`.
- [x] **Platform note:** Spring Boot 4.1 ships Jackson 3 (`tools.jackson.*` packages); fixed imports accordingly.
- [x] `./mvnw compile` clean, no warnings.

### Phase 4 — Application use cases
- [ ] `GetHomePageService` (@Service) — orchestrates composition.
- [ ] `ProductsListResolveService` (@Service) — dynamic block resolution behind its own circuit breaker.

### Phase 5 — Inbound REST + error handling
- [ ] `HomeController` `GET /home` (layout only, SpringDoc-annotated).
- [ ] `ProductsListResolveController` `GET /home/blocks/products-list/{blockId}`.
- [ ] DTOs + mappers (domain ↔ dto).
- [ ] Single thin `@RestControllerAdvice` → `ProblemDetail` (the only error infra; semantics live on exceptions).

### Phase 6 — Two representative blocks
- [ ] Static `banner` block: resolved from Contentstack, cached, owns its records/use case/rules.
- [ ] Dynamic `products_list` (salesforce): placeholder in `/home` + independent endpoint + circuit-breaker fallback.

### Phase 7 — Cross-cutting hardening
- [ ] Security review across all endpoints (validation, headers, actuator, secrets).
- [ ] Logging strategy (log-once at highest layer, context fields, no PII/secrets).
- [ ] Observability (MDC fields, health, metrics).

### Phase 8 — Docs
- [ ] `architecture.md`, `decisions.md`, `integrations.md`, `deployment.md`, `changelog.md`, `error-handling.md`.

### Phase 9 — Tests & verify
- [ ] Unit: `HomeCompositionService`, mappers (no Spring).
- [ ] WireMock contract test: `ContentServiceClient`.
- [ ] `@SpringBootTest` slice: `HomeController` (guest/auth/flag-off/down scenarios).
- [ ] Testcontainers Redis IT: `StaticBlockCacheAdapter`.
- [ ] Circuit-breaker test: >5% failures opens breaker, no retries, fallback ProblemDetail.
- [ ] `./mvnw clean verify` green.

---

## Reference contracts (from digital_bff, read-only)
- Contentstack proxy: `GET /content/{contentType}/{locale}/{id}`, header `x-brand-id: {brand}` (+`-PREVIEW`
  when request has `x-preview`). Source: `libs/providers/src/providers/content.provider.ts`.
- Response: `template { _content_type_uid, layout[], top_layout[], bottom_layout[], header, footer }` + `globalData`.
  Legacy renames `layout→blocks`, `top_layout→top_content`, `bottom_layout→bottom_content`.
- Block fields: `_content_type_uid`, `_uid`, optional `audience_filter` (`logged|guest|all`), `enable_on_web/apps`.
- Session: guest = anonymous token; ms-home needs only the login/guest boolean.

## Block-addition template (remaining ~9 blocks, later)
New use case + new adapter (or placeholder mapping) + Spring bean + Contentstack `_content_type_uid`
mapping. **No edits to existing blocks** (Open/Closed).
