# SwiftDrop — Keycloak Identity & OIDC Foundation

**Module:** Platform & Security · **Owner:** Suthankan1 · **Target Milestone:** November 1, 2026 · **Issue:** [Refs #2](https://github.com/Suthankan1/swiftdrop/issues/2) · **Architecture Baseline:** SwiftDrop v1.3

This directory contains the reproducible Keycloak identity foundation for the SwiftDrop delivery platform. It defines the canonical `swiftdrop` realm (`realm-swiftdrop.json`), public OpenID Connect (OIDC) client (`swiftdrop-frontend`) for the React Single Page Application (SPA), audience (`swiftdrop-api`), custom branded login theme (`swiftdrop`), role-based access control (RBAC) definitions, claims mapping for Spring Security Resource Servers, and Google OAuth 2.0 identity federation brokering.

> [!NOTE]
> **Phase 3C Canonical Alignment:** The canonical configuration for fresh installations is [`realm-swiftdrop.json`](realm-swiftdrop.json) with loginTheme `swiftdrop`. Existing running containers preserve their internal realm ID (`devarom`) to ensure continuous subject UUID and database profile immutability without data loss. Legacy [`realm-devarom.json`](realm-devarom.json) and theme directory `themes/devarom` are preserved for historical audit provenance and live migration safety.

---

## 1. Pinned Keycloak Version & Engine

| Property | Value | Rationale |
|---|---|---|
| **Image** | `quay.io/keycloak/keycloak:26.8.1` | Pinned exact stable release (released October 2026). Avoids unpredictable breaking changes from `latest`. |
| **Engine Runtime** | Quarkus-based Keycloak distribution | Modern official Keycloak architecture. Deprecated WildFly distribution and legacy client adapters are completely avoided. |
| **Local Binding Port** | `8090` | Pinned per [Login Contract](../../docs/contracts/LOGIN_CONTRACT.md) to eliminate host port collisions with Spring Cloud Gateway (`8080`). |
| **Local Storage Mode** | Embedded dev database (dev mode) | Development and smoke-testing use Keycloak's embedded storage. Production / shared stack will bind to dedicated logical PostgreSQL database `keycloak_db` (Issue #3). |

The custom image replaces the base image's vulnerable Guava `33.6.0-jre` with
`33.7.2-jre` for CVE-2026-102554. Its build verifies Maven Central's SHA-256
checksum and rebuilds Quarkus metadata while preserving the original fast-jar
classpath filename. Dependencies are never downloaded at startup. The official
Keycloak version, realm configuration and runtime command remain unchanged.
This patch applies to images built from this Dockerfile; using the upstream
image directly does not include it. Remove the override when the pinned
upstream image contains the fixed dependency.

---

## 2. Realm Architecture (`swiftdrop`)

The canonical realm is defined in [`realm-swiftdrop.json`](realm-swiftdrop.json) (with legacy baseline retained in [`realm-devarom.json`](realm-devarom.json)) as a sanitized, version-controlled configuration with zero hardcoded passwords or sensitive secrets.

### 2.1 Five Core Application Roles
Keycloak manages authentication and realm roles (`realm_access.roles`). SwiftDrop defines five core business roles:

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

Keycloak brokers federated identity with Google Sign-In. The browser application talks only to Keycloak; a Google token is **never** a SwiftDrop API bearer token.

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
2. Select the existing Google Cloud project (recorded legacy name `devarom`). Its project ID and OAuth credentials remain unchanged in Phase 1; do not create replacement cloud resources for branding.
3. Configure the **OAuth consent screen**:
   - **User Type:** External.
   - **App name:** SwiftDrop (visible consent-screen branding; requires an authorized Google Cloud console update, not changed by repository files).
   - **Publishing status:** Testing (standard for development).
   - **Test users:** Add the authorized Google accounts of developers testing the login flow (e.g., developer@gmail.com). Google restricts login in Testing mode exclusively to listed test users; unauthorized accounts receive `403 access_denied`.
   - **Scopes:** `.../auth/userinfo.email`, `.../auth/userinfo.profile`, `openid`.
4. Create an **OAuth 2.0 Client ID** of type **Web application**:
   - **Name:** `Devarom Keycloak Identity Broker`
   - **Authorized redirect URIs:**
     ```text
     http://localhost:8090/realms/devarom/broker/google/endpoint
     ```
   - **Authorized JavaScript origins:**
     ```text
     http://localhost:8090
     ```
5. Copy the generated **Client ID** and **Client Secret** into your local `platform/identity/.env` file. Never commit `.env` or paste secrets into frontend `VITE_*` configuration.
6. To activate Google login on the Keycloak login screen, set `enabled: true` on the Google provider using `kcadm.sh` or the Keycloak Admin Console:
   ```bash
   # Enable Google IdP on running Keycloak container:
   docker exec devarom-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
     --server http://localhost:8080 --realm master --user admin --password <bootstrap-pass>
   docker exec devarom-keycloak /opt/keycloak/bin/kcadm.sh update realms/devarom/identity-provider/instances/google -s enabled=true
   ```

### 5.3 Automated Verification & Negative Testing
An automated test suite validates the broker configuration, contract parameters, live Google OAuth acceptance, and negative callback failure paths without exposing secrets:

```bash
# Run standalone Google OIDC broker smoke test:
python3 platform/identity/tests/google_broker_smoke.py

# Run static configuration and privilege confinement tests:
python3 platform/identity/tests/test_google_broker_configuration.py

# Full identity security validation (static 14/14 + live smoke checks A–H):
./platform/identity/validate-identity.sh
```

**Verifications performed:**
- Keycloak intercepts `kc_idp_hint=google` and delegates to the Google broker (HTTP 303).
- Constructs strict Google authorization URL with `client_id`, `redirect_uri=http://localhost:8090/realms/devarom/broker/google/endpoint`, `scope=openid profile email`, PKCE, `state`, and `nonce`.
- Google OAuth endpoint accepts the registered credentials and authorized redirect URI (HTTP 302 to Google signin flow).
- Negative test: Google strictly rejects unapproved redirect URIs with `redirect_uri_mismatch`.
- Negative test: Keycloak rejects tampered/unmatched callback state with HTTP 400 Bad Request.
- Negative test: Keycloak rejects OAuth cancellation/`access_denied` with HTTP 400 Bad Request.
- Role isolation: newly federated broker identities receive strictly `CUSTOMER` role via `default-roles-devarom`; zero staff roles (`MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`).

*Status of Acceptance Item `L03`:* **BLOCKED / NOT VERIFIED (External Human Consent Gate)**. All automated broker invariants, contract parameters, Google server probing, and negative security controls pass. Live interactive sign-in on `accounts.google.com` requires human interaction with an authorized Google test user; automated agents must not fabricate personal Google credentials or bypass consent.

---

## 6. Local Startup & Verification Commands

### 6.1 Prerequisites
- Docker engine and Docker Compose installed and running.
- `curl` and `jq` installed locally.

### 6.2 Starting Keycloak Locally
For an **existing** container, use the non-destructive Phase A upgrade in §9.
The embedded dev database lives in that container's writable layer; a generic
Compose `up` after configuration changes may recreate it and lose identities.

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
docker compose -f platform/identity/docker-compose.identity.yml --project-directory platform/identity stop
```

Do not use `down`, `rm`, `--force-recreate`, or `down -v` against an existing
embedded identity database. Persistent identity PostgreSQL remains a separate
platform slice; this task does not migrate storage.

---

## 7. Evidence & Acceptance Register (Refs #2)

| Acceptance Item | Description | Status | Evidence |
|---|---|---|---|
| **Realm Configuration** | `devarom` realm with exact pinned Keycloak 26.8.1 | **Verified** | `realm-devarom.json`, `docker-compose.identity.yml` |
| **Role Model** | 5 application roles; no self-elevation to staff/merchant | **Verified** | `validate-identity.sh` check 3; `default-roles-devarom` has `CUSTOMER` only |
| **Public SPA Client** | `devarom-frontend` code + PKCE S256, no client secret | **Verified** | `validate-identity.sh` check 4; rejected plain PKCE in live smoke test |
| **Redirect Security** | Strict local origins; zero wildcards | **Verified** | `validate-identity.sh` check 5; rejected attacker redirect with HTTP 400 |
| **Secrets Absent from Git** | Zero credentials in repo; `.env` gitignored | **Verified** | `git check-ignore -v platform/identity/.env` (`.gitignore:2:.env`) |
| **Google Federation** | Google IdP brokering configuration & live delegation verified | **Configured & Validated (Live interactive login gated)** | `validate-identity.sh` check 14 & Check H; `google_broker_smoke.py` (Keycloak delegation, Google OAuth URI probe, negative controls passed; interactive login gated on human test-user consent) |

---

## 8. Custom Branded Login Theme (`platform/identity/themes/devarom/login`)

**Issue:** [Refs #44](https://github.com/Suthankan1/swiftdrop/issues/44) · **Theme Name:** `devarom` · **Parent Theme:** `keycloak.v2`

A fully responsive, accessible, and professionally branded Keycloak login, registration, and error recovery theme matching the SwiftDrop React Single Page Application design language.

### 8.1 Theme Architecture & Directory Layout

```text
platform/identity/themes/devarom/login/
├── theme.properties                  # Declares parent=keycloak.v2, styles, scripts
├── template.ftl                      # Accessible layout wrapper, SwiftDrop header & alert banner
├── footer.ftl                        # Branded card footer with encrypted session badge
├── messages/
│   └── messages_en.properties        # Custom branded copy and error labels
└── resources/
    ├── css/
    │   ├── styles.css                # Base stylesheet
    │   └── devarom.css               # SwiftDrop design tokens, card styling, responsive rules
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

## 9. Issue #47 Phase A — native email verification and local SMTP

Owner: **Suthankan1**. Only native email ownership links are included. TOTP
(Phase B) and the email OTP decision (Phase C) remain open. No React verification
endpoint, custom token store, plugin, password grant, or new service is added.

### Policy and native behavior

The fresh-import realm sets `verifyEmail: true` and retains
`registrationAllowed: true`. SMTP uses `mailpit:1025` on the existing Compose
network, with the synthetic sender `no-reply@devarom.test`, no authentication and
no TLS on this local container connection. Mailpit v1.27.4 captures messages;
it has no forwarding/relay settings or published SMTP port. Its web UI/API binds
only `127.0.0.1:${MAILPIT_UI_PORT:-8025}`. It is a development tool, never a
production SMTP provider. Captured messages are ephemeral, bounded to 100, and
contain sensitive action links: do not share screenshots of mail bodies/URLs.

With Keycloak 26.8.1, registration normally collects profile details first,
verifies email, then asks the user to create a password. Keep this native order.
The required-action array is intentionally **omitted** from the realm import:
a partial list would replace native defaults, including `UPDATE_PASSWORD`.
Fresh import retains the native 30-second resend cooldown; the runtime upgrade
merges `VERIFY_EMAIL.config.emailResendCooldownSeconds: "60"` into the existing
action. It keeps `defaultAction: false`; the realm verification flag evaluates
unverified accounts without requiring verified accounts to repeat the action.
This is a per-user native resend cooldown, not comprehensive registration/IP
flood protection. Production edge abuse controls require a separately reviewed
configuration; this task does not introduce account lockout.

User-generated links retain the existing **300-second** lifetime. The updater
checks the effective `actionTokenGeneratedByUserLifespan.verify-email` override
and native password action before changing policy. Invalid/tampered/expired
links fail in Keycloak. Tokens bind the subject and email; a different logged-in
identity is rejected. Native link reuse can show “already verified”; these
links are **not described as strictly single-use**. Reuse must not verify a
different user, create another session improperly, or grant privileged roles.

### Fresh setup versus existing realm upgrade

For a new installation, copy `.env.example` to ignored `.env`, supply your own
bootstrap password, and run the §6 startup command. Do not overwrite an existing
`.env`. Existing admin, Google and database values are preserved. The import
contains safe local SMTP defaults; changes in `.env` are read by the upgrade
helper, not applied to an existing realm merely by restarting Keycloak.

For an existing installation:

```bash
# Preserve the existing container and its embedded identity data.
# Inspect local port 8025 (or choose MAILPIT_UI_PORT) before starting Mailpit.
docker start devarom-keycloak
docker compose -f platform/identity/docker-compose.identity.yml --project-directory platform/identity up -d --no-deps mailpit
```

In Keycloak's Admin Console, create an **enabled disposable customer**, with a
unique synthetic `.test` mailbox, no password, and no privileged roles. Copy its
user ID (not an email link). This uses an ordinary test customer; no additional
administrator account is needed. Then run:

```bash
python3 platform/identity/configure-email-verification.py apply --test-user-id <synthetic-customer-id>
python3 platform/identity/configure-email-verification.py check
```

The helper reads `.env` as data without sourcing/executing it; environment
values can override defaults. Credentials and the short-lived admin token stay
in memory and are never printed. Admin/Mailpit URLs are loopback only, HTTP
redirects and proxy forwarding are disabled. The helper:

1. Checks the existing realm/action baseline, expiry and disposable user roles.
2. Saves only prior SMTP/verification/action settings in ignored
   `.local/email-verification-recovery.json`, mode `0600`.
3. Stages only SMTP, keeping the current verification gate unchanged.
4. Calls Keycloak's native `send-verify-email` for that customer with lifespan
   300, and confirms a **new actual SMTP message** in Mailpit for the mailbox.
5. Merges the cooldown, enables `verifyEmail`, then reads back the narrow policy.
   Failures restore the previous SMTP/action/gate. Repeated apply confirms mail
   delivery without rewriting an already matching policy.

The helper never updates users, passwords, roles, clients, Google configuration,
keys, or token/session lifetimes. Remove the disposable test customer and only
its captured messages after testing; never bulk-mark existing users verified.
If the existing administrator already has a synthetic `.test` mailbox, omitting
`--test-user-id` instead uses native `testSMTPConnection` and confirms receipt
before any SMTP settings are persisted. That endpoint sends to the authenticated
administrator; it has no custom recipient override. No admin mailbox is edited.

For an older container without the theme bind mount, copy the updated theme and
restart that same container to clear theme/message caches, without recreating it:

```bash
docker cp platform/identity/themes/devarom/. devarom-keycloak:/opt/keycloak/themes/devarom/
docker restart devarom-keycloak
```

The native verification/info/error templates remain inherited. Message overrides
and wrapping use the existing SwiftDrop card, typography, colors and alert styles;
all native action/resend links remain intact. Original-session verification
continues through native password setup/callback. Another browser may display a
native confirmation/success screen and require a fresh sign-in.

### Existing users, Google, and recovery

Existing local unverified users encounter `VERIFY_EMAIL` at their next
interactive login. Existing verified users continue normally. Existing JWTs and
SSO/refresh sessions are **not retroactively revoked** by this flag; do not claim
instant invalidation. Retiring existing sessions would require separate approval.
Changing an email requires proving ownership of the new address through native
Keycloak behavior. No migration sets `emailVerified` in bulk.

The existing Google broker and `trustEmail` policy are preserved. Keycloak can
honor a verified broker email; an explicitly unverified broker email must still
be verified. Google consent/real Google login remains a separate, unverified
acceptance item; a broker button is not evidence of successful Google sign-in.

If mail delivery fails, keep the gate and **repair/start Mailpit first**, then
retry the native resend after cooldown. Expired links require a newly sent link;
never extend expiry or mark users verified to make a test succeed. For a reviewed
local rollback of this upgrade only:

```bash
python3 platform/identity/configure-email-verification.py recover
```

Recovery restores the private snapshot's prior SMTP, gate and `VERIFY_EMAIL`
configuration. It cannot restore a masked external SMTP secret and refuses such
an upgrade. Already queued user `VERIFY_EMAIL` actions remain, so toggling the
realm flag alone may not unblock affected users. Inspect individual accounts
with the owner; repair SMTP rather than deleting required actions in bulk.
The helper never deletes queued actions or resets credentials. Preserve the
private snapshot for recovery; review/archive it privately before a different
policy migration.

### Production SMTP and focused verification

Production must use approved SMTP on the Keycloak server, a verified sender,
TLS/STARTTLS, protected provider credentials, certificate validation, and tested
delivery/recovery. Configure via supported Admin Console/API or server-side
secret injection, e.g. placeholders `${SMTP_HOST}`, `${SMTP_PORT}`,
`${SMTP_FROM}`, `${SMTP_USER}`, `${SMTP_PASSWORD}`. Never put credentials in
`VITE_*`, commit an expanded realm export, or use the dev Mailpit Compose there.
The helper deliberately accepts **local Mailpit only**; it is not a production
credential provisioning tool. Do not change an existing Google broker to pass
SMTP tests.

```bash
./platform/identity/validate-identity.sh
python3 -m unittest discover -s platform/identity/tests -p 'test_*.py'
# Actual native HTTP registration, Mailpit delivery, PKCE and profile regression:
python3 platform/identity/tests/email_verification_smoke.py --include-expiry
```

Static checks do not prove mail delivery. The live harness uses only disposable
synthetic identities; it prints sanitized outcomes, never links/passwords/JWTs.
See the dated evidence in `docs/project/STATUS.md` for what actually ran and
which browser/full-stack checks remain unverified.

Version references: [Keycloak 26.8.1 admin guide](https://www.keycloak.org/docs/26.8.1/server_admin/),
[native verification handler](https://github.com/keycloak/keycloak/blob/26.8.1/services/src/main/java/org/keycloak/authentication/actiontoken/verifyemail/VerifyEmailActionTokenHandler.java),
[native cooldown](https://github.com/keycloak/keycloak/blob/26.8.1/services/src/main/java/org/keycloak/authentication/requiredactions/util/EmailCooldownManager.java),
[Mailpit Docker configuration](https://mailpit.axllent.org/docs/install/docker/).

---

## 10. Issue #47 Phase B — native authenticator-app TOTP multi-factor authentication

Owner: **Suthankan1**. Implements native Keycloak 26.8.1 authenticator-app Time-based
One-Time Password (TOTP) enrollment, multi-factor authentication challenge, and
administrative recovery flows (Issue #47 Phase B). Only standard authenticator apps
(Google Authenticator, Microsoft Authenticator, FreeOTP) are supported via RFC 6238.
Phase C (email OTP code feasibility) remains an open decision. No React-side OTP form,
custom cryptographic secrets, custom token store, or direct access grant is introduced.

### Policy and native behavior

The `realm-devarom.json` foundation configures the RFC 6238 native OTP policy:
- **Policy Type:** `totp` (Time-based One-Time Password)
- **Algorithm:** `HmacSHA1`
- **Digits:** `6`
- **Period:** `30` seconds
- **Look-ahead Window:** `1` (permits ±30s clock drift tolerance)
- **Replay Prevention:** `otpPolicyCodeReusable: false` (strictly single-use; codes cannot be reused within the active time window)
- **Supported Applications:** `totpAppGoogleName`, `totpAppMicrosoftAuthenticatorName`, `totpAppFreeOTPName`

### Safe initial enrollment policy & browser flow

Keycloak's native browser authentication flow (`Browser - Conditional 2FA`) features
the subflow `Condition - user configured` (`conditional-user-configured`):
- **Unenrolled Users:** The condition evaluates to false; unenrolled customers log in
  seamlessly with their primary credentials without challenge, avoiding lockout.
- **Enrolled Users:** Once a user registers an authenticator credential, the condition
  evaluates to true and Keycloak prompts for the TOTP code via `login-otp.ftl`.
- **Action Configuration:** The `CONFIGURE_TOTP` required action is registered with
  `defaultAction: false`, ensuring MFA is optional for `CUSTOMER` by default while
  permitting administrators to require MFA per user or users to voluntarily enroll.
- **Voluntary Enrollment (Application-Initiated Action):** Users can trigger voluntary
  enrollment from the frontend by initiating an OIDC authorization flow with
  `kc_action=CONFIGURE_TOTP`. Keycloak provides an accessible `#cancelTOTPBtn` allowing
  the user to decline without breaking their authentication session.

### Branded Keycloak theme integration

Branded screens extend `keycloak.v2` and strictly apply SwiftDrop design tokens:
- **`login-config-totp.ftl`:** Branded heading, description, QR code container enclosed
  in a dedicated high-contrast white card (`#kc-totp-secret-qr-code`) for dark mode legibility,
  monospace manual key display (`#kc-totp-secret-key`), device name input, and 6-digit TOTP input.
- **`login-otp.ftl`:** Branded MFA challenge form with prominent 20px centered digit input
  (`#otp`), device selection (when multiple credentials exist), and clear error messages.
- **Responsive & Accessible:** Fluid card layout tested down to 360px small mobile displays
  with zero horizontal overflow, WCAG 2.1 AA compliant color contrast, and visible focus rings.

### Administrative recovery & credential management

If a user loses access to their authenticator device, administrators can safely reset their
MFA credentials using the loopback CLI helper without exposing secrets:

```bash
# Check existing runtime TOTP configuration
python3 platform/identity/configure-totp.py check

# Safely reset lost device MFA for a specific user and queue fresh setup on next login
python3 platform/identity/configure-totp.py reset-user <username>

# Restore previous realm OTP policy from private 0600 snapshot
python3 platform/identity/configure-totp.py recover
```

The reset workflow deletes existing OTP credentials via Keycloak's Admin API and appends
`CONFIGURE_TOTP` to the user's `requiredActions`. Upon their next login, the user is safely
prompted to pair their new authenticator device.

### Verification and regression suite

```bash
# Comprehensive security invariant and static structure validation:
./platform/identity/validate-identity.sh

# Complete unit and mock integration suite:
python3 -m unittest discover -s platform/identity/tests -p 'test_*.py'

# Live native TOTP MFA integration & smoke test suite:
python3 platform/identity/tests/totp_smoke.py

# Live Phase A email verification regression:
python3 platform/identity/tests/email_verification_smoke.py
```

All test harnesses use strictly synthetic disposable identities (`dv47b-*`) and perform
automatic cleanup on exit. Secrets, OTP codes, and tokens are kept in memory and never logged.

---

## 11. Issue #47 Phase C — Email OTP Feasibility, Security Architecture & Decision

Owner: **Suthankan1**. Reviews the feasibility, security threat model, and architectural
decision regarding typed Email OTP codes in SwiftDrop's Keycloak platform (Issue #47 Phase C).

### Summary of findings & decision gate

1. **Native Support in Keycloak 26.8.1:** Keycloak core provides **no native typed Email OTP authenticator**.
   Keycloak's native email mechanisms are strictly action-token links (`VERIFY_EMAIL`, `UPDATE_PASSWORD`),
   and its core `OTPPolicy` applies exclusively to RFC 6238 TOTP and RFC 4226 HOTP (app/hardware tokens).
2. **Evaluated Use Cases:**
   - *Use Case A (Signup Verification):* Typed codes degrade entropy from $\ge 256$ bits (cryptographic
     action token) to 20 bits ($10^6$ combinations) and introduce brute-force attack vectors without adding security.
   - *Use Case B (Login MFA):* Violates NIST SP 800-63B §5.1.3.3 factor independence (fate sharing between
     email password reset and email OTP); unencrypted intermediate SMTP hops; lack of phishing resistance;
     and transforms external SMTP latency/outages into total authentication denial of service.
   - *Use Case C (Recovery & Step-Up):* Emailed codes offer insufficient assurance for high-risk actions.
3. **Formal Architectural Decision ([ADR-0009](../../docs/architecture/adr/ADR-0009.md)):**
   - **Primary Recommendation (OUTCOME A):** Do NOT implement typed Email OTP. Retain Keycloak's native signed
     email verification links (PR #48) and native RFC 6238 Authenticator-App TOTP (PR #49).
   - **Contingency Specification (OUTCOME C):** If product management or security stakeholders explicitly
     mandate typed email codes, that work is gated to a separate, independently scoped follow-up issue to build
     an in-house Keycloak SPI (`DevaromEmailOtpAuthenticatorFactory`) conforming to the strict mitigations
     specified in [EMAIL_OTP_THREAT_MODEL.md](../../docs/security/EMAIL_OTP_THREAT_MODEL.md) (CSPRNG, 180s expiry,
     max 3 attempts, 60s cooldown, salted HMAC session binding, and zero secrets logging).
4. **Detailed Architecture & Security References:**
   - Feasibility Study: [`docs/architecture/EMAIL_OTP_FEASIBILITY.md`](../../docs/architecture/EMAIL_OTP_FEASIBILITY.md)
   - Threat Model & Invariants: [`docs/security/EMAIL_OTP_THREAT_MODEL.md`](../../docs/security/EMAIL_OTP_THREAT_MODEL.md)
   - Architecture Decision Record: [`docs/architecture/adr/ADR-0009.md`](../../docs/architecture/adr/ADR-0009.md)

---

## 12. Issue #150 — Production Identity, SMTP, Headers & Secret Rotation Readiness

Owner: **Suthankan1** (Platform & Security Lead) · **Issue:** [Refs #150](https://github.com/Suthankan1/swiftdrop/issues/150) · **Handoff:** [ISSUE_150_TO_159_IDENTITY_HANDOFF.md](../../docs/handoff/ISSUE_150_TO_159_IDENTITY_HANDOFF.md)

Delivers production-grade identity hardening for SwiftDrop's local-first architecture:
1. **Authoritative Ingress & HTTPS Issuer:**
   - Ingress Origin: `https://swiftdrop.local:18443` (and subdomains `app.`, `api.`, `auth.swiftdrop.local:18443`).
   - Canonical Issuer: `https://swiftdrop.local:18443/realms/swiftdrop` (matches `iss` claim in all issued tokens).
   - Ingress Admin Surface: `/admin` path gated via `ingress.keycloak.exposeAdmin` in Helm chart values.
2. **Reverse Proxy & Runtime Mode:**
   - Keycloak container runs in production mode (`start --http-enabled=true`).
   - Proxy header handling: `KC_PROXY_HEADERS=xforwarded` strictly processes headers from embedded Traefik.
3. **Role-Based TOTP MFA Enforcement:**
   - `ADMIN` and `DISPATCHER` roles require mandatory TOTP pairing via `configure-totp.py enforce-staff`.
   - `CUSTOMER` retains voluntary MFA configuration.
   - Non-destructive administrative reset helper: `configure-totp.py reset-user --user-id <UUID> --require-re-enroll`.
4. **Strict $0 SMTP Policy:**
   - Local Mailpit capture sink (`mailpit:1025`) used for synthetic verification testing.
   - Real external SMTP delivery remains gated and marked `BLOCKED` until a verified $0 provider is authorized.
5. **Security Headers & CSP:**
   - Keycloak `browserSecurityHeaders` and SPA `nginx.conf` enforce CSP, HSTS (`max-age=31536000`), `X-Content-Type-Options: nosniff`, and frame restrictions.
6. **Secret Externalization & Rotation Readiness:**
   - All credentials externalized to Kubernetes Secrets (`keycloak-admin-secret`, `keycloak-db-secret`, `swiftdrop-google-oidc-secret`, `swiftdrop-smtp-secret`, `swiftdrop-tls-cert`).
   - Reversible rotation tool: [`rotate-identity-secrets.py`](rotate-identity-secrets.py) (`inventory`, `dry-run`, `simulate-key-rotation`).
   - Comprehensive operator documentation: [`docs/security/PRODUCTION_IDENTITY_OPERATOR_RUNBOOK.md`](../../docs/security/PRODUCTION_IDENTITY_OPERATOR_RUNBOOK.md).
