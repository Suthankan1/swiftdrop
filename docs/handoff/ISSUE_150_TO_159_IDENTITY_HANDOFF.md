# Operational Handoff Contract: Issue #150 to Issue #159

**Producer Issue:** [N10-P11] Configure Production Identity, SMTP, Headers and Secret Rotation Readiness ([#150](https://github.com/Suthankan1/swiftdrop/issues/150))  
**Producer Feature Branch:** `feat/n10-p11-production-identity-security`  
**Target Integration Branch:** `test`  
**Primary Implementer:** @Suthankan1 (Platform / Identity & Security Lead)  
**Consumer Issue (Independent QA):** [N10-P11A] Independent identity, browser, and email recovery acceptance ([#159](https://github.com/Suthankan1/swiftdrop/issues/159))  
**Consumer Owner:** @EricPraveen (Independent QA / Security Acceptance)  
**Date:** October 2026  
**Architecture Baseline:** SwiftDrop v1.3 Local-First Architecture  

---

## 1. Executive Summary & Purpose

This document provides the authoritative, sanitized technical handoff from **Issue #150** to **Issue #159**.
Issue #150 delivers the hardened, production-oriented identity runtime, HTTPS OIDC contract, externalized secrets, role-based MFA enforcement, and credential rotation readiness for SwiftDrop's local-first architecture.
Issue #159 is an independent testing and QA activity owned exclusively by **@EricPraveen** to execute browser automation, email capture validation, and MFA recovery verification against this environment.

---

## 2. Division of Responsibility & Ownership Boundaries

| Dimension | Issue #150 (Platform / @Suthankan1) | Issue #159 (Independent QA / @EricPraveen) |
|---|---|---|
| **Identity Configuration** | Production Keycloak runtime, HTTPS issuer, OIDC endpoints, Helm wiring, secret references | Independent validation of login, session tokens, and redirect behavior |
| **Email Verification** | Native Keycloak policy (`verifyEmail: true`, 300s TTL, 60s cooldown, fail-closed) | Browser execution of self-registration, link clicking, and replay rejection |
| **MFA Enforcement** | Programmatic staff MFA enforcement (`enforce-staff`) and recovery CLI (`reset-user`) | Synthetic test execution of QR code pairing, TOTP challenge, and recovery workflow |
| **SMTP Delivery** | Local Mailpit integration sink (`mailpit:1025`) & secret externalization | Verification of synthetic email delivery and verification link parsing |
| **Security Headers** | Ingress, Gateway, Keycloak, and Nginx CSP/HSTS/Cookie configuration | Browser security header audit, cookie attribute inspection, and frame resistance checks |
| **Rotation Readiness** | Reversible secret rotation inventory, dry-run, and signing-key rollover tools | Verification of downstream stability during synthetic key rollover |
| **Sign-Off Authority** | Submits implementation PR and sanitized handoff; marks #150 ready for review | Independently signs off on Issue #159 browser acceptance criteria |

---

## 3. Sanitized Environment & Endpoint Contract

All endpoints operate under the local HTTPS domain infrastructure defined by Issue #149 and Issue #176:

| Parameter | Tested Value / URI |
|---|---|
| **Base HTTPS Origin** | `https://swiftdrop.local:18443` |
| **Frontend Web SPA URL** | `https://swiftdrop.local:18443` or `https://app.swiftdrop.local:18443` |
| **Canonical OIDC Issuer** | `https://swiftdrop.local:18443/realms/swiftdrop` |
| **OIDC Discovery Endpoint** | `https://swiftdrop.local:18443/realms/swiftdrop/.well-known/openid-configuration` |
| **External JWKS Endpoint** | `https://swiftdrop.local:18443/realms/swiftdrop/protocol/openid-connect/certs` |
| **SPA Login Callback URI** | `https://swiftdrop.local:18443/auth/callback` |
| **SPA Post-Logout Callback** | `https://swiftdrop.local:18443` |
| **Google Broker Callback** | `https://swiftdrop.local:18443/realms/swiftdrop/broker/google/endpoint` |
| **Client ID** | `swiftdrop-frontend` (Public Client, PKCE S256 enforced) |
| **Keycloak Version** | `26.8.1` (Pinned) |
| **Keycloak Admin Path** | `https://swiftdrop.local:18443/admin` (Gated via `ingress.keycloak.exposeAdmin`) |

---

## 4. Identity & Verification Policy Specifications

### 4.1 Native Email Verification
- **Self-Registration:** Enabled (`registrationAllowed: true`).
- **Initial Verification State:** Unverified (`emailVerified: false`).
- **Action Link Lifespan:** Strictly **300 seconds (5 minutes)**. Links expired past 300 seconds must fail closed.
- **Resend Cooldown:** **60 seconds** per account.
- **Fail-Closed Guarantee:** SMTP transport failures during registration keep account in unverified status.

### 4.2 Role-Based TOTP Multi-Factor Authentication
- **Algorithm:** RFC 6238 TOTP, HMAC-SHA1, 6 digits, 30-second time-step, single-use code (`otpPolicyCodeReusable: false`).
- **Policy by Role:**
  - `CUSTOMER`: Voluntary MFA. Allowed to pair via account management, never forced upon login.
  - `ADMIN`: **Mandatory**. Unenrolled accounts are prompted with `CONFIGURE_TOTP` upon login.
  - `DISPATCHER`: **Mandatory**. Unenrolled accounts are prompted with `CONFIGURE_TOTP` upon login.
  - `MERCHANT` & `COURIER`: Voluntary baseline pending dedicated policy approval.

### 4.3 Administrative MFA Recovery Procedure
When executing recovery tests under Issue #159:
```bash
python3 platform/identity/configure-totp.py reset-user \
  --user-id <SYNTHETIC_USER_UUID> \
  --require-re-enroll
```
Verify that existing OTP credentials are deleted and that `CONFIGURE_TOTP` is re-added to `requiredActions`.

---

## 5. SMTP Test Transport Configuration

- **Local Capture Sink:** Mailpit (`axllent/mailpit:v1.27.4`).
- **Keycloak Host/Port:** `mailpit:1025` (internal cluster / docker network).
- **Mailpit Web UI (QA Inspection):** `http://localhost:8025` (or mapped cluster port).
- **Public Outbound Delivery:** **BLOCKED / NOT APPLICABLE**. In accordance with strict $0 hosting (`platform/security/azure-allowlist-policy.yaml`), no commercial or paid external SMTP service is configured. Mailpit serves as the authoritative sink for synthetic registration and verification testing.

---

## 6. Security Header & Cookie Expectations

When inspecting HTTP response headers across `https://swiftdrop.local:18443`:

| Header Name | Expected Value | Scope |
|---|---|---|
| `Content-Security-Policy` | `default-src 'self'; connect-src 'self' https://swiftdrop.local:18443 ...` | All HTML responses |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | All HTTPS responses |
| `X-Content-Type-Options` | `nosniff` | All responses |
| `X-Frame-Options` | `SAMEORIGIN` (Keycloak) / `DENY` (SPA) | HTML responses |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | All responses |

Cookie attributes for Keycloak session cookies (`AUTH_SESSION_ID`, `KEYCLOAK_IDENTITY`):
- `Secure: true`
- `HttpOnly: true`
- `SameSite: Lax`

---

## 7. Synthetic Test Accounts & Test Data Requirements

QA testing must use dedicated synthetic accounts. **Never use production, real-user, or personal credentials.**

| Role | Synthetic Username | Purpose | Expected Auth Behavior |
|---|---|---|---|
| `CUSTOMER` | `qa-customer-01` | Self-registration & email verification | Email link required; optional TOTP |
| `ADMIN` | `qa-admin-01` | Privileged administration | Mandatory TOTP enrollment on first login |
| `DISPATCHER` | `qa-dispatcher-01` | Dispatch management | Mandatory TOTP enrollment on first login |
| `MERCHANT` | `qa-merchant-01` | Partner portal | Standard login |
| `COURIER` | `qa-courier-01` | Courier driver app | Standard login |

---

## 8. Outstanding Blockers & External Gating

The following items are outside local environment feasibility and must be reported as **BLOCKED** or **NOT APPLICABLE** during Issue #159 acceptance:
1. **Live External Google OIDC Login:** BLOCKED. Headless automated test pipelines do not possess live Google Cloud client secrets or interactive Google account consent.
2. **External Internet SMTP Inbox Delivery:** BLOCKED. No zero-cost, unmetered external SMTP relay has been provisioned; local Mailpit in-memory capture is the approved test mechanism.
3. **Public Internet TLS Certificates:** LOCAL-ONLY. Certificates are issued by the local Root CA (`swiftdrop.local`); browser tests must import the local CA certificate into the browser trust store.
