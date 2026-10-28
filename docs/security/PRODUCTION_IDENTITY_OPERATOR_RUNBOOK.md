# SwiftDrop Production Identity & Security Operator Runbook

**Document Owner:** Platform / Identity & Security Lead (@Suthankan1)  
**Issue Reference:** [N10-P11] Issue #150 — Configure Production Identity, SMTP, Headers and Secret Rotation Readiness  
**Target Release:** SwiftDrop v1.3 Local-First Architecture  
**Date:** October 2026  
**Audience:** Platform Engineers, DevSecOps, System Operators, Independent Reviewers (@Harithkeshan, @Vibhushan-2003), Independent QA (@EricPraveen)

---

## 1. Identity Architecture Overview

SwiftDrop utilizes a single authoritative identity provider based on **Keycloak 26.8.1** running with a dedicated PostgreSQL database (`keycloak_db`).
The system enforces strict defense-in-depth principles:
- **Authentication:** Handled exclusively by Keycloak via OAuth 2.0 Authorization Code Flow + PKCE (RFC 7636, S256).
- **Session Tokens:** Public SPA clients store tokens strictly in memory; no refresh or access tokens are ever placed in `localStorage`, `sessionStorage`, or cookies.
- **Service Authorization:** Edge routing occurs via Spring Cloud Gateway, while all downstream business services (`accounts-service`, `pricing-service`, `delivery-service`, `communications-service`, `graphql-query-api`) independently validate JWT signatures, issuers, audiences, and tenant boundaries without shared entity models or distributed database queries.
- **Zero-Cost Policy:** Preserves strict $0 infrastructure constraints adhering to `platform/security/azure-allowlist-policy.yaml`. No cloud resources, paid SMTP, or commercial identity providers are permitted.

---

## 2. Target Environment & Approved Endpoint Matrix

In accordance with the approved architectural decisions from Issues #149 and #176, SwiftDrop operates within an ingress TLS termination boundary hosted locally on k3d/K3s with embedded Traefik.

| Component / Function | Canonical Endpoint | Audience / Scope | Protocol / Flow |
|---|---|---|---|
| **Root Local Ingress** | `https://swiftdrop.local:18443` | Local Browser | HTTPS / TLS 1.3 (Self-Signed CA) |
| **Frontend Web SPA** | `https://swiftdrop.local:18443` or `https://app.swiftdrop.local:18443` | Local Browser | Static React / Nginx |
| **API Gateway Ingress** | `https://swiftdrop.local:18443/api/*` or `https://api.swiftdrop.local:18443` | Browser to Gateway | Spring Cloud Gateway Edge Proxy |
| **Keycloak External Hostname** | `https://swiftdrop.local:18443` or `https://auth.swiftdrop.local:18443` | Browser to IdP | Reverse Proxy (`KC_PROXY_HEADERS=xforwarded`) |
| **Canonical OIDC Issuer** | `https://swiftdrop.local:18443/realms/swiftdrop` | All Relying Parties | Matches `iss` claim in all access JWTs |
| **OIDC Discovery** | `https://swiftdrop.local:18443/realms/swiftdrop/.well-known/openid-configuration` | Relying Parties | OpenID Connect 1.0 Metadata |
| **External JWKS Endpoint** | `https://swiftdrop.local:18443/realms/swiftdrop/protocol/openid-connect/certs` | Browser / External | RS256 Public Verification Keys |
| **Internal Cluster JWKS** | `http://keycloak.swiftdrop-identity.svc.cluster.local:8080/realms/swiftdrop/protocol/openid-connect/certs` | Backend Pods | Microservices intra-cluster JWT verification |
| **Authorization Endpoint** | `https://swiftdrop.local:18443/realms/swiftdrop/protocol/openid-connect/auth` | Browser Public Client | Auth Code + PKCE Challenge (S256) |
| **Token Endpoint** | `https://swiftdrop.local:18443/realms/swiftdrop/protocol/openid-connect/token` | Browser Public Client | Token exchange with PKCE verifier |
| **End-Session (Logout)** | `https://swiftdrop.local:18443/realms/swiftdrop/protocol/openid-connect/logout` | Browser Public Client | OIDC Front-Channel Logout |
| **SPA Login Callback** | `https://swiftdrop.local:18443/auth/callback` & `https://app.swiftdrop.local:18443/auth/callback` | Browser Public Client | Exact URI allowlisted; no wildcards |
| **SPA Post-Logout Callback** | `https://swiftdrop.local:18443` & `https://app.swiftdrop.local:18443` | Browser Public Client | Allowlisted post-logout redirection |
| **Google Broker Callback** | `https://swiftdrop.local:18443/realms/swiftdrop/broker/google/endpoint` | Google OAuth Broker | External IdP Redirect URI |
| **Keycloak Admin Console** | `https://swiftdrop.local:18443/admin` | Operator Loopback | Gated via `ingress.keycloak.exposeAdmin` |

