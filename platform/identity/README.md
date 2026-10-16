# Devarom — Keycloak Identity & OIDC Foundation

**Module:** Platform & Security · **Owner:** Suthankan1 · **Target Milestone:** November 1, 2026 · **Issue:** [Refs #2](https://github.com/Suthankan1/devarom/issues/2) · **Architecture Baseline:** Devarom v1.3

This directory contains the reproducible Keycloak identity foundation for the Devarom delivery platform. It defines the `devarom` realm, public OpenID Connect (OIDC) client for the React Single Page Application (SPA), role-based access control (RBAC) definitions, claims mapping for Spring Security Resource Servers, and Google OAuth 2.0 identity federation brokering.

---

## 1. Pinned Keycloak Version & Engine

| Property | Value | Rationale |
|---|---|---|
| **Image** | `quay.io/keycloak/keycloak:26.8.1` | Pinned exact stable release (released October 2026). Avoids unpredictable breaking changes from `latest`. |
| **Engine Runtime** | Quarkus-based Keycloak distribution | Modern official Keycloak architecture. Deprecated WildFly distribution and legacy client adapters are completely avoided. |
| **Local Binding Port** | `8090` | Pinned per [Login Contract](../../docs/contracts/LOGIN_CONTRACT.md) to eliminate host port collisions with Spring Cloud Gateway (`8080`). |
| **Local Storage Mode** | Embedded dev database (dev mode) | Development and smoke-testing use Keycloak's embedded storage. Production / shared stack will bind to dedicated logical PostgreSQL database `keycloak_db` (Issue #3). |

---

## 2. Realm Architecture (`devarom`)

The realm is defined in [`realm-devarom.json`](realm-devarom.json) as a sanitized, version-controlled configuration with zero hardcoded passwords or sensitive secrets.

### 2.1 Five Core Application Roles
Keycloak manages authentication and realm roles (`realm_access.roles`). Devarom defines five core business roles:

1. `CUSTOMER`: Self-registered public sender/receiver. Can book parcels, track shipments, and view own order history.
2. `MERCHANT`: Business client sending batch orders via API or merchant portal. Requires business approval in Accounts Service.
3. `COURIER`: Field delivery personnel updating assigned parcels, recording failed delivery reasons, and capturing proof of delivery.
4. `DISPATCHER`: Operational coordinator managing courier workload and parcel assignment.
5. `ADMIN`: System and platform administrator managing merchant approvals, pricing zones, and platform parameters.

### 2.2 Privilege Separation & Safe Registration Policy
- **Self-Registration Safe Default:** Public self-registration (`registrationAllowed: true`) assigns **only** the `CUSTOMER` role via `default-roles-devarom` composite membership.
- **Privilege Elevation Guard:** The privileged roles `MERCHANT`, `COURIER`, `DISPATCHER`, and `ADMIN` are strictly excluded from default roles. Public users cannot self-assign staff or merchant roles.
- **Controlled Staff Provisioning:** Staff roles (`COURIER`, `DISPATCHER`, `ADMIN`) must be manually granted by an existing administrator within Keycloak.
- **Authentication vs. Business Authorization:** Successful authentication as `MERCHANT` in Keycloak does **not** grant order dispatching permissions. The Accounts Service enforces business status `status = APPROVED`. Newly registered merchants default to `PENDING` approval in `accounts_db`.

---

## 3. React SPA Public OIDC Client (`devarom-frontend`)

Configured in accordance with the frozen [November 1 Login Contract](../../docs/contracts/LOGIN_CONTRACT.md):

| Setting | Value | Security Rationale |
|---|---|---|
| **Client ID** | `devarom-frontend` | Identifies the React Single Page Application. |
| **Access Type** | Public client (`publicClient: true`) | Browser application cannot safeguard client secrets. Client authentication is disabled. |
| **Grant Types** | Authorization Code Flow (`standardFlowEnabled: true`) | Recommended secure OIDC flow for browser SPAs. |
| **Implicit Flow** | Disabled (`implicitFlowEnabled: false`) | Deprecated, insecure grant type vulnerable to token leakage in browser history. |
| **Direct Access Grants (ROPC)** | Disabled (`directAccessGrantsEnabled: false`) | Username/password exchange directly via browser is strictly disallowed. |
| **PKCE Enforcement** | `S256` (`pkce.code.challenge.method: "S256"`) | Proof Key for Code Exchange using SHA-256 is strictly enforced. Plain PKCE and missing challenges are rejected with HTTP redirect errors. |
| **Valid Redirect URIs** | `http://localhost:5173/auth/callback` | Strictly allowlisted; wildcards (`*`) and unrestricted paths are forbidden. |
| **Web Origins (CORS)** | `http://localhost:5173` | Restricts CORS authorization requests to the Vite development server origin. |
| **Post-Logout Redirect** | `http://localhost:5173/` | Explicit landing route after session termination. |

*Note on Client Responsibilities:* The Keycloak realm defines the client configuration and enforces PKCE on authorization requests. Generating cryptographic `code_verifier`, `code_challenge`, `state`, and `nonce`, and handling the `/auth/callback` code exchange is the responsibility of the React OIDC client library (owned by Customer pair in Issue #4).

---

## 4. JWT Claims & Spring Security Resource Server Mapping

The realm configuration publishes standard OIDC discovery metadata and provides token claims tailored for downstream Spring Boot Resource Servers (`api-gateway` and `accounts-service`):

### 4.1 Endpoints & Discovery
- **OIDC Discovery:** `http://localhost:8090/realms/devarom/.well-known/openid-configuration`
- **Issuer:** `http://localhost:8090/realms/devarom`
- **JWKS Endpoint:** `http://localhost:8090/realms/devarom/protocol/openid-connect/certs`
- **Authorization Endpoint:** `http://localhost:8090/realms/devarom/protocol/openid-connect/auth`
- **Token Endpoint:** `http://localhost:8090/realms/devarom/protocol/openid-connect/token`
- **End Session (Logout) Endpoint:** `http://localhost:8090/realms/devarom/protocol/openid-connect/logout`

### 4.2 Audience Strategy
The client includes a dedicated `devarom-audience-mapper` (`oidc-audience-mapper`) configured with:
- `included.custom.audience`: `devarom-api`
- `access.token.claim`: `true`

This guarantees that emitted access JWTs contain `"aud": [..., "devarom-api"]`, allowing Spring Cloud Gateway and Resource Servers to validate audience integrity without ambiguity.

### 4.3 Role Claims Mapping
The client includes a dedicated `roles-claim-mapper` (`oidc-usermodel-realm-role-mapper`):
- `claim.name`: `roles`
- `multivalued`: `true`
- `access.token.claim`: `true`
- `id.token.claim`: `true`

This maps realm roles directly to a top-level `"roles": ["CUSTOMER", ...]` array in the access token, while Keycloak automatically retains standard `realm_access.roles`. Downstream Spring Security `JwtAuthenticationConverter` can cleanly extract roles from either path.

### 4.4 Token Lifetimes & Storage Rules
- **Access Token Lifespan:** `300` seconds (5 minutes). Short-lived tokens minimize exposure.
- **SSO Session Idle Timeout:** `1800` seconds (30 minutes).
- **Token Storage Rule:** Access JWTs are held **exclusively in JavaScript runtime memory**. Tokens must never be written to `localStorage`, `sessionStorage`, or unmasked browser cookies.

---

## 5. Google Identity Brokering & Secrets Management

Keycloak brokers federated identity with Google Sign-In. The browser application talks only to Keycloak; a Google token is **never** a Devarom API bearer token.

### 5.1 Runtime Credential Injection (Outside Git)
Google OAuth credentials are kept strictly outside source control.
- `platform/identity/.env.example`: Committed template with blank placeholders.
- `platform/identity/.env`: Local environment file ignored by Git (`.gitignore:2:.env`). Contains runtime variables for local development.

Keycloak's realm configuration uses environment variable substitution enabled via `-Dkeycloak.migration.replace-placeholders=true`:
```json
"config": {
  "clientId": "${GOOGLE_CLIENT_ID}",
  "clientSecret": "${GOOGLE_CLIENT_SECRET}",
  "defaultScope": "openid profile email",
  "syncMode": "IMPORT",
  "useJwksUrl": "true"
}
```

### 5.2 Google Cloud Console Setup Steps
1. Navigate to Google Cloud Console: `https://console.cloud.google.com/apis/credentials`.
2. Select or create project `devarom`.
3. Create an **OAuth 2.0 Client ID** of type **Web application**.
4. Configure **Authorized redirect URIs**:
   ```text
   http://localhost:8090/realms/devarom/broker/google/endpoint
   ```
5. Configure **Authorized JavaScript origins**:
   ```text
   http://localhost:8090
   ```
6. Copy the generated **Client ID** and **Client Secret** into your local `platform/identity/.env` file.
7. To activate Google login on the Keycloak login screen, set `enabled: true` on the Google provider using `kcadm.sh` or the Keycloak Admin Console.

*Status of Acceptance Item `L03`:* Real Google authentication acceptance (`L03`) is gated on external Google Cloud provider consent verification. The local broker configuration is complete and verified, while full end-to-end browser sign-in remains unverified until human team verification is conducted.

---

## 6. Local Startup & Verification Commands

### 6.1 Prerequisites
- Docker engine and Docker Compose installed and running.
- `curl` and `jq` installed locally.

### 6.2 Starting Keycloak Locally
```bash
# From repository root:
docker compose -f platform/identity/docker-compose.identity.yml --project-directory platform/identity up -d
```

### 6.3 Automated Validation Suite
A comprehensive validation script is provided to verify all security invariants and live endpoints:
```bash
./platform/identity/validate-identity.sh
```

**Checks Performed:**
1. Validates JSON syntax of `realm-devarom.json`.
2. Verifies realm identity is `devarom`.
3. Verifies 5 application roles exist and privileged roles cannot be self-assigned.
4. Verifies `devarom-frontend` is a public client with Authorization Code Flow, `S256` PKCE, and disabled ROPC/Implicit grants.
5. Verifies strict redirect URIs (`http://localhost:5173/auth/callback`) and Web Origins with no wildcards.
6. Verifies custom audience (`devarom-api`) and roles claim mapping.
7. Scans realm configuration for accidental plaintext secrets.
8. (If Keycloak is running) Executes live runtime smoke tests:
   - OIDC discovery endpoint inspection.
   - JWKS certificate verification.
   - Negative test: Direct Access Grant (ROPC) rejected with `unauthorized_client` (HTTP 400).
   - Negative test: Unauthorized redirect URI rejected with HTTP 400 Bad Request.
   - Negative test: Plain PKCE challenge rejected with redirect error.

### 6.4 Safe Shutdown
```bash
docker compose -f platform/identity/docker-compose.identity.yml --project-directory platform/identity down
```

---

## 7. Evidence & Acceptance Register (Refs #2)

| Acceptance Item | Description | Status | Evidence |
|---|---|---|---|
| **Realm Configuration** | `devarom` realm with exact pinned Keycloak 26.8.1 | **Verified** | `realm-devarom.json`, `docker-compose.identity.yml` |
| **Role Model** | 5 application roles; no self-elevation to staff/merchant | **Verified** | `validate-identity.sh` check 3; `default-roles-devarom` has `CUSTOMER` only |
| **Public SPA Client** | `devarom-frontend` code + PKCE S256, no client secret | **Verified** | `validate-identity.sh` check 4; rejected plain PKCE in live smoke test |
| **Redirect Security** | Strict local origins; zero wildcards | **Verified** | `validate-identity.sh` check 5; rejected attacker redirect with HTTP 400 |
| **Secrets Absent from Git** | Zero credentials in repo; `.env` gitignored | **Verified** | `git check-ignore -v platform/identity/.env` (`.gitignore:2:.env`) |
| **Google Federation** | Google IdP brokering configuration prepared | **Prepared / Configured** | IdP broker defined; runtime credentials injected via `.env`; full browser login gated on human consent verification |

---

## 8. Custom Branded Login Theme (`platform/identity/themes/devarom/login`)

**Issue:** [Refs #44](https://github.com/Suthankan1/devarom/issues/44) · **Theme Name:** `devarom` · **Parent Theme:** `keycloak.v2`

A fully responsive, accessible, and professionally branded Keycloak login, registration, and error recovery theme matching the Devarom React Single Page Application design language.

### 8.1 Theme Architecture & Directory Layout

```text
platform/identity/themes/devarom/login/
├── theme.properties                  # Declares parent=keycloak.v2, styles, scripts
├── template.ftl                      # Accessible layout wrapper, Devarom header & alert banner
├── footer.ftl                        # Branded card footer with encrypted session badge
├── messages/
│   └── messages_en.properties        # Custom branded copy and error labels
└── resources/
    ├── css/
    │   ├── styles.css                # Base stylesheet
    │   └── devarom.css               # Devarom design tokens, card styling, responsive rules
    └── img/
        ├── devarom-logo.svg          # Primary light mode wordmark
        ├── devarom-logo-dark.svg     # Dark mode wordmark
        ├── devarom-mark.svg          # Geometric tilted bar brand mark
        ├── favicon.ico               # Favicon (binary)
        ├── favicon.svg               # Vector SVG favicon
        └── social/
            └── google.svg            # Local vector asset for Google Sign-In broker button
```

### 8.2 Rationale: Native FreeMarker vs. Keycloakify
- **Zero Additional Build Tooling:** Native FreeMarker + CSS is natively supported by Keycloak 26.8.1 (Quarkus runtime) with no additional Node.js/npm transpilation pipelines required inside Docker or CI.
- **Immediate Hot Reloading:** Changes in `./themes` are reflected immediately in the running container via volume mounting.
- **Direct Compatibility & Security Invariants:** Directly extends `keycloak.v2` (PatternFly v5). Upstream Keycloak security forms, CSRF validation, session state management, and required action handlers are fully preserved without risk of desynchronization.
- **Independence:** Does not duplicate or bundle the React SPA into Keycloak.

### 8.3 Design Tokens & Styling
Tokens directly match `frontend/src/styles/global.css`:
- **Ink Primary:** `#173f36` (`--color-ink`)
- **Teal Accent / Brand:** `#20594a` (`--color-teal`)
- **Lime Accent:** `#d9ed95` (`--color-accent`)
- **Paper Background:** `#f7f7ef` (`--color-paper`)
- **Card Surface:** `#fcfcf7` (`--color-card`)
- **Border Subtle:** `#dce0d2` (`--color-border`)
- **Local Typography:** Uses native system sans-serif font stack (`system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`) with zero external Google Fonts / CDN network calls, ensuring fast, privacy-respecting rendering.
- **Responsive Layout:** Optimized with fluid card width (`max-width: 460px`) and breakpoints for Desktop (1440px), Tablet (768px), Mobile (480px, 390px), and Small Mobile (360px) with zero horizontal overflow.
- **Dark Mode Support:** Clean palette adaptation using `.pf-v5-theme-dark` and `@media (prefers-color-scheme: dark)` with dedicated dark wordmark.

### 8.4 Safe Theme Installation & Existing-Realm Upgrade
1. **Docker Compose Mounting:**
   `platform/identity/docker-compose.identity.yml` mounts the local theme directory into the container:
   ```yaml
   volumes:
     - ./realm-devarom.json:/opt/keycloak/data/import/realm-devarom.json:ro
     - ./themes:/opt/keycloak/themes:ro
   ```
2. **Fresh Deployments:**
   `realm-devarom.json` specifies `"loginTheme": "devarom"`, automatically applied on fresh container imports.
3. **Existing Realm Upgrade (Idempotent, Zero Data Loss):**
   Keycloak does not re-import `realm-devarom.json` when a database volume already exists. To safely apply the theme to a running Keycloak instance without deleting users, realms, or wiping volumes:
   ```bash
   ./platform/identity/apply-theme.sh
   ```
   This script securely runs `kcadm.sh` inside the container using ephemeral admin credentials, verifies the theme is installed, updates `loginTheme: devarom`, and tests the live HTTP response.

### 8.5 Accessibility (WCAG 2.1 AA Compliance)
- **Skip Link:** Hidden `#kc-skip-link` allows keyboard/screen-reader users to skip straight to the page title.
- **Focus Rings:** High-visibility dual focus rings (`0 0 0 3px rgba(32, 89, 74, 0.25)`) on all inputs, buttons, and links.
- **Semantic HTML & ARIA:** Preserves Keycloak's `aria-invalid`, `aria-describedby`, label associations, and `role="alert"` announcements for error banners.
- **Color Contrast:** All text ratios exceed 4.5:1 on their respective backgrounds (Ink `#173f36` on Paper `#f7f7ef` is > 10:1).
- **Multi-modal Feedback:** Validation errors combine icons, high-contrast borders, and descriptive text.

### 8.6 Security Invariants Preserved
- **Zero Client Password Exposure:** Passwords are never sent to the React SPA; forms post directly to Keycloak's authenticated browser endpoint.
- **OIDC/PKCE Invariants:** Authorization Code Flow, PKCE S256, token lifetimes, and strict redirect allowlists remain 100% untouched.
- **Zero Privilege Escalation on Registration:** Public self-registration only assigns `CUSTOMER`. Tested live: newly registered user `bobcustomer` received strictly `CUSTOMER` role membership.
- **Google Broker Preservation:** Google sign-in button uses native Keycloak-generated identity broker links with a local SVG icon. Client secrets are never embedded in the theme.

### 8.7 Theme Verification Summary (Refs #44)

| Check | Scope | Result | Evidence |
|---|---|---|---|
| **Static Theme Structure** | Theme properties, parent inheritance, CSS, SVGs, messages | **Passed** | `./platform/identity/validate-identity.sh` (Checks 7–11) |
| **Running Keycloak Theme Registration** | Theme recognized and listed by Keycloak 26.8.1 | **Passed** | `apply-theme.sh` & `kcadm.sh get realms/devarom` (`loginTheme=devarom`) |
| **Branded Login UI** | Logo, typography, card layout, focus states, responsive styles | **Passed** | Chrome browser inspection; desktop (1440px) & mobile (390px) screenshots |
| **Error Feedback** | Alert banner for invalid credentials / session expiry | **Passed** | Live test with incorrect password; verified alert styling |
| **Registration Form** | Consistent brand layout, field formatting | **Passed** | Self-registration tested live; verified user in Keycloak with `CUSTOMER` role |
| **E2E Baseline & Post-Theme Login** | React SPA -> Keycloak -> Gateway -> Accounts -> PostgreSQL | **Passed** | Logged in as `testcustomer`, retrieved profile via Gateway at `/customer` |
| **Session Termination (Logout)** | RP-Initiated logout clearing session & returning to SPA | **Passed** | Verified post-logout redirect to `http://localhost:5173/` |
