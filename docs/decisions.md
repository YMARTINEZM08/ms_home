# ms-home — Architectural Decision Records

Each ADR captures a decision that is not obvious from reading the code, the trade-off accepted, and
the consequences that follow from it. Decisions marked **Locked** must not be reversed without a new
ADR and team alignment.

---

## ADR-001 — CMS access via content-service proxy, not the Contentstack SDK

**Status:** Locked  
**Context:** The Home page definition lives in Contentstack. Two options: the Contentstack Java SDK
or an existing internal `content-service` HTTP proxy already used by other teams.

**Decision:** Access Contentstack exclusively through `GET /content/{contentType}/{locale}/{id}` on
the `content-service` proxy. The Contentstack SDK and its credentials never enter this service.

**Consequences:**
- The SDK is not on the classpath; no Contentstack-specific types in domain or adapters.
- `ContentServiceClient` is the only point that knows about the proxy URL, the `x-brand-id` header,
  and the preview suffix (`-PREVIEW`).
- If the proxy contract changes, only `ContentServiceClient` needs to change.
- Circuit-breaker failure counts against the proxy endpoint, not raw Contentstack — which is the
  right isolation boundary for this service.

---

## ADR-002 — Hexagonal Architecture (Ports & Adapters)

**Status:** Locked  
**Context:** The previous implementation mixed infrastructure and business logic. Testability,
independent deployability of infrastructure, and long-term maintainability all required a clear
layer boundary.

**Decision:** Strict Hexagonal Architecture. Domain code has zero Spring annotations. All
dependencies point inward. Adapters implement domain ports; the domain never references adapter
implementations.

**Consequences:**
- `HomeCompositionService` is a plain Java class; wired via `DomainBeansConfig`.
- All domain types are records or sealed interfaces (immutable by default).
- Unit tests for domain logic need no Spring context or mocks of infrastructure.
- Adding a new block type requires only additive changes (new port + adapter + controller); existing
  blocks are untouched (Open/Closed Principle).

---

## ADR-003 — Modular layout topology (placeholder pattern)

**Status:** Locked  
**Context:** The legacy BFF resolved all blocks — static and dynamic — in a single monolithic call.
One failing block degraded the entire page. Dynamic blocks (products list, personalised carousels)
are slow and session-dependent.

**Decision:** `GET /home` resolves layout ordering and static block content only. Dynamic blocks are
returned as *placeholders* (`DynamicPlaceholder`) containing the block id, type, a dedicated
resolution URL, a fallback payload, and a feature-flag id. The frontend calls each block's
resolution endpoint independently.

**Consequences:**
- A failing dynamic block cannot degrade the page layout or other blocks.
- Each block has its own circuit breaker, its own SLA, and its own observability surface.
- The frontend must implement per-placeholder loading; this is a frontend contract change.
- Three distinct signals tell the frontend *why* a block is not rendering:
  - `BLOCK_DISABLED` — feature flag is off at runtime.
  - `BLOCK_SERVICE_UNAVAILABLE` — the backing service returned an error (HTTP 502).
  - `SERVICE_UNAVAILABLE` — the circuit breaker is open (HTTP 503).

---

## ADR-004 — Circuit breaker with 5% failure-rate threshold; no retries

**Status:** Locked  
**Context:** Both content-service and Salesforce Evergage can be transiently unavailable. Retries
amplify load on an already-failing system; a low threshold detects issues early.

**Decision:** Every outbound integration is protected by a Resilience4j circuit breaker. Failure
rate threshold: 5% (over a 50-call sliding window, minimum 20 calls). No retry policy anywhere.
Open state lasts 30 seconds; 5 probe calls in half-open before closing.

**Consequences:**
- Transient errors that exceed 5% trip the breaker; the service fails fast and returns a cached or
  placeholder response without queuing requests.
- Retries are omitted deliberately — the frontend is responsible for user-triggered retry.
- All CB tuning is externalised via `resilience.circuit-breaker.*` env vars so thresholds can be
  adjusted without redeployment.

---

## ADR-005 — Static block full resolution vs. dynamic block placeholder

**Status:** Locked  
**Context:** Some blocks (banners, editorial) are identical for all users of the same brand/locale.
Others (product carousels) are personalised and change per session.

**Decision:** The `HomeCompositionService` classifies each block by its `BlockType.kind()`:
- `STATIC` → full resolution inline; all fields from Contentstack included.
- `DYNAMIC` → placeholder only; actual content fetched by a dedicated per-block endpoint.