---

## 3. Keycloak Production Runtime Configuration

The Keycloak container is configured for production runtime (`start --http-enabled=true`) behind Traefik:
- **`KC_HOSTNAME`**: `https://swiftdrop.local:18443`
- **`KC_HOSTNAME_STRICT`**: `true`
- **`KC_HOSTNAME_STRICT_BACKCHANNEL`**: `false` (enables backend pods to communicate via internal DNS)
- **`KC_PROXY_HEADERS`**: `xforwarded` (strictly processes `X-Forwarded-*` headers injected by Traefik)
- **`KC_HTTP_ENABLED`**: `true` (enables plaintext listener behind TLS-terminating Ingress)
- **`KC_HEALTH_ENABLED`**: `true` (exposes non-sensitive `/health/live` and `/health/ready` probes)

### Reverse Proxy & Forwarded Header Hardening
Traefik strips client-supplied `X-Forwarded-*` headers before appending authoritative proxy values, preventing host-header spoofing or cache-poisoning attacks. Client requests attempting to alter `X-Forwarded-Host` or `X-Forwarded-Proto` are ignored.

### Administrative Ingress Surface Gating
The `/admin` path and admin console subdomains are gated in Helm (`platform/kubernetes/helm/swiftdrop/values.yaml`):
```yaml
ingress:
  keycloak:
    exposeAdmin: true  # Set to false in hardened environments to restrict admin access to cluster port-forwarding
```
When `exposeAdmin` is set to `false`, `/admin` routes return HTTP 404 from the ingress controller. Operators access Keycloak admin securely via:
```bash
kubectl port-forward -n swiftdrop-identity svc/keycloak 8080:8080
```

---

## 4. OAuth 2.0 Authorization Code + PKCE Contract

The frontend React application acts strictly as an **OAuth 2.0 Public Client** (`swiftdrop-frontend`):
1. **Grant Types:**
   - Authorization Code Grant: `standardFlowEnabled: true`
   - Implicit Flow: `implicitFlowEnabled: false` (strictly prohibited)
   - Resource Owner Password Credentials (ROPC): `directAccessGrantsEnabled: false` (strictly prohibited)
2. **PKCE Enforcement:**
   - `pkce.code.challenge.method`: `S256` mandatory for all authorization requests.
   - Missing or non-S256 PKCE requests are rejected immediately by Keycloak.
3. **Session & Token Storage:**
   - Access tokens and ID tokens reside exclusively in JavaScript memory (`keycloak-js` instance).
   - Silent renewal executes via iframe or PKCE code refresh.
   - In accordance with ADR-0004, no tokens are stored in `localStorage` or `sessionStorage`.
4. **CORS & Web Origins:**
   - Allowed Web Origins: `https://swiftdrop.local:18443`, `https://app.swiftdrop.local:18443`, `http://localhost:5173`.
   - Wildcard `*` origins are strictly forbidden and rejected during realm validation.

---

## 5. Google Identity Broker Configuration

Keycloak provides built-in brokering to Google Identity (`identityProviders` alias `google`):
- **Broker Redirect URI:** `https://swiftdrop.local:18443/realms/swiftdrop/broker/google/endpoint`
- **Default Role Assignment:** `CUSTOMER` role only.
- **Role Isolation:** Public social login is strictly isolated. Social registration **never** assigns `MERCHANT`, `COURIER`, `DISPATCHER`, or `ADMIN`. Staff roles require separate administrative provisioning.
- **Email Verification Trust (`trustEmail`):** The realm sets `trustEmail: true` based on Google's email verification claim. If Google credentials are not supplied at runtime, the broker remains dormant and fails safe.
- **External Acceptance Status:** In accordance with repository constraints, external Google authentication is marked **BLOCKED** in automated local pipelines due to the lack of live Google API secrets and interactive browser consent in headless CI.

---

## 6. Native Email Verification Policy

