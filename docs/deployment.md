# ms-home — Deployment Guide

## Ports

| Port | Purpose | Exposed by |
|---|---|---|
| `8080` | Application (API) | API Gateway / load balancer |
| `8081` | Management (Actuator health/metrics) | K8s probes only; not externally exposed |

Both ports are env-configurable. The management port default (8081) must not collide with other
services on the same host. The content-service default URL uses 8082 in local dev to avoid this.

---

## Environment variables

All configuration is externalised. No secrets are compiled into the binary.

### Required at runtime

| Variable | Description |
|---|---|
| `SALESFORCE_AUTHORIZATION` | `Authorization` header value for Salesforce Evergage. Typically `Basic <base64>`. **Never committed to source control.** |
| `CONTENT_SERVICE_BASE_URL` | Base URL of the internal content-service proxy (e.g., `http://content-service.internal:8080`). |
| `REDIS_HOST` | Redis hostname. |

### Optional — infrastructure

| Variable | Default | Description |
|---|---|---|
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_TIMEOUT` | `2s` | Redis socket timeout |
| `MANAGEMENT_PORT` | `8081` | Actuator management port (K8s probe target) |
| `LOG_LEVEL_APP` | `INFO` | Log level for `com.liverpool.ms_home` |

### Optional — content-service

| Variable | Default | Description |
|---|---|---|
| `CONTENT_SERVICE_BRAND` | `LP` | Default brand when `x-brand-id` header is absent |
| `CONTENT_SERVICE_CONNECT_TIMEOUT` | `2s` | Connect timeout for content-service calls |
| `CONTENT_SERVICE_READ_TIMEOUT` | `5s` | Read timeout for content-service calls |
| `CONTENT_SERVICE_HOME_TYPE` | `page` | Contentstack content type for the Home page |
| `CONTENT_SERVICE_HOME_ENTRY` | `home` | Contentstack entry id for the root Home definition |
| `CONTENT_SERVICE_CACHE_TTL` | `5m` | Redis L2 TTL for cached Home definitions |
| `CONTENT_SERVICE_L1_CACHE_TTL` | `30s` | Caffeine L1 TTL for cached Home definitions |

### Optional — Salesforce

| Variable | Default | Description |
|---|---|---|
| `SALESFORCE_BASE_URL` | `https://serviciosliverpoolsadecv.us-4.evergage.com` | Salesforce Evergage base URL |
| `SALESFORCE_ACTIONS_PATH` | `/api2/authevent/liverpool` | Salesforce actions endpoint path |
| `SALESFORCE_TIMEOUT` | `4s` | Read timeout for Salesforce calls (connect is fixed at 2 s) |
| `SALESFORCE_APPLICATION` | `Web` | Application name sent in the Salesforce request body |
| `SALESFORCE_DEFAULT_CAROUSEL_ACTION` | `CMSOfertasIncreibles` | Default carousel action name |

### Optional — circuit breakers

| Variable | Default | Description |
|---|---|---|
| `CB_FAILURE_RATE_THRESHOLD` | `5` | Failure rate (%) that opens a breaker |
| `CB_SLIDING_WINDOW_SIZE` | `50` | Number of calls in the sliding window |
| `CB_MIN_CALLS` | `20` | Minimum calls before the rate is evaluated |
| `CB_WAIT_OPEN` | `30s` | Time the breaker stays open before probing |
| `CB_HALF_OPEN_CALLS` | `5` | Probe calls allowed in the half-open state |

### Optional — feature flags

| Variable | Default | Description |
|---|---|---|
| `FF_PRODUCTS_LIST_SALESFORCE` | `true` | Enables the products-list Salesforce block |
| `SWAGGER_ENABLED` | `true` | Enables Swagger UI (set to `false` in production) |

---

## Kubernetes configuration

### Deployment manifest snippets

```yaml
env:
  - name: SALESFORCE_AUTHORIZATION
    valueFrom:
      secretKeyRef:
        name: ms-home-secrets
        key: salesforce-authorization
  - name: CONTENT_SERVICE_BASE_URL
    value: "http://content-service.internal:8080"
  - name: REDIS_HOST
    value: "redis.internal"
  - name: SWAGGER_ENABLED
    value: "false"

ports:
  - name: http
    containerPort: 8080
  - name: management
    containerPort: 8081
```

### Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 3
```

### Graceful shutdown

`server.shutdown: graceful` is set. Add a `preStop` hook to ensure K8s stops routing traffic
before SIGTERM is sent to the JVM:

```yaml
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 5"]
```

---

## Local development

```bash
export SALESFORCE_AUTHORIZATION="Basic <your-value>"
export CONTENT_SERVICE_BASE_URL="http://localhost:8082"
export REDIS_HOST="localhost"

./mvnw spring-boot:run
# App:        http://localhost:8080
# Actuator:   http://localhost:8081/actuator/health
```

### Docker Compose (local)

```yaml
services:
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  content-service:
    image: content-service:latest
    ports: ["8082:8080"]

  ms-home:
    build: .
    ports:
      - "8080:8080"
      - "8081:8081"
    environment:
      REDIS_HOST: redis
      CONTENT_SERVICE_BASE_URL: http://content-service:8080
      SALESFORCE_AUTHORIZATION: "${SALESFORCE_AUTHORIZATION}"
    depends_on:
      - redis
      - content-service
```

---

## Logging

Logs are emitted as ECS-structured JSON (`logging.structured.format.console: ecs`). Every log line
carries the following MDC fields set by `MdcRequestContextFilter`:

| MDC field | Source |
|---|---|
| `requestId` | `x-request-id` header from upstream, or generated UUID |
| `correlationId` | `x-correlation-id` header from upstream, or falls back to `requestId` |
| `service` | `spring.application.name` constant (`ms-home`) |
| `operation` | `HTTP_METHOD /path` (e.g., `GET /home`) |
| `traceId` | Micrometer Tracing + Brave (automatic) |
| `spanId` | Micrometer Tracing + Brave (automatic) |

### Adjusting log level at runtime

```bash
# Enable DEBUG for ms-home package without restarting
curl -X POST http://localhost:8081/actuator/loggers/com.liverpool.ms_home \
  -H 'Content-Type: application/json' \
  -d '{"configuredLevel": "DEBUG"}'
```

DEBUG enables masked cURL logging for all outbound `RestClient` calls (via `OutboundLoggingInterceptor`).

---

## Observability — circuit-breaker metrics

Circuit-breaker state and call counts are exposed via Micrometer:

```
GET http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:content-service
GET http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.calls?tag=name:products-list-salesforce
```

States: `0` = CLOSED (normal), `1` = OPEN (tripped), `2` = HALF_OPEN (probing).
