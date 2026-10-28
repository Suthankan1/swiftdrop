# Identity Acceptance & Operational Handoff Contract — Issue #47 to Issues #150 and #159

**Producer Issue:** [LATER-15] Add Keycloak email verification and MFA (email link, TOTP, email OTP) ([#47](https://github.com/Suthankan1/swiftdrop/issues/47))
**Producer Feature Branch:** `chore/later-15-identity-mfa-acceptance`
**Target Integration Branch:** `test`
**Primary Implementer:** @Suthankan1 (Platform / Identity & Security Lead)
**Independent Security Reviewer:** @Vibhushan-2003 (Independent Security Reviewer)
**Staff / Merchant Policy Reviewer:** @Harithkeshan (Staff/Admin Business Policy)
**Consumer Issue A (Production Identity & SMTP):** [PROD-01] Production identity, SMTP, headers and secret readiness ([#150](https://github.com/Suthankan1/swiftdrop/issues/150))
**Consumer Issue B (Independent Browser Acceptance):** [QA-02] Independent browser, SMTP and recovery acceptance ([#159](https://github.com/Suthankan1/swiftdrop/issues/159))
**Date:** October 25, 2026
**Architecture Baseline:** SwiftDrop v1.3

---

## 1. Executive Summary & Purpose

This document provides the authoritative acceptance evidence and cross-team operational handoff contract for Keycloak email verification, multi-factor authentication (MFA), and email OTP architecture decisions delivered under **GitHub Issue #47**.

Issue #47 completes:
1. **Historical Implementation Verification:** Full verification of merged Phase A (native email verification, PR #48) and Phase B (native RFC 6238 TOTP MFA, PR #49) on the latest `test` branch commit SHA.
2. **Production Policy Formalization:** Comprehensive policy specification across registrations, Google brokering, token lifetimes, resend cooldown, role-based MFA requirements, and administrative recovery in [docs/security/EMAIL_VERIFICATION_MFA_POLICY.md](../security/EMAIL_VERIFICATION_MFA_POLICY.md).
3. **Architecture Decision Finalization:** Review and status recording of [ADR-0009](../architecture/adr/ADR-0009.md), maintaining the primary recommendation (OUTCOME A: retain native links and app TOTP, reject custom email OTP) under `PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL` without fabricated claims.
4. **Automated Security Regression Suite:** 14/14 static invariant checks, 93 unit and configuration tests running with 100% discovery parity across pytest (with 49 subtests) and unittest.

---

## 2. Division of Responsibility & Ownership Boundaries

To preserve architectural integrity and avoid scope creep, responsibilities are strictly partitioned across three distinct issues:

| Dimension / Responsibility | Issue #47 (Suthankan1) — COMPLETED HERE | Issue #150 (Platform Deployment) — NEXT | Issue #159 (Independent QA) — NEXT |
|---|---|---|---|
| **Scope Boundary** | Policy, architecture decision, unit/config tests, local Mailpit isolation | Production environment, external SMTP, DNS records, server secrets | Independent synthetic browser tests, external inbox delivery, recovery QA |
| **Email Verification Policy** | Defined approved baseline & proposed policies in `EMAIL_VERIFICATION_MFA_POLICY.md` | Ingests policy for Keycloak production runtime flags | Validates browser registration and email verification workflow |
| **MFA Configuration** | Verified RFC 6238 TOTP in realm, voluntary enrollment, `configure-totp.py` recovery | Enables mandatory MFA for `ADMIN` and `DISPATCHER` in production realm | Executes browser test of QR code pairing, TOTP prompt, and recovery |
| **SMTP Delivery** | Local isolated Mailpit (`mailpit:1025`); verified non-secret dev config | Configures external authenticated SMTP relay ($0 budget boundary) | Proves real external email receipt across external inboxes |
| **Email OTP Decision** | ADR-0009 review recorded; recommendation OUTCOME A; threat model documented | Complies with zero custom Keycloak SPI deployment | Verifies absence of unapproved custom OTP plugins |
| **Secrets & Credentials** | Zero secrets committed in Git; loopback admin helpers | Injects runtime secrets via environment variables (`KC_SPI_EMAIL_*`) | Audits that browser and public logs expose zero tokens or secrets |
| **Environment Boundary** | Local Docker Compose & mock REST tests | Staging / production container orchestration | External browser automation against deployed environment |

---

## 3. Summary of Verified Evidence for Issue #47

### 3.1 Phase A — Native Email Verification (PR #48)
- **Status:** **MERGED** into `test` (Commit `b72c005`).
- **Configuration:** `verifyEmail: true`, `registrationAllowed: true` in `realm-swiftdrop.json` and `realm-devarom.json`.
- **Action Token Lifespan:** `actionTokenGeneratedByUserLifespan: 300` seconds (5 minutes).
- **Cooldown:** `60` seconds per-user cooldown in runtime action configuration (`EmailCooldownManager`).
- **Local Mailpit:** Pinned `axllent/mailpit:v1.27.4` bound strictly to `127.0.0.1:8025` (web UI only; no published host SMTP port; zero external mail forwarding).
- **Theme:** Branded FreeMarker `login-verify-email.ftl` matching SwiftDrop design tokens.
- **Helper:** `platform/identity/configure-email-verification.py` applies idempotent, non-destructive configuration updates with `0600` recovery snapshots.

### 3.2 Phase B — Authenticator-App TOTP MFA (PR #49)
- **Status:** **MERGED** into `test` (Commit `7ecfc33`).
- **RFC 6238 Policy:** `otpPolicyType: "totp"`, `HmacSHA1`, `6` digits, `30`s period, lookahead `1`, `otpPolicyCodeReusable: false` (strictly single-use; replay protection).
- **Supported Apps:** Google Authenticator, Microsoft Authenticator, FreeOTP.
- **Enrollment Flow:** Native Keycloak `Condition - user configured` ensures unenrolled customers log in without prompt; voluntary enrollment initiated via `kc_action=CONFIGURE_TOTP`.
- **Theme Integration:** Branded `login-config-totp.ftl` (high-contrast QR code container, monospace secret key) and `login-otp.ftl` (prominent 20px centered digit input).
- **Recovery Helper:** `platform/identity/configure-totp.py reset-user <user>` safely deletes OTP credentials and queues `CONFIGURE_TOTP` for next login without touching passwords or roles.

### 3.3 Phase C — Email OTP Architecture Decision (PR #52 & ADR-0009)
- **Status:** **MERGED** into `test` (Commit `169d78e`).
- **Feasibility & Threat Model:** [docs/architecture/EMAIL_OTP_FEASIBILITY.md](../architecture/EMAIL_OTP_FEASIBILITY.md) and [docs/security/EMAIL_OTP_THREAT_MODEL.md](../security/EMAIL_OTP_THREAT_MODEL.md) (18 attack vectors modeled; proved Keycloak 26.8.1 core has zero native typed email OTP; identified NIST SP 800-63B factor independence violation).
- **Recommendation:** **OUTCOME A (Adopted Recommendation):** Do NOT implement typed email OTP. Retain Keycloak's native signed verification links and RFC 6238 TOTP.
- **Formal Status:** **`PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL`**. Maintained as proposed pending formal review signoff by @Vibhushan-2003 and @Harithkeshan.
- **Contingency Rule:** If typed email OTP is ever mandated, it requires a separate in-house SPI issue (`DevaromEmailOtpAuthenticatorFactory`) conforming to strict security mitigations; it is NOT implemented under Issue #47.

---

## 4. Downstream Prerequisites for Issue #150 (Production Deployment)

The platform deployment engineer (@Suthankan1 under Issue #150) must fulfill the following operational prerequisites:

1. **External SMTP Provider Provisioning:**
   - Select an approved zero-cost provider (e.g. SendGrid 100 emails/day free tier or university SMTP relay).
   - Verify strict compliance with the project's $0 budget boundary.
2. **DNS Record Verification:**
   - Configure DNS TXT records for SPF (`v=spf1 include:... ~all`).
   - Publish DKIM 2048-bit public key.
   - Publish DMARC policy (`v=DMARC1; p=quarantine; ...`).
3. **Server-Side Secret Injection:**
   - Inject Keycloak environment variables at container launch:
     - `KC_SPI_EMAIL_DEFAULT_HOST`
     - `KC_SPI_EMAIL_DEFAULT_PORT` (587 or 465)
     - `KC_SPI_EMAIL_DEFAULT_FROM`
     - `KC_SPI_EMAIL_DEFAULT_AUTH=true`
     - `KC_SPI_EMAIL_DEFAULT_USER`
     - `KC_SPI_EMAIL_DEFAULT_PASSWORD`
     - `KC_SPI_EMAIL_DEFAULT_STARTTLS=true` (or `SSL=true`)
   - Ensure secrets are never exposed in Git, client bundles (`VITE_*`), or public logs.
4. **Mandatory MFA Policy Activation:**
   - In production environment, activate mandatory TOTP required action for `ADMIN` and `DISPATCHER` roles after granting initial administrative enrollment sessions.
   - Maintain `CUSTOMER` role on optional/voluntary MFA.

---

## 5. Downstream Prerequisites for Issue #159 (Independent Browser Acceptance)

The independent security and QA engineer (@Vibhushan-2003 under Issue #159) must execute the following acceptance tests:

1. **Real External Mailbox Delivery Validation:**
   - Trigger public self-registration with a real external email address.
   - Verify actual receipt of the Keycloak verification email in the external inbox.
   - Click the action link and verify successful account confirmation.
2. **Synthetic Browser Flow Testing:**
   - Test registration -> email verification prompt -> link click -> password setup -> profile redirection.
   - Verify that an unverified account cannot access protected SPA routes.
3. **Authenticator-App TOTP E2E Flow:**
   - Voluntarily enroll via `kc_action=CONFIGURE_TOTP`.
   - Scan QR code using Google Authenticator / FreeOTP.
   - Verify that subsequent logins challenge for the 6-digit TOTP code.
   - Verify that invalid and expired codes are rejected.
4. **Lost Device Recovery Acceptance:**
   - Execute administrative reset for a test account.
   - Verify that the test user can log in with primary credentials and is prompted to register a new authenticator device.
5. **Security Header & Cookie Inspection:**
   - Verify presence of production headers (`HSTS`, `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`).
   - Verify Keycloak session cookies are marked `Secure`, `HttpOnly`, `SameSite=Lax`.

---

## 6. Acceptance Criteria Evaluation Register (Issue #47)

| Criterion | Evaluation | Supporting Evidence |
|---|---|---|
| **PR #48 Merged Implementation Verified** | **PASS** | Verified merged commit `b72c005` present in current `test` branch history. |
| **PR #49 Merged Implementation Verified** | **PASS** | Verified merged commit `7ecfc33` present in current `test` branch history. |
| **Current Test Commit SHA Recorded** | **PASS** | Recorded base `test` SHA: `0bc41e79df403ddde11eb14edb0570689f742510`. |
| **Email Verification Configuration Verified** | **PASS** | `verifyEmail: true`, `actionTokenGeneratedByUserLifespan: 300` in `realm-swiftdrop.json` and `realm-devarom.json`. |
| **Native TOTP Configuration Verified** | **PASS** | RFC 6238 TOTP (HmacSHA1, 6 digits, 30s period, lookahead 1, single-use) verified in realm files. |
| **Static Identity Validation Passes** | **PASS** | `./platform/identity/validate-identity.sh`: ALL 14 static checks PASSED. |
| **Identity pytest Suite Passes** | **PASS** | `python3 -m pytest platform/identity/tests -v`: 93 passed, 49 subtests passed (18.18s). |
| **Identity unittest Discovery Suite Passes** | **PASS** | `python3 -m unittest discover -s platform/identity/tests -p "test_*.py" -v`: 93 passed (18.06s). |
| **Local SMTP Status Accurately Recorded** | **PASS** | Local Mailpit development transport documented; distinguished from production SMTP. |
| **Verification-Link Behavior Evidenced** | **PASS** | Subject-bound token invariants, 300s TTL, 60s cooldown, replay safety verified. |
| **TOTP Enrollment & Challenge Evidenced** | **PASS** | Unenrolled pass-through, conditional 2FA, branded UI, loopback reset verified. |
| **Existing Login Compatibility Preserved** | **PASS** | Zero regression to customer login, PKCE S256, Gateway audience, or role mapping. |
| **Email Verification Production Policy Documented** | **PASS** | Complete policy documented in `docs/security/EMAIL_VERIFICATION_MFA_POLICY.md`. |
| **Role-Based MFA Policy Documented** | **PASS** | Matrix for CUSTOMER, MERCHANT, COURIER, DISPATCHER, ADMIN documented. |
| **SMTP Production Requirements Documented** | **PASS** | TLS/STARTTLS, $0 budget boundary, server secret injection, SPF/DKIM documented. |
| **ADR-0009 Review Pathway Formalized** | **PASS** | Recommendation OUTCOME A recorded; status maintained as PROPOSED pending human signoff. |
| **Custom Email OTP Scope Decided** | **PASS** | Custom OTP rejected under Issue #47; contingency SPI scope gated to separate issue. |
| **No Unapproved Email OTP Code Introduced** | **PASS** | Zero bespoke SPI code or third-party JARs introduced. |
| **Zero Committed Identity Secrets** | **PASS** | Automated scanner confirms zero secrets in realm, theme, or code files. |
| **Unrelated Issue #107 Changes Isolated** | **PASS** | Feature branch contains strictly identity MFA policy and test files. |
| **Issue #150 Production Handoff Recorded** | **PASS** | Prerequisites for external SMTP, TLS, and secrets documented in §4. |
| **Issue #159 Independent Acceptance Handoff Recorded** | **PASS** | Prerequisites for browser E2E, real mail delivery, and recovery documented in §5. |
| **Independent Security Review Requested** | **PASS** | Review formally requested from @Vibhushan-2003 and @Harithkeshan. |
| **Live Runtime Smoke Tests** | **UNVERIFIED (Offline)** | Live smoke tests not run due to local Docker daemon being offline (`LIVE SMOKE — NOT EXECUTED`). |
| **Production External Mail Delivery** | **UNVERIFIED (Gated to #150/#159)** | External SMTP delivery requires live production deployment. |

---

## 7. Review Sign-Off & Approvals

- **Primary Implementer / Platform Lead:** @Suthankan1 (Completed & Submitted for Review)
- **Independent Security Reviewer:** @Vibhushan-2003 (Pending independent acceptance review)
- **Staff / Merchant Policy Reviewer:** @Harithkeshan (Pending policy review)