Implemented natively within Keycloak per [docs/security/EMAIL_VERIFICATION_MFA_POLICY.md](EMAIL_VERIFICATION_MFA_POLICY.md):
- **Policy Invariant:** `verifyEmail: true`, `registrationAllowed: true`.
- **Action Token Lifespan:** 300 seconds (5 minutes) via `actionTokenGeneratedByUserLifespan: 300`.
- **Resend Cooldown:** 60-second cooldown enforced between verification link dispatches.
- **Fail-Closed Principle:** If SMTP transport fails during registration, the user account remains in an unverified state (`emailVerified: false`) and cannot authenticate into protected resources. Expired links fail closed with an error prompt instructing the user to request a fresh token.

---

## 7. Role-Based TOTP Multi-Factor Authentication (MFA)

Native RFC 6238 TOTP is configured in Keycloak without custom Java SPI plugins:
- **Algorithm & Parameters:** HMAC-SHA1, 6 digits, 30-second time-step period, lookahead window 1, single-use enforcement (`otpPolicyCodeReusable: false`).
- **Supported Applications:** Google Authenticator, Microsoft Authenticator, FreeOTP.
- **Policy by Role:**
  - `CUSTOMER`: Voluntary enrollment via account management (`kc_action=CONFIGURE_TOTP`).
  - `ADMIN` & `DISPATCHER`: **Mandatory**. Enforced programmatically using `platform/identity/configure-totp.py enforce-staff`. Unenrolled staff accounts receive `CONFIGURE_TOTP` in `requiredActions` upon next login.
  - `MERCHANT` & `COURIER`: Follows voluntary baseline pending dedicated operational approval.
- **Administrative Recovery Procedure:**
  When a staff user loses access to their authenticator device:
  ```bash
  python3 platform/identity/configure-totp.py reset-user \
    --user-id <KEYCLOAK_USER_UUID> \
    --require-re-enroll
  ```
  This securely deletes the registered `otp` credential and re-queues `CONFIGURE_TOTP` in `requiredActions`, forcing immediate pairing on subsequent login.

---

## 8. SMTP Transport Readiness & Strict $0 Policy

SwiftDrop supports dual-mode SMTP transport with strict $0 financial boundaries:

### Mode A: Local Integration Testing (Mailpit)
- Service: `axllent/mailpit:v1.27.4`
- Host/Port: `mailpit:1025`
- Web UI: Bound exclusively to `127.0.0.1:8025`
- Scope: In-memory mail capture only; zero real outbound emails; 100% free of charge.

### Mode B: External Delivery Candidate (Strictly Gated)
- Any production SMTP provider must satisfy `platform/security/azure-allowlist-policy.yaml`:
  - Zero cost ceiling (`cost_ceiling_usd: 0.00`).
  - No metered overage or credit card requirement.
  - Zero consumption of promotional credits.
- **Current Operational Status:** External public email delivery is marked **BLOCKED** because no zero-cost, unmetered external SMTP relay has been formally approved and provisioned. All automated tests utilize local Mailpit.

---

## 9. Security Headers, CSP and Cookie Policy

### Content Security Policy (CSP)
Keycloak and the frontend SPA Nginx container enforce strict Content-Security-Policy headers:
- `default-src 'self'`
- `connect-src 'self' https://swiftdrop.local:18443 https://*.swiftdrop.local:18443`
- `frame-ancestors 'self'` (Keycloak) / `frame-ancestors 'none'` (SPA)
- `script-src 'self'`
- `style-src 'self' 'unsafe-inline'`

