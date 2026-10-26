# Spring Cloud API Gateway

**Module:** API Gateway · **Owner:** Suthankan1 (Platform & Security) · **Target Milestone:** November 1, 2026 · **Issue:** [Refs #3](https://github.com/Suthankan1/swiftdrop/issues/3) · **Architecture Baseline:** SwiftDrop v1.3

This service is the single public entry point for the SwiftDrop delivery platform. Built on the non-blocking reactive Spring Cloud Gateway WebFlux stack, it handles external request routing, edge JWT authentication, CORS policy enforcement, client header sanitization, and graceful error handling.

---

## 1. Pinned Toolchain & Dependency Baseline

| Component | Pinned Version | Rationale |
|---|---|---|
| **Java Platform** | **Java 21 LTS** | Standardized language level across all SwiftDrop microservices. |
| **Spring Boot** | **4.1.1** (GA) | Modern production baseline built on Spring Framework 7. |
| **Spring Cloud** | **2025.1.3** (*Oakwood*) | Official release train compatible with Spring Boot 4.1.x and Java 21. |
| **Gateway Engine** | `spring-cloud-starter-gateway-server-webflux` | Non-blocking reactive Netty runtime. Servlet / Spring MVC dependencies are strictly excluded. |
| **Security** | `spring-boot-starter-security-oauth2-resource-server` | Reactive OAuth2 Resource Server with Keycloak JWKS signature and claim validation. |
| **Build Tool** | **Apache Maven** with Maven Wrapper (`./mvnw`) | Standalone reproducible builds without requiring global Maven installation. |

---

## 2. Architectural Boundaries & Responsibilities

In accordance with [SwiftDrop Architecture Baseline v1.3](../../docs/architecture/BASELINE.md) and [Login Contract](../../docs/contracts/LOGIN_CONTRACT.md):

### 2.1 Gateway Responsibilities
- **Edge Authentication Gate:** Validates Keycloak JWT cryptographic signature via JWKS, issuer URI, audience (`swiftdrop-api`), and token lifetime. Rejects missing or invalid tokens with HTTP 401 Unauthorized before forwarding to internal services.
- **Request Routing:** Forwards approved API requests to downstream business microservices (`accounts-service`, etc.) without unexpected path mutation.
- **Client Header Sanitization:** Automatically strips untrusted caller-supplied identity headers (`X-User-Id`, `X-Roles`, `X-Tenant-Id`, `X-Keycloak-Sub`, `X-User-Email`) to prevent identity spoofing, while strictly preserving the authentic `Authorization: Bearer <token>` header for downstream validation.
- **CORS Policy:** Restricts browser origins strictly to the authorized Vite development server (`http://localhost:5173`) and handles preflight `OPTIONS` requests.
- **Graceful Error Translation:** Emits standardized RFC 7807 `ProblemDetail` JSON responses on HTTP 401, 403, and 503 (downstream unavailable) without leaking stack traces or internal topology.
- **Health Monitoring:** Exposes `/actuator/health` publicly for container probes; keeps internal actuator management endpoints unexposed.

### 2.2 What the Gateway Does NOT Do
- **No Database / Persistence:** The Gateway has zero database dependencies, entities, or local storage.
- **No Business Logic:** Profile persistence, user registration, role assignment, and ownership verification belong exclusively to [Accounts & Partners Service](../accounts-service/) (Issue #5).
- **No Password Management:** Authentication and credential handling belong exclusively to [Keycloak](../../platform/identity/) (Issue #2).
- **No Monolithic Aggregation:** Does not replace the dedicated read-only GraphQL Query API or consume Kafka topics directly.

---

## 3. Approved Route Specifications (Architecture Baseline v1.3 & Issue #127)

| Method | Public Gateway Path | Downstream Target URL | Downstream Service | Auth Required | Route Order |
|---|---|---|---|---|---|
| `POST` | `/api/v1/merchants/webhooks` | `${COMMUNICATIONS_SERVICE_URL:http://localhost:8084}/api/v1/merchants/webhooks` | Communications | Yes (`ROLE_MERCHANT`, `ROLE_ADMIN`) | 5 |
| `GET` | `/api/v1/merchants/webhooks/deliveries` | `${COMMUNICATIONS_SERVICE_URL:http://localhost:8084}/api/v1/merchants/webhooks/deliveries` | Communications | Yes (`ROLE_MERCHANT`, `ROLE_ADMIN`) | 5 |
| `GET` | `/api/v1/users/me` | `${ACCOUNTS_SERVICE_URL:http://localhost:8081}/api/v1/users/me` | Accounts & Partners | Yes (`ROLE_CUSTOMER`, etc.) | 10 |
| `POST` | `/api/v1/merchants` | `${ACCOUNTS_SERVICE_URL:http://localhost:8081}/api/v1/merchants` | Accounts & Partners | Yes (Authenticated) | 10 |
| `POST` | `/api/v1/merchants/{id}/approve` | `${ACCOUNTS_SERVICE_URL:http://localhost:8081}/api/v1/merchants/{id}/approve` | Accounts & Partners | Yes (`ROLE_ADMIN`) | 10 |
| `*` | `/api/v1/merchants/**` | `${ACCOUNTS_SERVICE_URL:http://localhost:8081}/api/v1/merchants/**` | Accounts & Partners | Yes (`ROLE_MERCHANT`, `ROLE_ADMIN`) | 10 |
| `*` | `/api/v1/admin/**` | `${ACCOUNTS_SERVICE_URL:http://localhost:8081}/api/v1/admin/**` | Accounts & Partners | Yes (`ROLE_ADMIN`) | 10 |
| `POST, GET` | `/api/v1/quotes/**` | `${PRICING_SERVICE_URL:http://localhost:8082}/api/v1/quotes/**` | Pricing & Zones | No (Public) | 10 |
| `GET` | `/api/v1/zones/**` | `${PRICING_SERVICE_URL:http://localhost:8082}/api/v1/zones/**` | Pricing & Zones | Yes (Authenticated) | 10 |
| `GET` | `/api/v1/tracking/**` | `${DELIVERY_SERVICE_URL:http://localhost:8083}/api/v1/tracking/**` | Delivery Operations | No (Public, SSE unbuffered) | 10 |
| `POST` | `/api/v1/merchant/parcels/**` | `${DELIVERY_SERVICE_URL:http://localhost:8083}/api/v1/merchant/parcels/**` | Delivery Operations | Yes (`ROLE_MERCHANT` via JWT or `X-API-Key`) | 10 |
| `POST, GET` | `/api/v1/assignments/**` | `${DELIVERY_SERVICE_URL:http://localhost:8083}/api/v1/assignments/**` | Delivery Operations | Yes (`ROLE_COURIER`, `ROLE_DISPATCHER`, `ROLE_ADMIN`) | 10 |
| `*` | `/api/v1/parcels/**` | `${DELIVERY_SERVICE_URL:http://localhost:8083}/api/v1/parcels/**` | Delivery Operations | Yes (Authenticated, SSE unbuffered) | 10 |
| `POST` | `/graphql` | `${GRAPHQL_SERVICE_URL:http://localhost:8084}/graphql` | GraphQL Query API | Yes (Authenticated) | 10 |
| `GET` | `/actuator/health` | Local actuator handler | API Gateway | No (Public) | Local |

Target service URLs are configured via environment variables:
- `ACCOUNTS_SERVICE_URL` (default: `http://localhost:8081`)
- `PRICING_SERVICE_URL` (default: `http://localhost:8082`)
- `DELIVERY_SERVICE_URL` (default: `http://localhost:8083`)
- `COMMUNICATIONS_SERVICE_URL` (default: `http://localhost:8084`)
- `GRAPHQL_SERVICE_URL` (default: `http://localhost:8084`)

---

## 4. Keycloak JWT Security & Claims Enforcement

The Gateway configures a reactive `NimbusReactiveJwtDecoder` with:
- **Issuer URI:** `http://localhost:8090/realms/swiftdrop` (configurable via `KEYCLOAK_ISSUER_URI`)
- **JWKS Set URI:** `http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs` (configurable via `KEYCLOAK_JWKS_URI`)
- **Required Audience:** `swiftdrop-api` (enforced via `AudienceValidator`)
- **Cryptographic Signature:** Verified via public keys from the Keycloak realm's published JWKS.
- **Token Lifetime:** Validates non-expired token (`exp`), issue time (`iat`), and not-before (`nbf`).
- **Role Extraction:** Maps Keycloak `roles` and `realm_access.roles` claims to Spring Security `ROLE_*` authorities (`ROLE_CUSTOMER`, `ROLE_MERCHANT`, `ROLE_COURIER`, `ROLE_DISPATCHER`, `ROLE_ADMIN`).
- **Merchant Machine Credentials:** Authenticates machine callers on `POST /api/v1/merchant/parcels` via `X-API-Key` headers matching `^swd_live_[0-9a-zA-Z]{32}$`, granting `ROLE_MERCHANT` authority without exposing credentials or bypassing JWT security on other routes.
- **Internal Delegation Stripping:** Strips both client identity headers and internal gRPC actor delegation headers (`x-swiftdrop-*`) introduced in Issue #100, preventing external impersonation of internal services.
- **Abuse & Rate Limiting:** Enforces bounded token-bucket rate limits (60 req/min public, 120 req/min verified merchant, 10 req/min unauthenticated probes) with HTTP 429 and `Retry-After`.
- **Payload Size Enforcement:** Enforces maximum request body sizes (64 KB for quotes, 256 KB standard parcel commands, 5 MB courier proof uploads) with HTTP 413 `Payload Too Large`.
- **SSE Connection Policy:** Bounded concurrency caps (5 public tracking streams, 10 authenticated parcel streams) with zero response buffering and clean disconnect cleanup.

### RFC 7807 Problem Details Error Structure
Unauthenticated or invalid requests receive:
```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Bearer token is missing, expired, or invalid.",
  "instance": "/api/v1/users/me"
}
```

Forbidden or insufficient role requests receive:
```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "Access Denied",
  "instance": "/api/v1/admin/merchants"
}
```

Rate limit exceeded requests receive:
```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Too many requests. Please retry in 12 seconds.",
  "instance": "/api/v1/tracking/SWD-123"
}
```

Payload too large requests receive:
```json
{
  "type": "about:blank",
  "title": "Payload Too Large",
  "status": 413,
  "detail": "Payload size exceeds maximum allowed limit of 65536 bytes.",
  "instance": "/api/v1/quotes"
}
```

When downstream service is offline/unreachable (HTTP 503):
```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Downstream service is currently unavailable.",
  "instance": "/api/v1/users/me"
}
```

When downstream service times out (HTTP 504):
```json
{
  "type": "about:blank",
  "title": "Gateway Timeout",
  "status": 504,
  "detail": "The gateway timed out waiting for upstream service response.",
  "instance": "/api/v1/quotes/standard"
}
```

When downstream connection terminates prematurely or returns invalid upstream response (HTTP 502):
```json
{
  "type": "about:blank",
  "title": "Bad Gateway",
  "status": 502,
  "detail": "Bad gateway or invalid response from upstream service.",
  "instance": "/api/v1/parcels/SWD-101"
}
```

---

## 5. Local Port Registry

| Service | Local Origin | Protocol | Purpose |
|---|---|---|---|
| **Frontend SPA** | `http://localhost:5173` | HTTP (Vite) | Browser client (in-memory token) |
| **API Gateway** | `http://localhost:8080` | HTTP (WebFlux) | Edge routing & JWT gate |
| **Accounts Service** | `http://localhost:8081` | HTTP (Spring Boot) | User profile & business auth (Issue #5) |
| **Pricing Service** | `http://localhost:8082` | HTTP (Spring Boot) | Zones & quote calculations |
| **Delivery Service** | `http://localhost:8083` | HTTP (Spring Boot) | Parcels & lifecycle operations |
| **GraphQL Query API** | `http://localhost:8084` | HTTP (Spring Boot) | Read-only composite queries |
| **Communications Service** | `http://localhost:8084` | HTTP (Spring Boot) | Merchant notifications & webhooks |
| **Keycloak Identity** | `http://localhost:8090` | HTTP (Docker) | OIDC provider & realm authority (Issue #2) |

---

## 6. Build and Verification Instructions

### 6.1 Prerequisites
- Java 21 LTS (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`)
- Maven Wrapper (`./mvnw`)

### 6.2 Running Automated Gateway Tests
The automated test suite runs independently of external dependencies using synthetic RSA keys and MockWebServer:
```bash
cd services/api-gateway
./mvnw clean test
```

**Test Coverage Summary (75 Automated Tests across 7 Test Classes):**
1. Application context load (`ApiGatewayApplicationTests` - 1 test)
2. Downstream service unavailable error mapping (`ApiGatewayDownstreamUnavailableTest` - 1 test)
3. Target contract routing tests (`SwiftDropTargetContractGatewayTest` - 6 tests)
4. Baseline security & routing tests (`ApiGatewaySecurityAndRoutingTest` - 12 tests)
5. Business edge security policy tests (`GatewayBusinessEdgeSecurityTest` - 21 tests)
   - Missing, expired, malformed, wrong issuer/audience JWT rejection (HTTP 401)
   - Keycloak role extraction and role-gate enforcement (`ROLE_CUSTOMER`, `ROLE_COURIER`, `ROLE_DISPATCHER`, `ROLE_ADMIN`)
   - Merchant machine `X-API-Key` authentication and authorization on `POST /api/v1/merchant/parcels`
   - Denial of merchant API keys on non-merchant routes
   - Case-insensitive stripping of caller identity (`X-User-Id`, `X-Roles`, etc.) and internal gRPC delegation headers (`x-swiftdrop-*`)
   - Preservation of `Authorization: Bearer <token>` and `Idempotency-Key`
6. Bounded abuse and SSE policy tests (`GatewayAbuseAndSsePolicyTest` - 8 tests)
   - Public IP rate-limiting (HTTP 429 with `Retry-After`)
   - Merchant verified identity rate-limiting with independent quota buckets
   - Quotes calculation payload size limit (HTTP 413 on > 64 KB)
   - Parcel booking standard payload size limit (HTTP 413 on > 256 KB)
   - Public tracking SSE stream concurrency cap (HTTP 429 on > 5 concurrent streams)
   - Authenticated SSE stream Bearer token enforcement and concurrency limit
   - In-memory rate limiter capacity bounding and stale bucket pruning
7. Gateway business routes integration tests (`GatewayBusinessRoutesIntegrationTest` - 12 tests, Issue #127)
   - Verified routing to Accounts & Partners (`/api/v1/users/me`, `/api/v1/merchants/**`, `/api/v1/admin/**`)
   - Verified routing to Pricing & Zones (`/api/v1/quotes/**`, `/api/v1/zones/**`)
   - Verified routing to Delivery Operations (`/api/v1/merchant/parcels/**`, `/api/v1/assignments/**`, `/api/v1/parcels/**`)
   - Verified unbuffered SSE live tracking stream forwarding (`/api/v1/tracking/**`)
   - Verified routing to Communications webhooks (`/api/v1/merchants/webhooks/**`) with higher order precedence over generic merchants
   - Verified routing to GraphQL Query API (`/graphql`)
   - Edge security header sanitization and negative 401/403 authorization regressions
8. Gateway upstream error handling and timeout tests (`GatewayUpstreamErrorHandlingTest` - 7 tests, Issue #127)
   - Propagation of upstream 4xx client errors (`400 Bad Request`, `404 Not Found`, `409 Conflict`)
   - Propagation of upstream 500 Internal Server Error
   - Translation of connection refusal / unreachable upstream to RFC 7807 HTTP 503 Service Unavailable
   - Translation of upstream timeout to RFC 7807 HTTP 504 Gateway Timeout
   - Translation of premature upstream disconnect to RFC 7807 HTTP 502 Bad Gateway
   - Sanitization of error responses to guarantee zero leakage of internal hostnames, IPs, or stack traces

### 6.3 Running the Gateway Locally
```bash
cd services/api-gateway
./mvnw spring-boot:run
```

The gateway listens on `http://localhost:8080`.

---

## 7. Downstream Route Integration Status (Issue #127)

Implemented under **Issue #127** by **@Gajanthan23**:
- All approved business routes integrated in programmatic `RouteLocator` (`GatewayRoutesConfig.java`) and declarative `application.yml`.
- Unbuffered SSE forwarding configured for live parcel lifecycle updates and public tracking.
- RFC 7807 Problem Detail error handling distinguishing 502, 503, and 504 without leaking internal network topology or Java traces.
- All 75 unit, security, routing, and error handling integration tests verified passing.
