# Gateway Route Integration Handoff Contract — Issue #106 to Issue #127

**Producer Issue:** [N10-P03] Implement Gateway business-edge authentication and abuse policy ([#106](https://github.com/Suthankan1/swiftdrop/issues/106))  
**Producer PR:** [feat(gateway): implement business-edge authentication and abuse policy](https://github.com/Suthankan1/swiftdrop/pull/new/feat/n10-p03-gateway-edge-security)  
**Producer Owner:** @Suthankan1 (Platform / Platform)  
**Consumer Issue:** [N10-P03A] Integrate approved Gateway business routes and upstream error behavior ([#127](https://github.com/Suthankan1/swiftdrop/issues/127))  
**Consumer Owner:** @Gajanthan23 (Platform / Gateway Route Integration)  
**Date:** October 25, 2026  
**Architecture Baseline:** SwiftDrop v1.3  

---

## 1. Overview & Ownership Boundaries

This handoff provides the implementation-ready security policy foundation for Spring Cloud Gateway WebFlux. Issue #106 implements and validates the edge authentication, Keycloak role gates, merchant machine credential validation, case-insensitive header sanitization, development CORS, bounded rate-limiting, payload sizing, and SSE connection concurrency policies.

### Division of Responsibility

| Capability | Issue #106 (Suthankan1) — COMPLETED | Issue #127 (Gajanthan23) — NEXT |
|---|---|---|
| **Security Configuration** | Reactive Spring Security WebFlux chain, entry points, access denied handlers | Does not alter core security configuration or weaken policy |
| **Edge JWT Validation** | Signature, issuer, audience (`swiftdrop-api`), exp, Keycloak role converter | Leverages existing reactive security context for routed requests |
| **Merchant Credentials** | `X-API-Key` filter, pre-auth/auth tokens, syntax & scope validator | Maps `POST /api/v1/merchant/parcels` route to Delivery service |
| **Header Sanitization** | Case-insensitive stripping of caller identity & internal gRPC tokens | Preserves sanitized request pipeline for all business routes |
| **Abuse & Rate Limits** | Public IP, merchant identity, unauth buckets with HTTP 429 Retry-After | Connects business routes through the global filter chain |
| **Payload Limits** | Content-Length checks (64 KB quotes, 256 KB parcels, 5 MB proofs) | Ensures upstream routes do not circumvent payload filters |
| **SSE Connection Policy** | Max concurrent connections (5 public, 10 authenticated), zero buffering | Wires streaming routes with non-buffering downstream forwarding |
| **Route Destination Wiring** | Synthetic test route fixtures only (`TestRoutesConfig`) | Production route definitions, service URL configs, path predicates |
| **Upstream Error Behavior** | Edge Problem Detail handlers for 401, 403, 413, 429 | Upstream 502/503/504 timeouts, circuit breakers, service down mappings |

---

## 2. Security Components & Hooks Ready for Route Integration

Downstream route integration in Issue #127 must integrate with and preserve the following Spring components:

1. **`com.swiftdrop.apigateway.security.SecurityConfig`**:
   Configures the reactive WebFlux security filter chain (`SecurityWebFilterChain`). All routes mapped by Issue #127 will pass through this chain. The default-deny boundary (`.anyExchange().denyAll()`) remains active; all newly routed paths must match one of the explicit pathMatcher gates.

2. **`com.swiftdrop.apigateway.security.KeycloakReactiveJwtAuthenticationConverter`**:
   Extracts Keycloak realm and client roles into Spring Security `ROLE_*` authorities (`ROLE_CUSTOMER`, `ROLE_MERCHANT`, `ROLE_COURIER`, `ROLE_DISPATCHER`, `ROLE_ADMIN`). Route authorization relies directly on these authorities.

3. **`com.swiftdrop.apigateway.security.merchant.MerchantApiKeyAuthenticationFilter`**:
   An `AuthenticationWebFilter` attached at `SecurityWebFiltersOrder.AUTHENTICATION`. Scoped strictly to `POST /api/v1/merchant/parcels`. Verifies candidate `X-API-Key` headers against `MerchantApiKeyValidator`. On success, authenticates the request with `ROLE_MERCHANT` authority and attaches `MerchantPrincipal` to the reactive security context.

4. **`com.swiftdrop.apigateway.filter.StripUntrustedHeadersGatewayFilter`**:
   High-precedence GlobalFilter (`Ordered.HIGHEST_PRECEDENCE + 1`). Strips case-insensitively:
   - Client identity headers: `X-User-Id`, `X-Roles`, `X-Tenant-Id`, `X-Keycloak-Sub`, `X-User-Email`
   - Internal gRPC headers (Issue #100): `x-swiftdrop-service-token`, `x-swiftdrop-actor-sub`, `x-swiftdrop-actor-email`, `x-swiftdrop-actor-roles`, `x-swiftdrop-actor-tenant-id`, `x-swiftdrop-correlation-id`
   - Strictly preserves: `Authorization: Bearer <token>` and `Idempotency-Key`

5. **`com.swiftdrop.apigateway.filter.PayloadSizeLimitGatewayFilter`**:
   High-precedence GlobalFilter (`Ordered.HIGHEST_PRECEDENCE + 10`). Inspects `Content-Length` on `POST`, `PUT`, `PATCH`:
   - `/api/v1/quotes`: 64 KB (65,536 bytes)
   - `/api/v1/parcels/*/proof`: 5 MB (5,242,880 bytes)
   - All other routes: 256 KB (262,144 bytes)
   - Rejects violations with HTTP 413 `Payload Too Large` (RFC 7807 Problem Detail).

6. **`com.swiftdrop.apigateway.filter.RateLimitingGatewayFilter`**:
   GlobalFilter (`Ordered.HIGHEST_PRECEDENCE + 20`). Uses bounded in-memory token bucket (`InMemoryRateLimiter`):
   - Public IP bucket: 60 req/min, burst 10
   - Verified merchant identity bucket (`merch:<keyPrefix>`): 120 req/min, burst 20
   - Unauthenticated merchant probe bucket: 10 req/min, burst 5
   - Rejects violations with HTTP 429 `Too Many Requests` and standard `Retry-After` header.

7. **`com.swiftdrop.apigateway.filter.SseConnectionPolicyGatewayFilter`**:
   GlobalFilter (`Ordered.HIGHEST_PRECEDENCE + 30`). Manages concurrent open connections:
   - Public tracking SSE stream: max 5 concurrent connections per client IP
   - Authenticated parcel SSE stream: max 10 concurrent connections per verified principal
   - Zero response body buffering; connection counters release upon disconnect (`.doFinally()`).

---

## 3. Approved Route-Family Security Expectations Matrix

When wiring routes in `GatewayRoutesConfig.java` or `application.yml`, Issue #127 must ensure route paths, HTTP methods, and target services match the following frozen policy:

| Route Family | HTTP Method | Gateway Path Pattern | Target Microservice | Required Gateway Security Policy | Role Restriction |
|---|---|---|---|---|---|
| **Actuator Probes** | `GET` | `/actuator/health`, `/actuator/health/**` | API Gateway (local) | `permitAll()` | None |
| **CORS Preflight** | `OPTIONS` | `/**` | API Gateway (local) | `permitAll()` | None |
| **Public Tracking** | `GET` | `/api/v1/tracking/**` | Delivery Service | `permitAll()` + Rate Limit (60/min) | None (public read) |
| **Public Tracking SSE**| `GET` | `/api/v1/tracking/events` | Delivery Service | `permitAll()` + Concurrency Cap (5) | None (public stream) |
| **Public Quotes** | `POST`, `GET` | `/api/v1/quotes`, `/api/v1/quotes/**` | Pricing & Zones | `permitAll()` + Payload (64 KB) | None (public quote) |
| **User Profile** | `GET` | `/api/v1/users/me` | Accounts & Partners | Bearer JWT | `authenticated()` |
| **Merchant Onboarding**| `POST`| `/api/v1/merchants` | Accounts & Partners | Bearer JWT | `authenticated()` |
| **Merchant Admin** | `POST` | `/api/v1/merchants/*/approve` | Accounts & Partners | Bearer JWT | `ROLE_ADMIN` |
| **Admin Operations** | `*` | `/api/v1/admin/**` | Owning Services | Bearer JWT | `ROLE_ADMIN` |
| **Dispatcher Commands**| `POST` | `/api/v1/assignments` | Delivery Service | Bearer JWT | `ROLE_DISPATCHER`, `ROLE_ADMIN` |
| **Courier Operations** | `PATCH`| `/api/v1/parcels/*/status` | Delivery Service | Bearer JWT | `ROLE_COURIER` |
| **Courier Proof** | `POST` | `/api/v1/parcels/*/proof` | Delivery Service | Bearer JWT + Payload (5 MB) | `ROLE_COURIER` |
| **Customer Booking** | `POST` | `/api/v1/parcels` | Delivery Service | Bearer JWT + Payload (256 KB) | `ROLE_CUSTOMER`, `ROLE_MERCHANT`, `ROLE_ADMIN` |
| **Merchant Order API** | `POST` | `/api/v1/merchant/parcels` | Delivery Service | Valid `X-API-Key` OR Bearer JWT | `ROLE_MERCHANT`, `ROLE_ADMIN` |
| **Merchant Portal** | `*` | `/api/v1/merchants/**` | Accounts & Partners | Bearer JWT | `ROLE_MERCHANT`, `ROLE_ADMIN` |
| **Parcel Reads/SSE** | `GET` | `/api/v1/parcels/**` | Delivery Service | Bearer JWT + Concurrency (10) | `authenticated()` (downstream checks ownership) |
| **GraphQL Composite** | `POST` | `/graphql` | GraphQL Query API | Bearer JWT | `authenticated()` (downstream checks field access) |

---

## 4. Upstream Integration & SSE Forwarding Requirements for Issue #127

### 4.1 SSE Streaming Requirements
- Native browser `EventSource` and fetch streaming must receive unbuffered SSE responses.
- In `GatewayRoutesConfig` or route definitions for SSE paths (`/api/v1/tracking/events` and `/api/v1/parcels/*/events`), ensure no intermediate filter buffers the `Flux<DataBuffer>`.
- Client disconnection must propagate cleanly to Netty upstream channels so `SseConnectionPolicyGatewayFilter.doFinally()` decrements active connection counts.
- Bearer tokens for authenticated SSE must remain supported via fetch streaming headers (`Authorization: Bearer <token>`). Do NOT put tokens in URL query strings.

### 4.2 Error Translation & Upstream Resilience
- Edge authentication errors (401), authorization errors (403), payload size errors (413), and rate-limit errors (429) are fully handled by Issue #106.
- Issue #127 owns upstream failure mapping:
  - When downstream services are unreachable (connection refused, Netty timeout): return HTTP 503 `Service Unavailable` or HTTP 504 `Gateway Timeout` as RFC 7807 `ProblemDetail`.
  - Do NOT leak downstream internal hostnames (`http://delivery-service:8083`), IPs, or Java stack traces to clients.

### 4.3 Target Service URL Configuration
Use standardized environment variables with sensible local defaults:
- `ACCOUNTS_SERVICE_URL` (default: `http://localhost:8081`)
- `PRICING_SERVICE_URL` (default: `http://localhost:8082`)
- `DELIVERY_SERVICE_URL` (default: `http://localhost:8083`)
- `GRAPHQL_SERVICE_URL` (default: `http://localhost:8084`)

---

## 5. Policy Limits Status (Approved vs. Proposed)

To maintain compliance with repository rules, numerical limits are explicitly tagged:

| Parameter | Config Property | Configured Default | Status |
|---|---|---|---|
| Public IP Rate Limit | `swiftdrop.gateway.rate-limit.public-limit-per-minute` | 60 req/min (burst 10) | **PROPOSED — AWAITING REVIEW** |
| Merchant Rate Limit | `swiftdrop.gateway.rate-limit.merchant-limit-per-minute`| 120 req/min (burst 20) | **PROPOSED — AWAITING REVIEW** |
| Unauth Probe Rate Limit | `swiftdrop.gateway.rate-limit.unauthenticated-limit-per-minute` | 10 req/min (burst 5) | **PROPOSED — AWAITING REVIEW** |
| Quotes Payload Limit | `swiftdrop.gateway.payload.max-quote-bytes` | 65,536 (64 KB) | **PROPOSED — AWAITING REVIEW** |
| Standard Payload Limit | `swiftdrop.gateway.payload.max-standard-bytes` | 262,144 (256 KB) | **PROPOSED — AWAITING REVIEW** |
| Proof Upload Limit | `swiftdrop.gateway.payload.max-proof-bytes` | 5,242,880 (5 MB) | **PROPOSED — AWAITING REVIEW** |
| Public SSE Concurrency | `swiftdrop.gateway.sse.max-public-connections` | 5 concurrent streams | **PROPOSED — AWAITING REVIEW** |
| Authenticated SSE Concurrency | `swiftdrop.gateway.sse.max-authenticated-connections` | 10 concurrent streams | **PROPOSED — AWAITING REVIEW** |

**Action for Reviewers:** @Harithkeshan and @Vibhushan-2003 must review and sign off on these proposed values or provide alternative approved numbers before production release.

---

## 6. Test Suite Ownership

- **Issue #106 Test Suite (COMPLETED):**
  - `GatewayBusinessEdgeSecurityTest`: 21 tests covering JWT validation, role gates, public access, merchant API key authentication & authorization, and header spoofing defense.
  - `GatewayAbuseAndSsePolicyTest`: 8 tests covering public rate-limiting, merchant rate quotas, quote/parcel payload size rejections, and SSE concurrency limits.
  - Total gateway unit/security tests: 48 tests (all passing).
- **Issue #127 Test Suite (TO BE IMPLEMENTED BY GAJANTHAN):**
  - Route destination forwarding tests (verifying path routing to correct target microservice).
  - Upstream timeout and circuit breaker fallback tests.
  - Downstream connection failure translation tests (502/503/504 Problem Details).
  - Multi-service route integration tests.
