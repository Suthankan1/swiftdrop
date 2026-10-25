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

## 3. Approved Route Specifications (November 1 Milestone)

| Method | Public Gateway Path | Downstream Target URL | Downstream Service | Auth Required |
|---|---|---|---|---|
| `GET` | `http://localhost:8080/api/v1/users/me` | `http://localhost:8081/api/v1/users/me` | Accounts & Partners | Yes (Bearer JWT) |
| `GET` | `http://localhost:8080/actuator/health` | Local actuator handler | API Gateway | No (Public) |

The downstream target URL is fully configurable via the environment variable `ACCOUNTS_SERVICE_URL` (default: `http://localhost:8081`).

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

When downstream Accounts Service is offline/unreachable:
```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Downstream service is currently unavailable.",
  "instance": "/api/v1/users/me"
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

**Test Coverage Summary (48 Automated Tests across 5 Test Classes):**
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

### 6.3 Running the Gateway Locally
```bash
cd services/api-gateway
./mvnw spring-boot:run
```

The gateway listens on `http://localhost:8080`.

---

## 7. Downstream Route Integration Handoff (Issue #127)

Issue #106 provides the security, authentication, and abuse policy foundation.
Downstream route destination wiring, upstream timeout configuration, circuit breaking, and multi-service route integration tests are owned by **@Gajanthan23** under **Issue #127**.

For complete integration instructions, route-to-service mapping, and policy requirements, see:
[docs/handoff/ISSUE_127_GATEWAY_ROUTE_INTEGRATION.md](../../docs/handoff/ISSUE_127_GATEWAY_ROUTE_INTEGRATION.md)
