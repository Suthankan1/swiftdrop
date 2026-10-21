# Spring Cloud API Gateway

**Module:** API Gateway · **Owner:** Suthankan1 (Platform & Security) · **Target Milestone:** November 1, 2026 · **Issue:** [Refs #3](https://github.com/Suthankan1/devarom/issues/3) · **Architecture Baseline:** SwiftDrop v1.3

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
| **Accounts Service** | `http://localhost:8081` | HTTP (Spring Boot) | User profile & business auth (pending Issue #5) |
| **Keycloak Identity** | `http://localhost:8090` | HTTP (Docker) | OIDC provider & realm authority (Issue #2) |

---

## 6. Build and Verification Instructions

### 6.1 Prerequisites
- Java 21 LTS (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`)
- Docker (for optional Keycloak live smoke testing)

### 6.2 Running Automated Gateway Tests
The automated test suite runs independently of external dependencies using synthetic RSA keys and MockWebServer:
```bash
cd services/api-gateway
./mvnw clean test
```

**Test Coverage Summary (13 Automated Tests):**
1. Application context load (`ApiGatewayApplicationTests`)
2. Public `/actuator/health` endpoint accessibility without token
3. Missing Bearer token rejection with HTTP 401 and RFC 7807 Problem Details
4. Malformed JWT rejection with HTTP 401
5. Untrusted signature rejection with HTTP 401
6. Wrong issuer rejection with HTTP 401
7. Wrong audience rejection with HTTP 401
8. Expired JWT rejection with HTTP 401
9. Valid JWT routing to Accounts Service with preserved `Authorization` header
10. Stripping of client-supplied spoofed headers (`X-User-Id`, `X-Roles`, `X-Tenant-Id`, etc.)
11. Permissive CORS headers for authorized origin `http://localhost:5173`
12. Denial of permissive CORS headers for unauthorized origins
13. Downstream service unavailable translation to HTTP 503 Problem Details without leaking stack traces

### 6.3 Running the Gateway Locally
```bash
cd services/api-gateway
./mvnw spring-boot:run
```

The gateway listens on `http://localhost:8080`.

---

## 7. Cross-Team Integration & Next Steps

- **Downstream Accounts Service:** Owned by Customer backend lead (Sinthuha, Issue #5). Gateway forwarding contract is complete and verified against mock downstream server.
- **Frontend SPA:** Owned by Customer frontend lead (Gajanthan, Issue #6). CORS policy is configured and allowlisted for `http://localhost:5173`.
- **November 1 Login Milestone:** End-to-end browser integration (`React -> Gateway -> Accounts`) will be proven once Accounts Service and React SPA are merged into main.
