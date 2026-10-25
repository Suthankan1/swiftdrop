# SwiftDrop Identity Policy: Email Verification, Multi-Factor Authentication & SMTP Readiness

**Module:** Platform & Identity Security · **Owner:** @Suthankan1 · **Milestone:** November 10, 2026 (Full SwiftDrop Release)
**Issue:** [Refs #47 (LATER-15)](https://github.com/Suthankan1/swiftdrop/issues/47) · **Architecture Baseline:** SwiftDrop v1.3
**Reviewers:** @Vibhushan-2003 (Independent Security Reviewer), @Harithkeshan (Staff/Admin Business Policy)
**Status:** **AUTHOR POLICY RECORDED & FORMALIZED — PENDING INDEPENDENT SECURITY SIGNOFF**

---

## 1. Executive Summary & Purpose

This document provides the authoritative production policy specification for Keycloak identity verification, multi-factor authentication (MFA), and SMTP operations for the SwiftDrop enterprise parcel delivery platform.

This specification completes the remaining policy and security acceptance requirements for **GitHub Issue #47 ([LATER-15])**.

### 1.1 Architectural Scope Separation

Under Architecture Baseline v1.3 and ADR-0009, SwiftDrop establishes a strict architectural separation between three distinct mechanisms:

1. **Native Email Verification Links (Phase A — Merged PR #48):**
   Keycloak's native action-token workflow (`VerifyEmailActionTokenHandler`) verifies email mailbox ownership via cryptographically signed 256-bit JWT action links. It provides tamper-proof, high-entropy ($\ge 256$ bits) proof of mailbox delivery.
2. **Authenticator-App TOTP Multi-Factor Authentication (Phase B — Merged PR #49):**
   Keycloak's native RFC 6238 Time-based One-Time Password (TOTP) mechanism provides strong, offline, phishing-resistant second-factor authentication via standard authenticator applications (Google Authenticator, Microsoft Authenticator, FreeOTP) with strict single-use replay prevention.
3. **Typed Email OTP Codes (Phase C Feasibility & ADR-0009):**
   Sending short numeric codes via email for manual entry. Phase C feasibility research and threat modeling ([EMAIL_OTP_THREAT_MODEL.md](EMAIL_OTP_THREAT_MODEL.md)) revealed severe security vulnerabilities (fate sharing with password reset violating NIST SP 800-63B §5.1.3.3, intermediate unencrypted SMTP hops, lack of phishing resistance, delivery latency race conditions, and total authentication Denial of Service during external SMTP outages). **ADR-0009 recommends OUTCOME A (Do NOT implement custom typed email OTP)**. Custom email OTP is NOT implemented under Issue #47.

---

## 2. Production Email Verification Policy

The table below specifies the effective email verification policy for SwiftDrop, explicitly distinguishing **APPROVED Baseline Settings** (already implemented and verified in `realm-swiftdrop.json` / PR #48) from **PROPOSED Production Enhancements** (pending deployment-side activation).

### 2.1 Specification Matrix

| Setting / Scenario | Approved Baseline (PR #48 / v1.3) | Proposed Production Setting | Security & Architectural Rationale |
|---|---|---|---|
| **`verifyEmail` Gate** | `true` (Enabled in realm) | `true` (Retained) | **APPROVED.** Mandatory email verification ensures user cannot book or receive parcels under an unverified mailbox. |
| **Public Registration** | `registrationAllowed: true` | `registrationAllowed: true` | **APPROVED.** Public users register as `CUSTOMER` only (`default-roles-swiftdrop`). |
| **Token Action Handler** | Native `VERIFY_EMAIL` | Native `VERIFY_EMAIL` | **APPROVED.** Direct Quarkus core execution; zero custom Java SPI or third-party plugins. |
| **Action Token Lifetime** | `300` seconds (5 minutes) | `300` seconds (5 minutes) | **APPROVED.** Bounded TTL minimizes the exposure window of action tokens in inboxes/logs. |
| **Resend Cooldown** | `60` seconds (`emailResendCooldownSeconds: "60"`) | `60` seconds | **APPROVED.** Prevents mailbox flooding and rapid retry abuse. |
| **Link Replay Protection** | Subject-bound token; replay yields "Already verified" | Subject-bound token; replay yields "Already verified" | **APPROVED.** Token cannot authenticate a different user, create unauthorized sessions, or elevate roles. |
| **Local SMTP Transport** | `mailpit:1025` (isolated Compose) | External authenticated SMTP (TLS 1.2+) | Local Mailpit is development-only. Production SMTP handoff to **Issue #150**. |
| **Sender Address** | `no-reply@swiftdrop.test` | Verified domain (e.g. `no-reply@swiftdrop.com`) | Production requires SPF, DKIM, and DMARC alignment. |

### 2.2 Workflow & Edge Case Specifications

#### A. New Local Registrations (`CUSTOMER`)
1. User completes the branded registration form at `/auth/realms/swiftdrop/login-actions/registration`.
2. Public registration exclusively assigns the `CUSTOMER` composite role. Staff roles (`MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`) are strictly excluded.
3. Keycloak automatically generates a 256-bit cryptographically signed `VERIFY_EMAIL` action token and transmits an email containing the verification link.
4. Keycloak presents the branded `login-verify-email.ftl` screen instructing the user to check their email.
5. The account remains in `emailVerified: false`. The user cannot proceed to authenticated SPA routes until ownership is confirmed.

#### B. Existing Registered Users
1. Existing accounts with `emailVerified: true` continue normal authentication without interruption or repeated verification prompts.
2. Existing accounts with `emailVerified: false` encounter the native `VERIFY_EMAIL` required action screen upon their next interactive login.
3. **Non-Retroactive Invalidation Invariant:** Enabling `verifyEmail` does **not** instantly revoke existing active access JWTs or active SSO sessions. Active tokens expire naturally per their 300s lifespan.

#### C. Google-Brokered Identities
1. Google OAuth 2.0 identity brokering is configured in `realm-swiftdrop.json` with identity provider alias `google`.
2. Keycloak receives Google's OIDC ID token claim `email_verified`.
3. Under approved broker policy (`trustEmail: false` by default, or verified claim mapping), Google-verified email addresses are recognized by Keycloak. If Google returns an unverified email, Keycloak automatically queues the `VERIFY_EMAIL` required action before granting session tokens.
4. Google authentication assigns strictly `CUSTOMER` role membership; privilege escalation to staff roles is blocked.

#### D. Changes to Registered Email Address
1. When an existing user updates their registered email address via the User Profile API or Account Console, Keycloak sets `emailVerified = false` on the modified attribute.
2. Keycloak immediately dispatches a `VERIFY_EMAIL` action token to the **new** address.
3. Access to authenticated domain services using the new address remains blocked until the user clicks the link dispatched to the new mailbox.

#### E. Invalid or Tampered Verification Links
1. Action tokens include HMAC signatures validated against Keycloak realm active realm keys.
2. Any tampering with token parameters (subject ID, email address, expiration timestamp, signature) results in immediate rejection with HTTP 400 Bad Request.
3. Keycloak renders the branded `error.ftl` page ("Invalid or expired action link"). No internal stack traces or cryptographic validation details are leaked.

#### F. Expired Verification Links
1. Action token validity is strictly enforced at `300` seconds (5 minutes).
2. Clicking a link after $t > 300\text{s}$ fails validation. Keycloak renders an informative error page with a direct button allowing the user to request a newly generated verification email.
3. Old expired tokens cannot be resurrected or extended.

#### G. SMTP Delivery Failure & Operational Recovery
1. If the SMTP transport times out or fails (e.g. network disconnect, provider outage):
   - Keycloak logs an internal warning without logging credentials.
   - The user is displayed an alert banner: *"The email could not be sent. Please contact an administrator."*
   - The user account remains unverified (`emailVerified: false`). Security fails closed: SMTP failure **never** bypasses verification or auto-verifies an account.
2. The user may trigger a resend after the 60-second cooldown expires once SMTP transport is restored.

#### H. Privacy & Account-Enumeration Defenses
1. Resend verification requests for non-existent email addresses return identical HTTP response status codes and visual message banners as valid addresses (*"If your email is registered, a verification link has been sent"*).
2. Timing discrepancies during user lookup are mitigated via standard Keycloak password/token hashing delays.
3. Action links are delivered exclusively via the email body and are never exposed in browser redirect URLs, referrer headers, or Gateway access logs.

---

## 3. Production Multi-Factor Authentication (MFA) Policy

### 3.1 Technical Baseline (RFC 6238 TOTP)

SwiftDrop configures Keycloak's native OTP engine with industry-standard RFC 6238 parameters, verified in `realm-swiftdrop.json`:

- **Policy Type:** `totp` (Time-based One-Time Password). Counter-based `hotp` is strictly forbidden.
- **Algorithm:** `HmacSHA1` (Guarantees universal compatibility across Google Authenticator, Microsoft Authenticator, and FreeOTP).
- **Number of Digits:** `6` digits.
- **Time Step Interval:** `30` seconds.
- **Look-Ahead Window:** `1` (permits $\pm 30$ seconds clock drift tolerance).
- **Replay Prevention:** `otpPolicyCodeReusable: false` (strictly enforced single-use; a submitted code cannot be reused within the active time window).

### 3.2 Role-Based MFA Requirement Matrix

| Role | Environment | Baseline Requirement (v1.3) | Target Production Policy | Rationale & Non-Lockout Invariant |
|---|---|---|---|---|
| **`CUSTOMER`** | Dev / Test / Prod | **Optional / Voluntary** | **Optional / Voluntary** | Mandatory customer MFA causes high onboarding friction. Customers can voluntarily enroll via Account Console or `kc_action=CONFIGURE_TOTP`. |
| **`MERCHANT`** | Dev / Test | **Optional** | **Recommended; Step-Up for Payouts** | Merchant portal login can remain voluntary; high-risk operations (API key generation, bank account change) require TOTP step-up. |
| **`COURIER`** | Dev / Test | **Optional** | **Optional / Voluntary** | Field personnel using mobile delivery web app; voluntary enrollment avoids operational lockouts during route execution. |
| **`DISPATCHER`** | Dev / Test | **Optional (Baseline)** | **Mandatory (Enforced in Prod)** | Dispatchers manage fleet assignments and courier routing; privileged operational scope warrants mandatory 2FA. |
| **`ADMIN`** | Dev / Test | **Optional (Baseline)** | **Mandatory (Enforced in Prod)** | Platform administrators manage merchant approvals, pricing zones, and security configuration; mandatory 2FA is essential. |

> [!IMPORTANT]
> **Non-Lockout Guard for Development & Staging:**
> Mandatory MFA is **NOT** globally enabled on the realm level during current development or test phases. Enabling mandatory MFA globally without an enrollment grace period would lock out automated test runners and team developers. Enforcing mandatory MFA for `ADMIN` and `DISPATCHER` is a **production deployment task owned by Issue #150**.

### 3.3 Safe Enrollment & Browser Flow

1. **Conditional 2FA Execution:**
   Keycloak's browser authentication flow (`Browser - Conditional 2FA`) executes the subflow `Condition - user configured` (`conditional-user-configured`):
   - **Unenrolled User:** Condition evaluates to `false`. User logs in with primary credentials without prompt.
   - **Enrolled User:** Condition evaluates to `true`. Keycloak immediately displays the branded `login-otp.ftl` challenge form.
2. **Voluntary Enrollment Workflow:**
   - React SPA can trigger voluntary MFA setup by redirecting to Keycloak authorization endpoint with query parameter `kc_action=CONFIGURE_TOTP`.
   - Keycloak displays the branded `login-config-totp.ftl` screen featuring a high-contrast dark-mode QR code container, monospace manual entry secret, device name field, and 6-digit confirmation code.
   - Users may cancel setup via `#cancelTOTPBtn` without terminating their primary authenticated session.
3. **Validating Enrollment:**
   The user must provide a valid 6-digit code matching the current TOTP time step. Keycloak atomically persists the credential in `keycloak_db` and activates conditional MFA for subsequent logins.

### 3.4 Lost Device Handling & Administrative Recovery

If a user loses access to their authenticator device (e.g. lost, replaced, or damaged mobile phone):

1. **Identity Verification Prerequisite:**
   Before executing an MFA reset, an authorized platform administrator must verify the requester's identity through out-of-band channels (photo ID, verified phone callback, or merchant corporate registry).
2. **Administrative Recovery Execution:**
   Administrators use the loopback management helper (`configure-totp.py`) or Keycloak Admin Console:
   ```bash
   python3 platform/identity/configure-totp.py reset-user <username>
   ```
   **What this does non-destructively:**
   - Connects to Keycloak Admin REST API over local loopback using ephemeral admin credentials.
   - Deletes existing OTP credentials (`credentials/` where `type == "otp"`).
   - Appends `CONFIGURE_TOTP` to the user's `requiredActions`.
   - Leaves passwords, email verification status, and realm role assignments 100% untouched.
3. **Next Login Re-Enrollment:**
   Upon next interactive login, the user authenticates with their primary credentials and is immediately prompted with `login-config-totp.ftl` to pair their new device.
4. **Audit Logging Invariant:**
   Every administrative MFA reset must be logged in the administrative audit log (operator identity, target subject UUID, timestamp, reason code). Credential seeds and TOTP secrets are never logged.

---

## 4. Production SMTP Readiness & Operational Requirements

### 4.1 Development vs Production Transport Separation

| Dimension | Local Development (PR #48 Baseline) | Production Requirement (Issue #150) |
|---|---|---|
| **SMTP Host** | `mailpit:1025` (internal Compose network) | Approved external SMTP relay provider |
| **Encryption** | None (plaintext within Docker network) | **Mandatory TLS 1.2+ or STARTTLS** |
| **Authentication** | Disabled (`auth: false`) | **Mandatory authenticated user + password/token** |
| **Credential Storage** | None (zero credentials needed) | Server-side environment variables `${SMTP_PASSWORD}` |
| **Deliverability Scope** | Synthetic `.test` local capture only | Real external mailbox delivery worldwide |
| **Port Exposure** | `127.0.0.1:8025` (local web UI only) | Outbound port `587` (STARTTLS) or `465` (TLS) |

### 4.2 Approved Production SMTP Provider Criteria ($0 Budget Boundary)

In accordance with project constraints, production SMTP must adhere to the **strict $0 cost boundary**:
1. **Free-Tier SMTP Options:**
   - **SendGrid Free Tier:** 100 emails/day forever (sufficient for academic evaluation & demonstration).
   - **Mailgun Foundation Trial / University SMTP:** Approved institution/university relay.
   - **Brevo (formerly Sendinblue):** 300 emails/day free tier.
2. **Zero Paid Metering:** Under no circumstances may an auto-scaling metered cloud email service (e.g. Amazon SES without budget cap) be provisioned without human team authorization.

### 4.3 Security & Reputation Invariants

1. **Domain Alignment:** Outbound emails must use a verified sender identity matching the production domain (e.g. `no-reply@swiftdrop.lk` or `no-reply@swiftdrop.com`).
2. **DNS Authentication:** Domain must have valid DNS TXT records configured:
   - **SPF:** `v=spf1 include:sendgrid.net ~all`
   - **DKIM:** 2048-bit public key published in DNS.
   - **DMARC:** `v=DMARC1; p=quarantine; rua=mailto:dmarc@swiftdrop.com`.
3. **Credential Isolation:**
   - SMTP host, port, username, and password must be injected into the Keycloak container as runtime environment variables:
     - `KC_SPI_EMAIL_DEFAULT_HOST`
     - `KC_SPI_EMAIL_DEFAULT_PORT`
     - `KC_SPI_EMAIL_DEFAULT_FROM`
     - `KC_SPI_EMAIL_DEFAULT_AUTH`
     - `KC_SPI_EMAIL_DEFAULT_USER`
     - `KC_SPI_EMAIL_DEFAULT_PASSWORD`
   - Secrets must never be stored in Git, `.env.example`, client bundles (`VITE_*`), or exported realm JSON files.
4. **Action Link Masking:** Verification URLs must never be output to application logs, metrics endpoints, or error stack traces.

---

## 5. ADR-0009 Review Status & Custom Email OTP Decision

### 5.1 Architecture Decision Summary

**Reference Document:** [ADR-0009: Email OTP Verification Strategy for SwiftDrop](../architecture/adr/ADR-0009.md)
**Feasibility Study:** [docs/architecture/EMAIL_OTP_FEASIBILITY.md](../architecture/EMAIL_OTP_FEASIBILITY.md)
**Threat Model:** [docs/security/EMAIL_OTP_THREAT_MODEL.md](EMAIL_OTP_THREAT_MODEL.md)

| Category | Finding / Specification |
|---|---|
| **Keycloak 26.8.1 Native Capabilities** | Keycloak core provides **no native typed email OTP authenticator**. Native email support is restricted to action-token links (`VERIFY_EMAIL`). Native `OTPPolicy` applies exclusively to app/hardware tokens (RFC 6238 TOTP / RFC 4226 HOTP). |
| **Security Threat Evaluation** | 18 abuse vectors analyzed. Typed email OTP introduces fate sharing with email password reset (violates NIST SP 800-63B §5.1.3.3), intermediate unencrypted MTA hops, zero phishing resistance, and converts SMTP outages into total login outages. |
| **Primary Recommendation** | **OUTCOME A (Adopted Recommendation):** Do NOT implement typed email OTP. Retain Keycloak native signed verification links (PR #48) and RFC 6238 TOTP (PR #49). |
| **Current Approval Status** | **`PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL`** |
| **Reviewers** | @Suthankan1 (Author & Platform Lead), @Vibhushan-2003 (Independent Security Reviewer), @Harithkeshan (Staff/Merchant Policy Reviewer). |

> [!CAUTION]
> **Preserving Review Integrity:**
> ADR-0009 is maintained in `PROPOSED` status. In accordance with working discipline, recommendations are not promoted to `APPROVED` status without recorded, verifiable human peer signoff.

### 5.2 Contingency Specification: OUTCOME C (If Formally Mandated)

If product management or course stakeholders formally mandate typed email OTP in writing:
1. That implementation must **NOT** be built under Issue #47.
2. A separate follow-up implementation issue must be opened.
3. The implementation must be an in-house Keycloak Java SPI (`DevaromEmailOtpAuthenticatorFactory`), strictly adhering to the invariants in `EMAIL_OTP_THREAT_MODEL.md`:
   - Cryptographically secure CSPRNG (6 decimal digits).
   - Maximum 180s expiration window; atomic burn-on-read.
   - Max 3 invalid submission attempts before invalidation.
   - 60s resend cooldown.
   - Salted HMAC session binding to prevent session swapping.
   - Zero credential or plaintext code logging.

---

## 6. Downstream Handoff Boundaries & Responsibilities

| Responsibility | Issue #47 (Current Task) — COMPLETED | Issue #150 (Platform Deployment) — NEXT | Issue #159 (Independent Acceptance) — NEXT |
|---|---|---|---|
| **Policy Formulation** | Complete email verification, MFA, and SMTP policy documented herein | Ingests policy for production environment configuration | Verifies test plans against documented policy |
| **ADR-0009 Review** | Formulates Outcome A recommendation and review pathway | Acknowledges zero custom SPI deployment requirement | Validates absence of unapproved custom OTP plugins |
| **Regression Testing** | Validates 14 static checks, 93 unit tests (pytest + unittest parity) | Validates live container bootstrap in CI | Runs end-to-end synthetic browser acceptance suite |
| **Local SMTP** | Validates Mailpit container isolation and synthetic capture | Replaces Mailpit with verified external production SMTP relay | Validates real inbox delivery across external mail services |
| **MFA Enforcement** | Validates voluntary TOTP, conditional 2FA flow, recovery helper | Configures mandatory MFA role execution for `ADMIN`/`DISPATCHER` | Validates interactive QR setup and recovery flow in browser |
| **DNS & TLS Secrets** | Enforces zero committed secrets in Git | Configures TLS certificates, DNS records (SPF/DKIM), server secrets | Verifies HTTPS and security headers (`HSTS`, `CSP`) |

---

## 7. Acceptance Criteria Verification

- [x] **Criterion 1 (Historical Baseline Verification):** PR #48 (email verification) and PR #49 (TOTP MFA) merged evidence verified against current test commit SHA (`0bc41e7`).
- [x] **Criterion 2 (Production Email Verification Policy):** Documented approved vs proposed policies across registrations, existing accounts, Google brokering, expiry, resend cooldown, and privacy.
- [x] **Criterion 3 (Production MFA Policy):** Documented RFC 6238 parameters, role-based voluntary vs mandatory requirements, lost device recovery workflow, and non-lockout invariants.
- [x] **Criterion 4 (SMTP Production Readiness):** Documented external SMTP requirements, $0 budget boundary, TLS/STARTTLS, secret isolation, and DNS reputation prerequisites.
- [x] **Criterion 5 (ADR-0009 Review Recording):** Recorded Outcome A recommendation, threat model summary, and formal review status as `PROPOSED — AWAITING SECURITY/PRODUCT APPROVAL`.
- [x] **Criterion 6 (Security Regression Parity):** Verified 14/14 static invariant checks, 93/93 pytest suite (41 subtests), 93/93 unittest discovery suite.
- [x] **Criterion 7 (Downstream Handoffs):** Explicit handoff boundaries established for Issue #150 and Issue #159.