### Additional HTTP Headers
- **HSTS:** `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- **MIME Sniffing:** `X-Content-Type-Options: nosniff`
- **Framing Protection:** `X-Frame-Options: SAMEORIGIN` (Keycloak) / `DENY` (SPA)
- **Referrer Policy:** `Referrer-Policy: strict-origin-when-cross-origin`

### Secure Cookies
All Keycloak authentication session cookies (`AUTH_SESSION_ID`, `KEYCLOAK_IDENTITY`, `KEYCLOAK_SESSION`) enforce:
- `Secure: true` (transmitted exclusively over HTTPS)
- `HttpOnly: true` (inaccessible to browser JavaScript)
- `SameSite: Lax` (standard OAuth redirect compatibility while preventing CSRF)

---

## 10. Externalized Secrets & Kubernetes Secret References

All sensitive credentials are externalized from Helm values, ConfigMaps, and source code:

| Secret Name | Namespace | Keys | Injected Pod / Service |
|---|---|---|---|
| `keycloak-admin-secret` | `swiftdrop-identity` | `admin-username`, `admin-password` | Keycloak Bootstrap Admin |
| `keycloak-db-secret` | `swiftdrop-identity` | `username`, `password` | Keycloak PostgreSQL Connection |
| `swiftdrop-google-oidc-secret` | `swiftdrop-identity` | `client-id`, `client-secret` | Google Identity Broker |
| `swiftdrop-smtp-secret` | `swiftdrop-identity` | `host`, `port`, `username`, `password`, `from`, `auth`, `ssl`, `starttls` | Keycloak SMTP Transport |
| `swiftdrop-tls-cert` | `swiftdrop-system` | `tls.crt`, `tls.key` | Traefik Ingress TLS Termination |

### Provisioning Local Development Secrets
To provision synthetic, zero-leakage secrets in a local k3d cluster:
```bash
./platform/kubernetes/helm/scripts/create-dev-secrets.sh
```

---

## 11. Credential & Signing-Key Rotation Procedures

All rotation workflows are operator-controlled and executable via:
```bash
python3 platform/identity/rotate-identity-secrets.py <action>
```

### 11.1 Secret Rotation Inventory
1. **`keycloak-admin`**: Keycloak Master Bootstrap Admin Credentials
2. **`keycloak-db`**: Keycloak PostgreSQL Database Role Password
3. **`google-oauth`**: Google Cloud Console OAuth 2.0 Client Secret
4. **`smtp-relay`**: SMTP Relay Transport Credentials
5. **`realm-signing-keys`**: RS256 Cryptographic Keypair (Token Signing)
6. **`local-tls-cert`**: Ingress TLS Certificate & Private Key

### 11.2 Realm Signing-Key Rollover (Zero Downtime)
Keycloak supports dual-key overlapping validity in JWKS:
1. **Phase 1 (Introduction):** Add new `rsa-generated` key provider in Keycloak admin with higher priority (e.g., priority `200` vs existing priority `100`).
2. **Phase 2 (Dual Publication):** Keycloak automatically advertises both public keys on the `/protocol/openid-connect/certs` (JWKS) endpoint.
3. **Phase 3 (Active Signing):** New JWTs are signed with the new key (`kid="key-2026-v2"`). Previously issued tokens signed with `kid="key-2026-v1"` remain valid across downstream services for their remaining TTL (300 seconds).
4. **Phase 4 (Retirement):** After a grace period exceeding maximum token lifespan (5 minutes), disable and delete the retired key provider.
5. **Rollback Procedure:** If an issue is detected during rollover, instantly reduce the new key's priority below the old key (e.g. priority `50`). Keycloak immediately cuts back to signing with the original key without downtime.

---

## 12. Identity Database & User State Preservation

**CRITICAL INVARIANT:** Operational procedures must never destructively drop or prune identity data.
- **Prohibited Commands:**
  - `docker compose down -v`
  - `docker volume prune -f`
  - `kubectl delete pvc -l app=keycloak-postgres`
  - Dropping logical database `keycloak_db`
- **Backup Verification:**
  Before executing schema migrations or configuration cutovers, take a cold or logical backup:
  ```bash
  python3 platform/identity/backup-restore-identity.py backup
  ```
  All user UUIDs (`sub`), credentials, and role mappings must remain stable across updates.

---

## 13. Verification & Troubleshooting

### Diagnostic Checklist
1. **Validate Static Realm Invariants:**
   ```bash
   ./platform/identity/validate-identity.sh
   ```
2. **Execute Full Identity Test Suite:**
   ```bash
   python3 -m unittest discover -s platform/identity/tests -p "test_*.py" -v
   ```
3. **Verify Rotation Readiness Dry-Run:**
   ```bash
   python3 platform/identity/rotate-identity-secrets.py dry-run
   ```
4. **Simulate Key Rollover & Rollback:**
   ```bash
   python3 platform/identity/rotate-identity-secrets.py simulate-key-rotation
   ```
5. **Verify Helm Chart Templates & Lint:**
   ```bash
   helm lint platform/kubernetes/helm/swiftdrop
   helm template swiftdrop platform/kubernetes/helm/swiftdrop -f platform/kubernetes/helm/swiftdrop/values-local.yaml
   ```