The classification is purely configuration-driven (`BlockType` enum + `BlockKind`).

**Consequences:**
- Unknown `_content_type_uid` values map to `BlockType.UNKNOWN` (kind=STATIC), so unrecognised
  CMS content is passed through unchanged rather than dropped.
- New block types only require updating `BlockType` and (for DYNAMIC) adding a resolution endpoint.

---

## ADR-006 — Two-tier cache: Caffeine L1 + Redis L2 for Home definitions

**Status:** Active  
**Context:** The CMS layout changes infrequently (editorial team updates; not per-request). Fetching
it on every request would add 100–200 ms of latency and unnecessary load on content-service.

**Decision:** `StaticBlockCacheAdapter` implements a write-through two-tier cache:
- **L1 (Caffeine):** in-process, default 30s TTL, max 200 entries. Absorbs hot traffic with
  sub-millisecond access.
- **L2 (Redis):** shared across instances, default 5 min TTL. Prevents cache stampede on pod
  restart; provides cross-instance consistency.

Cache key: `home:def:{brand}:{locale}:{path}:{preview}`. Preview content has its own cache entry
so live and preview never collide.

**Consequences:**
- Redis errors on read return empty (cache miss → origin fetch); on write they are logged and
  swallowed. A Redis outage degrades to origin-only; no hard dependency.
- Cache TTLs are env-configurable (`CONTENT_SERVICE_CACHE_TTL`, `CONTENT_SERVICE_L1_CACHE_TTL`).

---

## ADR-007 — Java 21 virtual threads (Project Loom)

**Status:** Active  
**Context:** Spring MVC is blocking-I/O by default. The service makes several sequential blocking
calls (Redis → content-service → Salesforce). Traditional thread-pool sizing is error-prone.

**Decision:** `spring.threads.virtual.enabled: true`. Spring MVC dispatches each request onto a
virtual thread managed by the JVM rather than a platform thread pool.

**Consequences:**
- No thread-pool sizing configuration required; virtual threads are cheap enough to create per
  request.
- MDC (thread-local) works correctly because each request stays on its own virtual thread without
  thread-hopping inside `OncePerRequestFilter`.
- Tomcat max connections (`server.tomcat.threads.max`) is no longer the bottleneck; the limit moves
  to OS file descriptors and outbound connection pools.

---

## ADR-008 — Programmatic Resilience4j (core library, not Spring AOP starter)

**Status:** Active  
**Context:** The Spring Boot 4.1 module layout does not ship a managed `resilience4j-spring-boot`
starter that compiles cleanly against Boot 4.x.

**Decision:** Use `resilience4j-circuitbreaker` (core, no AOP) and `resilience4j-micrometer`
directly. Circuit breakers are instantiated programmatically at each call site via
`CircuitBreakerRegistry`.

**Consequences:**
- No `@CircuitBreaker` annotation; each call site calls `cb.executeSupplier(...)` explicitly. This
  makes the protection visible and removes implicit AOP magic.
- `Resilience4jConfig` registers circuit-breaker metrics with Micrometer via
  `TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry)`.
- CB state and call metrics are available via `/actuator/metrics` (management port 8081).

---

## ADR-009 — Jackson 3 package names (Spring Boot 4.1)

**Status:** Active  
**Context:** Spring Boot 4.1 ships Jackson 3.x. Jackson 3 migrated from `com.fasterxml.jackson.*`
to `tools.jackson.*` package names. This is a breaking change from Jackson 2.x.

**Decision:** All Jackson imports use `tools.jackson.*`. Code never references `com.fasterxml.*`.
`JacksonException` replaces `JsonProcessingException` as the base checked exception.

**Consequences:**
- `StaticBlockCacheAdapter` imports `tools.jackson.core.JacksonException` and
  `tools.jackson.databind.ObjectMapper`.
- Any future Jackson usage (custom serializers, modules) must use `tools.jackson.*`.

---

## ADR-011 — Channel and audience filtering: template-based routing, field-based fallback

**Status:** Active  
**Context:** `HomeCompositionService.isVisible()` applies two guards before including a block:
1. **Audience filter** — `audience_filter` field (`"logged"` / `"guest"` / `"all"`).
2. **Channel visibility** — `enable_on_web` / `enable_on_apps` boolean fields.

