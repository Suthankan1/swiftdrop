# November 1 Login Integration Contract & Baseline Freeze

**Status:** Proposed team contract for review · **Target Milestone:** November 1, 2026 · **Issue:** [Refs #1](https://github.com/Suthankan1/devarom/issues/1) · **Architecture Baseline:** Devarom v1.3

> **Contract-first principle:** This document freezes the authentication scope, backend/frontend toolchain baseline, identity flows, minimal REST contract, local development port registry, and team responsibilities for the November 1 deliverable. No application code, scaffolding, or configurations are generated in this task.

---

## 1. Toolchain & Version Baseline Freeze

All future Spring Initializr project generation and frontend scaffolding across the Devarom repository **must** strictly adhere to this pinned baseline:

| Component | Selected Baseline | Verification & Compatibility Status |
|---|---|---|
| **Java Platform** | **Java 21 LTS** | Verified baseline for modern enterprise Spring ecosystems; supported by all target services. |
| **Spring Boot** | **4.1.1** (GA) | Released August 2026; primary GA production line built on Spring Framework 7.0. |
| **Spring Cloud** | **2025.1.3** (*Oakwood* release train) | Verified officially compatible with Spring Boot 4.0.x and 4.1.x lines. Used **only** in applications requiring Cloud components (`services/api-gateway/`). Core business services do not bundle Spring Cloud unnecessarily. |
| **Build Tool** | **Apache Maven** | Standardized across all backend services; wrapper scripts (`mvnw`) generated per service by owners. |
| **Packaging** | **JAR** | Executable standalone microservice containers. |
| **Java Group ID** | `com.devarom` | Standard package namespace (e.g., `com.devarom.accountsservice`, `com.devarom.apigateway`). |
| **Frontend Framework** | **React + TypeScript + Vite** | Current Node.js LTS, `pnpm` package manager default; single SPA with role-based routing. |
| **Persistence** | **PostgreSQL 16+** | Separate logical database per service; Flyway migrations owned by individual services. |
| **Identity Provider** | **Keycloak 25+** | Standalone identity provider container with separate identity PostgreSQL database. |

*Note on verification:* Spring Boot 4.1.1 and Spring Cloud 2025.1.3 (Oakwood) have been verified for mutual compatibility under Java 21 LTS. Core business services (`accounts-service`, `pricing-service`, `delivery-service`, `communications-service`, `graphql-query-service`) will use Spring Boot 4.1.1 starters directly and omit Spring Cloud BOMs unless edge gateway capabilities require them.

---

## 2. Authentication & Identity Architecture (Nov 1 Scope)

### 2.1 Identity Provider & OIDC Architecture
- **Identity Provider (IdP):** Keycloak acts as the central OpenID Connect (OIDC) identity provider and authorization server for Devarom.
- **Federated Identity Broker:** Google Sign-In is integrated as an external Identity Provider (IdP Broker) inside Keycloak. The React SPA interacts **only** with Keycloak; it never directly manages Google OAuth2 tokens or secrets. A Google token is **not** a Devarom API bearer token.
- **Client Configuration:**
  - Client ID: `devarom-frontend`
  - Access Type: **Public client** (browser SPA).
  - Standard Flow: **Authorization Code Grant with PKCE** (Proof Key for Code Exchange) using code challenge method `S256`.
  - Client Authentication: **Disabled** (no client secret in browser code or runtime bundle; `VITE_*` environment variables are completely public).
  - Web Origins: `http://localhost:5173`
  - Valid Redirect URIs: `http://localhost:5173/auth/callback`
  - Post-Logout Redirect URIs: `http://localhost:5173/`

### 2.2 Token Storage & Session Security
- **Access Token Storage:** Access JWTs are held exclusively in **JavaScript runtime memory** within the React SPA state. Access tokens must **never** be stored in `localStorage`, `sessionStorage`, or readable cookies to guard against XSS token exfiltration.
- **Refresh Flow & Silent Re-authentication:** Token renewal uses Keycloak silent re-authentication (via refresh token held in memory or secure session cookie against the Keycloak origin only).
- **Session Lifecycle & Error States:**
  - *Login Redirect:* React initiates redirect to Keycloak `/auth` with `code_challenge` (SHA-256 of cryptographic `code_verifier`), `state`, and `nonce`.
  - *Callback Handling:* React handles `/auth/callback`, passes authorization code and `code_verifier` to Keycloak `/token` endpoint, and receives access token, ID token, and refresh token.
  - *Logout:* SPA calls Keycloak OIDC end-session endpoint with `post_logout_redirect_uri`, revokes local tokens, and clears in-memory state.
  - *Expired Token:* If an API call receives HTTP 401, the SPA attempts one silent refresh; if refresh fails or expires, the user is redirected to the login landing page.
  - *Cancellation & Failure:* User cancellation at the Google/Keycloak login page or an IdP network failure routes the user back to `/login?error=cancelled` with a sanitized user-facing error message without creating false authenticated state.

---

## 3. Authorization & Role Model

### 3.1 Five Core Roles
Devarom defines five distinct business roles in Keycloak realm roles (`realm_access.roles`):
1. `CUSTOMER`: Self-registered public sender/receiver.
2. `MERCHANT`: Business client sending batch orders via API or dashboard.
3. `COURIER`: Field delivery personnel managing assigned parcels.
4. `DISPATCHER`: Operational coordinator assigning parcels to eligible couriers.
5. `ADMIN`: System and business administrator managing approvals, pricing zones, and platform parameters.

### 3.2 Controlled Provisioning & Privilege Invariants
- **Self-Registration:** Only `CUSTOMER` and `MERCHANT` roles can self-register.
- **Staff Provisioning:** `COURIER`, `DISPATCHER`, and `ADMIN` roles **cannot** be self-assigned. They must be provisioned directly by an existing administrator within Keycloak.
- **Privilege Separation:** Users with customer or merchant accounts cannot self-elevate to courier, dispatcher, or admin privileges.
- **Merchant Approval Distinction:** Successful authentication as a `MERCHANT` in Keycloak does **not** grant permission to dispatch orders. Merchant capabilities require `status = APPROVED` in the Accounts Service database. A newly registered merchant defaults to `PENDING` approval and receives restricted read-only onboarding access until an administrator approves the merchant.

### 3.3 Dual-Layer JWT & Ownership Enforcement
Authorization is enforced at two distinct boundaries:

```text
React SPA (Bearer JWT in memory)
     |
     v
[1. Spring Cloud Gateway] (Port 8080)
     | - Edge JWT verification (signature, issuer, audience, expiry)
     | - Returns 401 Unauthorized if invalid or missing
     | - CORS policy & path routing
     v
[2. Core Business Service] e.g., Accounts Service (Port 8081)
       - Independent OAuth2 Resource Server validation via Keycloak JWKS
       - Extracts Keycloak 'sub' (subject claim)
       - Enforces role-based access control (RBAC)
       - Enforces Tenant & Resource Ownership (Customer A cannot access Customer B's profile)
       - Returns 401 (invalid token), 403 (unauthorized role/tenant), or privacy-preserving 404
```

- **HTTP 401 Unauthorized:** Missing, expired, malformed, or untrusted Bearer JWT.
- **HTTP 403 Forbidden:** Valid token, but caller lacks the required role or does not own the requested resource.
- **HTTP 404 Not Found (Privacy-Preserving):** Returned when exposing existence of another tenant's resource would leak sensitive operational or personal data.

---

## 4. Minimum November 1 REST Integration Contract

To demonstrate real end-to-end authentication for the November 1 milestone, the team agrees on the smallest possible protected path:
`React SPA → Spring Cloud Gateway → Accounts Service`.

### 4.1 Endpoint Specification: Current User Profile
- **Method & Path:** `GET /api/v1/users/me`
- **Gateway Public Route:** `http://localhost:8080/api/v1/users/me`
- **Downstream Service Target:** `http://localhost:8081/api/v1/users/me` (Accounts & Partners Service)
- **Required Header:** `Authorization: Bearer <keycloak-jwt>`
- **Content Negotiation:** `Accept: application/json`

### 4.2 Proposed Success Response (`200 OK`)
```json
{
  "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "keycloakSub": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "email": "customer@example.com",
  "fullName": "Jane Doe",
  "phoneNumber": "+94771234567",
  "roles": [
    "CUSTOMER"
  ],
  "merchantProfile": null,
  "createdAt": "2026-10-15T10:00:00Z"
}
```

*For a Merchant user:*
```json
{
  "id": "8d0f7780-8536-41ef-055c-f18fd2a01bf8",
  "keycloakSub": "b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e",
  "email": "logistics@store.lk",
  "fullName": "Store Logistics Admin",
  "phoneNumber": "+94112345678",
  "roles": [
    "MERCHANT"
  ],
  "merchantProfile": {
    "merchantId": "9e1a8891-9647-42fa-166d-a29ge3b12cg9",
    "businessName": "Store Direct LK",
    "registrationNumber": "PV-123456",
    "status": "PENDING"
  },
  "createdAt": "2026-10-15T10:00:00Z"
}
```

### 4.3 Error Response Standard (RFC 7807 Problem Details)
All error responses adhere to the standard Spring Boot `ProblemDetail` structure:

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Bearer token is missing, expired, or invalid.",
  "instance": "/api/v1/users/me"
}
```

### 4.4 Service Responsibility Boundary
- **Spring Cloud Gateway:** Matches `/api/v1/users/**`, validates JWT signature/issuer, strips forbidden internal headers, forwards `Authorization` header to Accounts Service. Does **not** read databases, resolve profiles, or cache user entities.
- **Accounts & Partners Service:** Acts as independent OAuth2 Resource Server. Reads JWT `sub`, queries internal `accounts_db`, creates Just-In-Time (JIT) minimal customer record on first authenticated login if absent, and returns the caller's own profile. Does **not** expose or handle Keycloak user credentials.

---

## 5. Local Development Integration & Port Registry

### 5.1 Port Registry & Origin Allocation
To prevent port collisions between services and third-party containers during local development, the following ports are pinned:

| Component / Service | Local Origin / Port | Protocol / Exposure | Database / Storage Ownership |
|---|---|---|---|
| **Frontend SPA** | `http://localhost:5173` | HTTP (Vite Dev Server) | Browser memory (no persistence) |
| **API Gateway** | `http://localhost:8080` | HTTP (Spring Cloud Gateway) | **No database** |
| **Accounts & Partners** | `http://localhost:8081` | HTTP (Spring Boot MVC) | `accounts_db` (PostgreSQL logical DB) |
| **Pricing & Zones** | `http://localhost:8082` | HTTP / gRPC (future) | `pricing_db` (PostgreSQL logical DB) |
| **Delivery Operations** | `http://localhost:8083` | HTTP / gRPC / SSE (future) | `delivery_db` + private object storage |
| **Communications** | `http://localhost:8084` | HTTP / Kafka Consumer (future) | `communications_db` (PostgreSQL logical DB) |
| **GraphQL Query API** | `http://localhost:8085` | HTTP / GraphQL (future) | **No database** (gRPC aggregator) |
| **Keycloak Identity** | `http://localhost:8090` | HTTP (Keycloak Container) | `keycloak_db` (PostgreSQL logical DB) |
| **PostgreSQL Server** | `localhost:5432` | TCP (PostgreSQL 16 Engine) | Hosts logical databases separately |

*Crucial Conflict Prevention:* Keycloak is assigned to port `8090` so that Spring Cloud Gateway can occupy the standard API port `8080` without host conflict.

### 5.2 Public Frontend Configuration (`.env.example`)
Non-sensitive configuration exposed to the Vite frontend (`VITE_*` keys are public by design):
```properties
VITE_KEYCLOAK_URL=http://localhost:8090
VITE_KEYCLOAK_REALM=devarom
VITE_KEYCLOAK_CLIENT_ID=devarom-frontend
VITE_API_GATEWAY_URL=http://localhost:8080
```

### 5.3 Secrets Management (Outside Git)
- **Google OAuth Client Credentials:** The Google Client ID and Google Client Secret required for Google identity brokering must be stored in an untracked local environment file (`platform/identity/.env` or Docker Compose environment file) that is explicitly ignored in `.gitignore`.
- **Zero Secrets in Git:** No client secrets, private keys, database passwords, API keys, or JWT tokens may ever be committed to the repository.

### 5.4 Platform vs. Customer Team Integration Responsibilities
- **Platform Team (`Suthankan1`):**
  - Exports sanitized Keycloak realm configuration (`platform/identity/realm-devarom-export.json`).
  - Implements Spring Cloud Gateway routing, JWT edge filter, and CORS headers permitting `http://localhost:5173`.
  - Provides reproducible local container startup documentation and verify Gateway-to-Accounts forwarding.
- **Customer Team (`Gajanthan`, `Sinthuha`):**
  - Implements React SPA OIDC client provider with PKCE.
  - Implements login/logout buttons, `/auth/callback` route, and error state handling.
  - Attaches Bearer JWT from memory to API calls and renders current user profile from `GET /api/v1/users/me`.

---

## 6. November 1 Scope & Dependency Isolation

### 6.1 True Milestone Scope (Nov 1, 2026)
1. **Design Report:** Comprehensive architectural documentation, 20 Draw.io diagrams, use cases, sequences, flowcharts, ERDs, lifecycle state machine, and design justifications.
2. **Demonstrable Initial Codebase:** A minimal, verifiable, working authentication slice:
   - Keycloak local user login.
   - Google Sign-In via Keycloak broker (if provider setup is unblocked).
   - React PKCE callback handling.
   - Spring Cloud Gateway routing to Accounts Service.
   - Backend JWT validation and resource ownership checks.
   - Negative security tests (missing token, invalid token, cross-tenant profile probe).
   - Clear, reproducible local startup commands.

### 6.2 Out of Scope for Nov 1
Booking workflows, pricing calculation algorithms, Kafka event outbox publication, Kafka consumers, gRPC service communication, GraphQL dashboard resolvers, SSE live tracking, receipt PDF generation, object storage adapters, Kubernetes manifests, and AI assistant implementations remain **design diagrams only** and are **not** required for November 1 code completion.

### 6.3 Google OAuth Blocker Isolation
- Google OAuth verification and provider consent credentials depend on third-party verification outside the team's control.
- **Isolation Rule:** A blocker or delay in Google OAuth credentials must **never** block local Keycloak login, Gateway routing, Accounts service implementation, or negative security tests. Local authentication satisfies the core architectural demonstration; Acceptance Item `L03` will remain unchecked until real Google credentials are available.

### 6.4 Initial Login Acceptance Status (L01–L12)
All acceptance criteria in [LOGIN_ACCEPTANCE.md](../milestones/LOGIN_ACCEPTANCE.md) remain strictly **UNVERIFIED**. No acceptance checkbox is marked as passed in this contract task:

| ID | Criterion | Assigned Lead | Verification State |
|---|---|---|---|
| L01 | Keycloak + React + Gateway + Accounts subset startup | Suthankan1 + Customer | Unverified |
| L02 | Local customer sign-up/login, profile by Keycloak `sub` | Gajanthan / Sinthuha | Unverified |
| L03 | Real Google login via Keycloak broker + PKCE | Suthankan1 + Customer | Unverified (External Blocker Gate) |
| L04 | Logout and session expiration tested | Suthankan1 + Customer | Unverified |
| L05 | Role entry pages for 5 roles; no self-granted staff | Pushmitha / HarithKeshan | Unverified |
| L06 | Missing/invalid JWT rejected with 401 | Suthankan1 + Accounts | Unverified |
| L07 | Unauthorized role rejected with 403/404 | Abhinaya / Vibhushan | Unverified |
| L08 | Cross-user profile access probe rejected (ownership) | Eric / Thuvarahan | Unverified |
| L09 | Login cancellation/failure does not create auth state | Gajanthan / Sinthuha | Unverified |
| L10 | Clean clone setup run by independent teammate | Eric (Reviewer) | Unverified |
| L11 | No secrets in frontend or Git commit history | Suthankan1 (Reviewer) | Unverified |
| L12 | All 20 report figures reviewed by assigned owners | All 9 members | Unverified |

---

## 7. Team Ownership & Review Matrix

### 7.1 Nine-Member Division of Responsibilities
The team consists of nine members. Individual internal Frontend/Backend splits are planning allocations agreed within each pair:

| Team / Module | Members | Primary Functional Ownership | Shared Contract Review Responsibilities |
|---|---|---|---|
| **Customer** | **Gajanthan, Sinthuha** | Customer UI/journeys, quote request, booking, tracking, customer profile | Reviews Accounts `/api/v1/users/me` contract and Gateway CORS with Platform. |
| **Merchant** | **Eric, Thuvarahan** | Merchant registration, order API, dashboard, webhook contracts | Reviews Accounts merchant pending/approval model and API key design. |
| **Courier** | **Abhinaya, Vibhushan** | Courier deliveries UI, status updates, notes, delivery proof capture | Reviews Courier role authorization rules and negative 403 test cases. |
| **Dispatcher/Admin** | **Pushmitha, HarithKeshan** | Unassigned queue, assignments, zone/pricing admin, approval dashboard | Reviews Admin role provisioning, staff access controls, and report figures. |
| **Platform & Security** | **Suthankan1** | Keycloak realm, Google broker, Gateway routing, JWT validation, contracts | Reviews all shared contracts; coordinates architecture and negative tests. |

### 7.2 Diagram Review Ownership (All 20 Figures)
Each of the 20 existing diagrams in `docs/diagrams/` requires formal review and text caption alignment by its assigned owners before report finalization:

| Figure ID & Title | Primary Reviewer(s) |
|---|---|
| **01** Customer use cases | Gajanthan, Sinthuha (Customer pair) |
| **02** Merchant use cases | Eric, Thuvarahan (Merchant pair) |
| **03** Courier use cases | Abhinaya, Vibhushan (Courier pair) |
| **04** Dispatcher/Admin use cases | Pushmitha, HarithKeshan (Dispatcher/Admin pair) |
| **05** System context | Suthankan1 (Platform) + All members |
| **06** Logical container architecture | Suthankan1 (Platform) + All service owners |
| **07** Customer quote and booking activity | Customer pair + Dispatcher pair |
| **08** Dispatcher courier assignment activity | Dispatcher pair + Courier pair |
| **09** Courier status, Kafka and notifications activity | Courier pair + Merchant pair + Platform |
| **10** Customer delivery booking sequence | Gajanthan, Sinthuha (Customer pair) |
| **11** Merchant order API sequence | Eric, Thuvarahan (Merchant pair) |
| **12** Courier status and webhook sequence | Courier pair + Merchant pair |
| **13** Google login via Keycloak sequence | Suthankan1 (Platform) + Customer pair |
| **14** Delivery Operations logical ERD | Customer + Courier + Dispatcher pairs |
| **15** Accounts and Pricing logical ERD | Merchant + Customer + Dispatcher pairs |
| **16** Communications logical ERD | Merchant pair + Suthankan1 (Platform) |
| **17** Parcel lifecycle state diagram | Courier pair + Dispatcher pair |
| **18** Kafka reliability and SSE fanout | Suthankan1 (Platform) + Courier + Merchant pairs |
| **19** Kubernetes deployment target (bonus only) | Suthankan1 (Platform) + All members |
| **20** Docker Compose target (planned) | Suthankan1 (Platform) + All members |

---

## 8. Human Team Confirmations Required

An automated agent cannot provide human member consent. The following confirmations are explicitly required from the human team:

1. **Lecturer Group-Size Approval:** Confirmation from the course lecturer regarding the 9-member team size against the printed 10-member specification. (Status: **OPEN**).
2. **Team Baseline Signoff:** Formal signoff from all 9 members agreeing to:
   - Java 21 LTS + Spring Boot 4.1.1 + Spring Cloud 2025.1.3 (Oakwood) + React/Vite baseline.
   - Pinned port registry (Gateway: 8080, Accounts: 8081, Keycloak: 8090, etc.).
   - Minimal REST endpoint `GET /api/v1/users/me` and JWT Bearer contract.
3. **Google Provider Credentials:** Confirmation on whether Google OAuth Client ID and Secret are available for local testing, or whether `L03` will be marked as deferred due to external credential gating.
4. **Peer Review Acknowledgement:** Acknowledgment by each pair of their assigned diagram review responsibilities.
