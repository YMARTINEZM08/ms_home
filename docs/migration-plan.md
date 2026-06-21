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

> **Gap analysis vs. live BFF** — see [docs/gap-analysis.md](gap-analysis.md) for full findings
> from comparing ms-home against `GET /web-bff/content/page/es-mx/tienda/home`.  
> **Critical P0 blocker identified:** `ContentServiceClient` parses `template.top_layout + layout + bottom_layout`
> but production Contentstack home template uses `template.blocks[]` directly → zero blocks rendered.
> Phase 10 is the next gate.

| Phase | State | Notes |
|---|---|---|
| 0 — Foundation | ✅ Complete | pom + yaml + 4 profiles compile cleanly. |
| 1 — Config | ✅ Complete (1 deferred) | Properties, Resilience4jConfig, RestClientConfig + interceptor, SecurityConfig done & compiling. **OpenApiConfig deferred** — springdoc has no verified Spring Boot 4.1 release. |
| 2 — Domain | ✅ Complete | All domain sources written and compiling cleanly. |
| 3 — Outbound adapters | ✅ Complete | ContentServiceClient (ContentPort + CB), SessionContextAdapter, FeatureFlagAdapter, StaticBlockCacheAdapter (Redis L2 + Caffeine L1). Also added: StaticBlockCachePort, HomeProperties, CacheConfig, cache TTL fields in ContentstackProperties. Compiles clean. |
| 4 — Application use cases | ✅ Complete | GetHomePageService (cache-aside + composition), ProductsListResolveService (own CB `products-list-salesforce`), DomainBeansConfig, ProductsListAdapter stub. Compiles clean. |
| 5 — Inbound REST | ✅ Complete | HomeController, ProductsListResolveController, DTOs, HomePageMapper, ProductsListMapper, GlobalExceptionHandler. Compiles clean (60 files). |
| 6 — Representative blocks | ✅ Complete | Static banner: already handled by existing pipeline (no extra code). Dynamic products_list: SalesforceProperties, SalesforceConfig, real ProductsListAdapter (POST /api2/authevent/liverpool), x-user-id header relay, ProductsListQuery.userId added. Compiles clean (62 files). |
| 7 — Cross-cutting hardening | ✅ Complete | MdcRequestContextFilter (requestId/correlationId/service/operation MDC fields, runs before Spring Security); management port isolated to MANAGEMENT_PORT:8081; SecurityConfig denies /actuator/** on main port; path param @Size(max=256); requestId echo now reads from MDC (no duplication). Compiles clean (63 files). |
| 8 — Docs | ✅ Complete | architecture.md, decisions.md (10 ADRs), integrations.md, deployment.md, changelog.md, error-handling.md all written. |
| 9 — Tests & verify | ✅ Complete | 56 tests / 0 failures. |
| **10 — Fix CMS schema (P0)** | ⬜ Next | `template.blocks[]` vs `top_layout+layout+bottom_layout`; default channel flags; add 6 real BlockTypes. |
| 11 — Block content contracts | ⬜ | Per-type field mapping for all 6 production types. |
| 12 — SEO / page metadata | ⬜ | Add `pageTitle`, `url`, `seo{}` to `HomePageResponse`. |
| 13 — Channel/audience validation | ⬜ | Confirm CMS filtering mechanism with content team. |
| 14 — GlobalData endpoint | ⬜ | Feature flags, public_variables, themes. |
| 15 — Salesforce e2e | ⬜ | Find Salesforce block in CMS; validate full dynamic flow. |
| 16 — Header/footer strategy | ⬜ | Bundle vs. dedicated endpoint decision + implementation. | 56 tests across 7 test classes, 0 failures. `./mvnw clean verify` → BUILD SUCCESS. Key fixes: `@Qualifier("contentServiceRestClient")` on `ContentServiceClient`, `ConstraintViolationException` handler added to `GlobalExceptionHandler`, Spring Boot 4.1 package `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `lenient()` stubbing for L1-hit Redis test. |

**Files written so far (Phases 0–3):**
- Config: `config/{ContentstackProperties,ResilienceProperties,Resilience4jConfig,OutboundLoggingInterceptor,RestClientConfig,SecurityConfig,HomeProperties,CacheConfig}.java`
- Domain models: `domain/model/home/*` (BlockKind, BlockType, AudienceFilter, DynamicBlockStatus, SessionContext, HomeBlock, StaticBlock, DynamicPlaceholder, HomePage, HomePageQuery, BlockResolution, BlockResolutionCatalog), `domain/model/content/*` (ContentQuery, BlockDefinition, HomeDefinition), `domain/model/block/productslist/*` (ProductsListQuery, ProductItem, ProductsListResolution)
- Domain errors: `domain/error/*` (ErrorCategory, ErrorCodes, HomeException, ValidationException, HomeDefinitionNotFoundException, ContentServiceUnavailableException, DynamicBlockServiceUnavailableException, ServiceUnavailableException)
- Ports: `domain/port/inbound/*` (GetHomePageUseCase, ResolveProductsListUseCase), `domain/port/outbound/*` (ContentPort, SessionContextPort, FeatureFlagPort, ProductsListPort, **StaticBlockCachePort**)
- Domain service: `domain/service/HomeCompositionService.java`
- Adapters: `adapter/outbound/contentstack/ContentServiceClient.java`, `adapter/outbound/session/SessionContextAdapter.java`, `adapter/outbound/featureflag/FeatureFlagAdapter.java`, `adapter/outbound/redis/StaticBlockCacheAdapter.java`
- App: `MsHomeApplication` annotated with `@ConfigurationPropertiesScan`.

**Immediate next step:** Phase 7 — cross-cutting hardening (security, MDC logging, observability).

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

### Phase 4 — Application use cases ✅
- [x] `DomainBeansConfig` — wires `HomeCompositionService` (POJO, no Spring annotations) and `BlockResolutionCatalog` (built from `HomeProperties.blocks` + flag id constant `"products-list-salesforce"`). Single place to register new dynamic block types (Open/Closed).
- [x] `GetHomePageService` — cache-aside pattern: L1/L2 hit → compose; miss → `ContentPort.fetchHomeDefinition` → cache → compose. Brand derived from `query.session()` so it stays consistent across the request. Session pre-resolved by caller (inbound adapter).
- [x] `ProductsListResolveService` — CB `"products-list-salesforce"` wraps `ProductsListPort.fetch`; maps `CallNotPermittedException` → `ServiceUnavailableException`; maps unexpected errors → `DynamicBlockServiceUnavailableException`; re-throws the domain exception unchanged.
- [x] `ProductsListAdapter` (stub, `adapter/outbound/salesforce/`) — Phase 6 placeholder; satisfies `ProductsListPort` so Spring context boots; always throws `DynamicBlockServiceUnavailableException` to surface the "not yet wired" state without masking failures.
- [x] `./mvnw compile` clean (51 source files).

### Phase 5 — Inbound REST + error handling ✅
- [x] `HomeController` `GET /home` — reads session via `SessionContextPort`; preview from configurable header name (`ContentstackProperties.previewHeader`); echoes/generates `x-request-id`; `@Validated`.
- [x] `ProductsListResolveController` `GET /home/blocks/products-list/{blockId}` — `@NotBlank @Size(max=128)` on `blockId`; own `x-request-id` echo.
- [x] DTOs: `HomeBlockResponse` (flat record with `kind` discriminator), `HomePageResponse`, `ProductsListResponse`, `ProductItemResponse`.
- [x] Mappers: `HomePageMapper` (sealed pattern match → exhaustive conversion), `ProductsListMapper`.
- [x] `GlobalExceptionHandler` — 4 handlers: `DynamicBlockServiceUnavailableException` (502 + blockId/blockType props), `HomeException` (status from exception), `HandlerMethodValidationException` (400), `Exception` catch-all (500, no internal detail leaked).
- [x] `./mvnw compile` clean (60 source files).

### Phase 6 — Two representative blocks ✅
- [x] **Static `banner`** — no new code required. The existing pipeline already handles it: `BlockType.BANNER(STATIC)` is classified by `HomeCompositionService`, resolved inline from Contentstack content, carried as `StaticBlock`, cached in Redis L2 + Caffeine L1 via `StaticBlockCacheAdapter`, and serialised as `HomeBlockResponse(kind=STATIC, content=...)` by `HomePageMapper`.
- [x] **Dynamic `products_list` (Salesforce)** — replaced stub with real adapter:
  - `SalesforceProperties` — `baseUrl`, `actionsPath`, `authorizationValue` (env-only secret), `timeout`, `application`, `defaultCarouselAction`.
  - `SalesforceConfig` — dedicated `salesforceRestClient` bean (own connection pool, `Authorization` header, `OutboundLoggingInterceptor` with auth masking).
  - `ProductsListQuery` — extended with `userId` (ATG profile id from `x-user-id` header; null for guests).
  - `ProductsListResolveController` — reads `x-user-id` header; passes it in the query.
  - `ProductsListAdapter` — `POST /api2/authevent/liverpool` with `action/flags/source/user.attributes.ID_ATG1`; maps `campaignResponses[0].payload.{productDataMapped,products}` to `ProductItem(sku,title,price)`; guards against missing userId.
  - CB `"products-list-salesforce"` applied at use-case layer (Phase 4); adapter has no retry.
  - **Platform note:** `salesforce.authorization-value` defaults to empty string — must be set via `SALESFORCE_AUTHORIZATION` env var; never committed.
- [x] `./mvnw compile` clean (62 source files).

### Phase 7 — Cross-cutting hardening ✅
- [x] **MDC filter** — `MdcRequestContextFilter` (`@Order(HIGHEST_PRECEDENCE + 10)`) sets `requestId`
      (echoed or UUID-generated), `correlationId` (from `x-correlation-id` or falls back to requestId),
      `service` (bound to `spring.application.name`), `operation` (`METHOD /path`). Cleared in `finally`.
      `traceId`/`spanId` handled automatically by Micrometer Tracing + Brave `MDCScopeDecorator`.
- [x] **Request ID ownership moved to filter** — both `HomeController` and `ProductsListResolveController`
      now read `MDC.get("requestId")` for the response echo header instead of each having their own
      `resolveRequestId()` UUID-generation logic. Single source of truth, consistent log/header correlation.
- [x] **Security — actuator isolation** — `management.server.port: ${MANAGEMENT_PORT:8081}` separates
      actuator onto a dedicated port not exposed by the API gateway. K8s health probes target port 8081.
      `SecurityConfig` adds `.requestMatchers("/actuator/**").denyAll()` on the main port as
      belt-and-suspenders.
- [x] **Security — input validation** — `path` query param in `GET /home` hardened with `@Size(max=256)`.
      `blockId` path variable already constrained (`@NotBlank @Size(max=128)`) from Phase 5.
- [x] **Secrets** — confirmed no secrets in code; `SALESFORCE_AUTHORIZATION` default is empty string,
      `OutboundLoggingInterceptor` masks `Authorization`/cookies/tokens.
- [x] `./mvnw compile` clean (63 files).

### Phase 8 — Docs ✅
- [x] `architecture.md` — layers, package layout, two request-flow diagrams, block-addition template.
- [x] `decisions.md` — 10 ADRs: CMS proxy, Hexagonal, placeholder topology, CB/no-retry, static vs
      dynamic, two-tier cache, virtual threads, programmatic Resilience4j, Jackson 3, management port.
- [x] `integrations.md` — content-service (URL contract, headers, response structure, error handling),
      Salesforce (request body, response mapping, guest guard), Redis (key scheme, failure behaviour),
      upstream gateway (full inbound header table).
- [x] `deployment.md` — full env-var table (required + optional), K8s probe snippets, graceful
      shutdown, Docker Compose example, runtime log-level adjustment, CB metrics queries.
- [x] `changelog.md` — v0.1.0 entry covering all Phases 0–7 additions.
- [x] `error-handling.md` — exception hierarchy, error codes table, ProblemDetail shape (3 examples),
      circuit-breaker states, three dynamic-block signals, logging conventions, troubleshooting guide.

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