Gap analysis (Phase 13) of the live BFF response against the production Contentstack home template
revealed that **none of these fields appear on any of the 16 home template blocks**. They appear
only in `globalData` entries (value `"all"`). Additionally, the BFF returned `container_guest` for
an authenticated session, confirming it is not server-filtered.

**Decision:** Channel and audience routing for the home page is **template-based**, not
field-based: the upstream caller supplies a different `path` query parameter for each channel
(`/tienda/home` for web, a separate path for apps), and Contentstack serves the matching template.
The server-side filtering guards in `HomeCompositionService.isVisible()` are **preserved**:

- **Absence of fields defaults to permissive** — `enable_on_web`/`enable_on_apps` absent →
  both default to `true`; `audience_filter` absent → resolves to `ALL`. All production home blocks
  therefore pass visibility without being filtered.
- **Legacy content remains handled** — the three-section schema (`top_layout + layout +
  bottom_layout`) uses `_uid`, explicit channel flags, and `audience_filter`. The guards protect
  this content class without any code branching.
- **`container_guest`** is returned to all sessions; rendering for guest-only is a
  **frontend concern** driven by the session context, not a server filter from ms-home. The block
  type name is a CMS editorial convention, not a CMS-enforced audience restriction.

**Consequences:**
- `HomeCompositionService.isVisible()` is functionally a no-op for current production home blocks
  (all fields absent → all defaults pass). This is intentional and correct.
- If the CMS team ever adds explicit `audience_filter` or channel flags to home blocks, the
  filtering activates automatically with no code change.
- If a future template-based channel routing change is needed (e.g., the `path` convention
  changes), only `ContentServiceClient.buildUri()` needs updating.
- `container_guest` behaviour change (make it server-filtered) requires the CMS to add
  `audience_filter: "guest"` to the block, or a new ADR overriding this decision.

---

## ADR-010 — Actuator on a dedicated management port

**Status:** Active  
**Context:** Actuator endpoints (`/actuator/health`, `/actuator/metrics`, `/actuator/loggers`) must
be accessible to the Kubernetes control plane but must not be reachable via the API gateway exposed
to the public internet.

**Decision:** `management.server.port: ${MANAGEMENT_PORT:8081}`. Actuator runs on its own embedded
listener (default 8081). The API gateway exposes only port 8080. K8s liveness/readiness probes
target port 8081 directly.

`SecurityConfig` also contains an explicit `.requestMatchers("/actuator/**").denyAll()` rule on
the main port as a belt-and-suspenders guard.

**Consequences:**
- Actuator is unreachable from the public internet regardless of gateway misconfiguration.
- K8s probes: liveness → `http://localhost:8081/actuator/health/liveness`; readiness →
  `http://localhost:8081/actuator/health/readiness`.
- In local development: actuator is on port 8081 and the content-service default URL has been
  moved to port 8082 to avoid a localhost collision.

---

## ADR-012 — Header / footer served via `GET /global-data`, not a separate endpoint

**Status:** Active  
**Context:** The frontend needs the global navigation header and footer on every page. Three options
were evaluated:
1. Bundle `header` and `footer` in the `GET /home` response.
2. Serve from a dedicated `GET /navigation` endpoint with its own cache.
3. Extend the existing `GET /global-data` endpoint to include `header` and `footer`.

**Decision:** Option 3 — extend `GET /global-data`. Header and footer are session-independent,
site-wide data with the same 15-minute TTL semantics as feature flags, public variables, and themes.
Adding two more map fields to the existing `GlobalData` record keeps the fetch-and-cache path
unified: one CMS entry, one circuit breaker (`global-data`), one Caffeine L1 cache.

**Consequences:**
- `GlobalData` domain record gains `header: Map<String,Object>` and `footer: Map<String,Object>`.
- `GlobalDataClient` extracts `header` and `footer` using the same `extractMap()` helper as the
  other fields; absent keys default to `Map.of()` (never null).
- `GlobalDataResponse` DTO and `GlobalDataMapper` are extended in kind — no new classes.
- The frontend receives header/footer on the same call it already makes for feature flags, avoiding
  a second round-trip.
- Option 1 was rejected: bundling nav in `/home` couples navigation cache to home-page content TTL
  and forces every home-page request to carry nav payload even when nav hasn't changed.
- Option 2 was rejected: a separate `/navigation` endpoint is justified only if nav has a different
  TTL, circuit-breaker budget, or cache strategy — none of which apply here.
