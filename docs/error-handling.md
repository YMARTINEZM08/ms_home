# ms-home — Error Handling

## Design principles

- **One translation point.** `GlobalExceptionHandler` (`@RestControllerAdvice`) is the only place
  that converts domain exceptions to HTTP responses. Controllers never catch domain exceptions.
- **Self-describing exceptions.** Every `HomeException` carries its HTTP status, error code,
  category, and retryability — the handler adds no branching logic.
- **Two levels of detail.** `getMessage()` is consumer-facing; `getDetail()` is developer-facing.
  Only `getMessage()` is included in API responses.
- **RFC 7807 ProblemDetail.** All error responses follow the Problem Details for HTTP APIs standard
  (Spring 6 native `ProblemDetail`).
- **No internal detail leakage.** 5xx responses never expose stack traces, internal paths, or
  infrastructure topology to clients.
- **Log-once at the highest layer.** The handler logs each error once. Domain services and adapters
  throw without logging (except for cache/infrastructure warnings that are intentionally swallowed).

---

## Exception hierarchy

```
HomeException  (abstract)
├── ValidationException               — 400; VALIDATION; not retryable
├── HomeDefinitionNotFoundException   — 404; RESOURCE_NOT_FOUND; not retryable
├── ContentServiceUnavailableException — 502; EXTERNAL_SERVICE; retryable
├── DynamicBlockServiceUnavailableException — 502; EXTERNAL_SERVICE; retryable; adds blockId/blockType
└── ServiceUnavailableException        — 503; INFRASTRUCTURE; retryable (circuit breaker open)
```

`HandlerMethodValidationException` (Spring 6) is also handled for `@NotBlank`/`@Size` violations
on path and query parameters.

---

## Error codes

Error codes are stable machine-readable strings surfaced in the `errorCode` ProblemDetail property.
Clients should switch on `errorCode`, not HTTP status, for precise behaviour.

| Error code | HTTP | Category | Retryable | When raised |
|---|---|---|---|---|
| `VALIDATION_ERROR` | 400 | VALIDATION | No | Path/query param fails `@NotBlank`, `@Size` |
| `HOME_DEFINITION_NOT_FOUND` | 404 | RESOURCE_NOT_FOUND | No | content-service returns 404 |
| `CONTENT_SERVICE_UNAVAILABLE` | 502 | EXTERNAL_SERVICE | Yes | content-service I/O failure or 4xx/5xx |
| `BLOCK_SERVICE_UNAVAILABLE` | 502 | EXTERNAL_SERVICE | Yes | Salesforce call failure; or userId absent |
| `SERVICE_UNAVAILABLE` | 503 | INFRASTRUCTURE | Yes | Circuit breaker open (content-service or Salesforce) |
| `UNEXPECTED_ERROR` | 500 | UNEXPECTED | No | Unhandled exception (catch-all) |

---

## ProblemDetail response shape

All error responses use the RFC 7807 `application/problem+json` content type.

### Base shape (all domain errors)

```json
{
  "type": "about:blank",
  "title": "CONTENT_SERVICE_UNAVAILABLE",
  "status": 502,
  "detail": "Failed to reach content-service.",
  "instance": "/home",
  "errorCode": "CONTENT_SERVICE_UNAVAILABLE",
  "category": "EXTERNAL_SERVICE",
  "retryable": true
}
```

### DynamicBlockServiceUnavailableException — additional properties

```json
{
  "type": "about:blank",
  "title": "BLOCK_SERVICE_UNAVAILABLE",
  "status": 502,
  "detail": "Salesforce call failed: Connection refused.",
  "instance": "/home/blocks/products-list/uid-abc123",
  "errorCode": "BLOCK_SERVICE_UNAVAILABLE",
  "category": "EXTERNAL_SERVICE",
  "retryable": true,
  "blockId": "uid-abc123",
  "blockType": "products_list"
}
```

`blockId` and `blockType` let the frontend identify and fallback exactly the placeholder that
failed without affecting the rest of the page.

### Validation error

```json
{
  "type": "about:blank",
  "title": "VALIDATION_ERROR",
  "status": 400,
  "detail": "Request validation failed. Check the supplied parameters.",
  "instance": "/home/blocks/products-list/",
  "errorCode": "VALIDATION_ERROR",
  "retryable": false
}
```

### Unexpected error (catch-all)

```json
{
  "type": "about:blank",
  "title": "UNEXPECTED_ERROR",
  "status": 500,
  "detail": "An unexpected error occurred. Please try again later.",
  "instance": "/home",
  "errorCode": "UNEXPECTED_ERROR",
  "retryable": false
}
```

No internal detail is ever included in 5xx responses.

---

## Circuit-breaker error states

| State | Client experience |
|---|---|
| **Closed** (normal) | Calls go through; failures counted. |
| **Open** (threshold exceeded) | Calls short-circuited immediately; `ServiceUnavailableException` → HTTP 503. |
| **Half-open** (probing) | 5 test calls allowed; success → closed; failure → open again. |

The circuit breaker for `GET /home` is named `"content-service"`.  
The circuit breaker for `GET /home/blocks/products-list/{blockId}` is named `"products-list-salesforce"`.

Both share the same tuning defaults (5% threshold, 50-call window, 30 s open wait) but are
independent — a Salesforce outage does not affect the content-service breaker.

---

## Three dynamic-block signals

The frontend receives one of three distinct signals for each dynamic placeholder:

| Signal | HTTP | `errorCode` / block `status` | Meaning |
|---|---|---|---|
| `BLOCK_DISABLED` | 200 (in placeholder) | `BLOCK_DISABLED` | Feature flag is off; render fallback |
| `BLOCK_SERVICE_UNAVAILABLE` | 502 | `BLOCK_SERVICE_UNAVAILABLE` | Backing service failed; render fallback |
| `SERVICE_UNAVAILABLE` | 503 | `SERVICE_UNAVAILABLE` | Circuit breaker is open; retry later |

`BLOCK_DISABLED` never triggers an HTTP error — it is returned inline in the `DynamicPlaceholder`
on the `GET /home` response (the `status` field of the placeholder is `BLOCK_DISABLED`).

---

## Logging conventions

| Level | When |
|---|---|
| `WARN` | Expected degradation: dynamic block unavailable, validation failure, cache read error |
| `ERROR` | Unexpected 5xx, unhandled exception, critical infrastructure failure |

All log lines emitted by `GlobalExceptionHandler` include `uri`, `errorCode`, and `status` as
structured key-value pairs (ECS format). The `requestId` and `correlationId` are always present
via MDC.

---

## Troubleshooting

**GET /home returns 503 with `SERVICE_UNAVAILABLE`**  
→ The content-service circuit breaker is open. Check `resilience4j.circuitbreaker.state` in
  Actuator metrics. Content-service may be unhealthy.

**GET /home returns 502 with `CONTENT_SERVICE_UNAVAILABLE`**  
→ content-service is reachable but returning errors. Check content-service logs.
  The breaker may trip within the next call window.

**GET /home/blocks/products-list/{blockId} returns 502 with `blockType: products_list`**  
→ Salesforce returned an error or `userId` was absent (guest session). Check that `x-user-id`
  is being forwarded by the API gateway for authenticated users.

**GET /home/blocks/products-list/{blockId} returns 400**  
→ `blockId` was blank or exceeded 128 characters.

**GET /home returns 200 but a block has `status: BLOCK_DISABLED`**  
→ The feature flag `products-list-salesforce` is off. Set `FF_PRODUCTS_LIST_SALESFORCE=true` or
  check `home.feature-flags` configuration.
