# Email OTP Feasibility & Architecture Study — SwiftDrop

> **Branding revision — 2026-10-20:** The platform was previously referred to as Devarom during initial development and was renamed SwiftDrop on 2026-10-20 by the project owner. Dated evidence, prior review wording and technical identifiers below retain their original values. See the [Phase 1 audit](../branding/PHASE_1_AUDIT.md).

**Module:** Platform & Security · **Owner:** Suthankan1 · **Target Milestone:** November 1, 2026 · **Issue:** [Refs #47 (Phase C)](https://github.com/Suthankan1/devarom/issues/47) · **Architecture Baseline:** SwiftDrop v1.3

---

## 1. Executive Summary & Objective

This document provides a technical feasibility analysis, comparative evaluation, and architectural recommendation regarding the implementation of **typed Email One-Time Password (OTP) codes** in the SwiftDrop identity platform.

### 1.1 Context & Current Milestone
SwiftDrop's November 1 milestone mandates a secure, demonstrable initial login vertical slice connecting the React Single Page Application (SPA), Spring Cloud API Gateway, Accounts Service, and PostgreSQL via a central Keycloak identity provider.

Previous phases delivered and verified:
- **Phase A (PR #48):** Native Keycloak email ownership verification via cryptographic signed action links (`verifyEmail: true`), backed by an isolated local Mailpit SMTP service, 60-second resend cooldown, and branded Devarom Keycloak theme screens.
- **Phase B (PR #49):** Native RFC 6238 Authenticator-App Time-based One-Time Password (TOTP) Multi-Factor Authentication (MFA), featuring unenrolled login pass-through, branded setup with dark-mode QR card, replay-resistant challenge, lost-device admin recovery, and voluntary enrollment.
- **Security E2E Suite (PR #50, PR #51):** 40 automated edge and service tests proving JWT rejection (L06), role confinement (L07), and resource-owner isolation (L08).

### 1.2 The Proposed User Experience
The user experience under evaluation for Email OTP is:
1. User enters their identifier (email/username) or completes primary password authentication.
2. Keycloak generates a short numeric code (e.g., 6 digits) and dispatches it to the user's email address via SMTP.
3. User enters the code on a Keycloak-hosted form within the active browser session.
4. Keycloak securely validates the code against session state.
5. The verification or authentication flow continues to completion.

### 1.3 Key Distinctions
Email OTP is distinct from:
- **Keycloak's native email verification link:** Uses a 256-bit cryptographically signed action token embedded in a URL; requires clicking a link rather than typing a code.
- **Authenticator-app TOTP:** Uses RFC 6238 HMAC-SHA1 algorithms running locally on an isolated physical device without any network, SMS, or email dependency.
- **Google OAuth 2.0 / OIDC federation:** Delegates authentication to an external identity provider broker; email verification is asserted by Google's cryptographically signed ID token.
- **Password reset by email:** A recovery action that transmits an action link to replace forgotten credentials.

---

## 2. Analysis of Email OTP Use Cases

Email OTP is often casually suggested as a generic solution. However, its security profile, failure modes, and user experience differ radically depending on the functional context. We analyze three distinct use cases below.

```text
+-------------------+---------------------------------------------------------+-------------------------------------------------------+
| Use Case          | Core Security Question                                  | Verdict / Recommendation                              |
+-------------------+---------------------------------------------------------+-------------------------------------------------------+
| Use Case A:       | Should the user type a code instead of clicking the     | NOT RECOMMENDED. Degrades entropy from 256-bit signed |
| Signup Email      | existing native signed verification link?               | JWT to 20-bit numeric code; introduces brute force    |
| Verification      |                                                         | attack surface without adding security.               |
+-------------------+---------------------------------------------------------+-------------------------------------------------------+
| Use Case B:       | Should Keycloak email a code after successful password   | NOT RECOMMENDED for primary MFA. Violates NIST        |
| Login Multi-      | authentication?                                         | SP 800-63B independence; fate-sharing with password   |
| Factor (MFA)      |                                                         | reset; unencrypted transit; SMTP DoS single point of   |
|                   |                                                         | failure. RFC 6238 TOTP is vastly superior.            |
+-------------------+---------------------------------------------------------+-------------------------------------------------------+
| Use Case C:       | Would an emailed code be appropriate for account        | CONDITIONAL / DEFERRED. Only acceptable as an audited |
| Account Recovery  | recovery or step-up authentication?                     | fallback for low-assurance self-service recovery,     |
| & Step-Up Auth    |                                                         | never for high-privilege staff or merchant approvals. |
+-------------------+---------------------------------------------------------+-------------------------------------------------------+
```

### 2.1 Use Case A: Email Address Ownership Verification During Signup
- **Current Mechanism:** SwiftDrop uses Keycloak's native `VERIFY_EMAIL` required action. Upon registration, Keycloak sends an email containing a high-entropy, cryptographically signed action token (`VerifyEmailActionTokenHandler`). Clicking the link verifies the email address and redirects the user back to the application.
- **Security Comparison:**
  - *Entropy:* A signed action token link has $\ge 256$ bits of cryptographic entropy, signed with the realm's RS256/ES256 private key and bound to the subject, email, and action token ID. In contrast, a 6-digit numeric OTP has only $10^6$ combinations (approx. 20 bits of entropy), making it inherently vulnerable to online brute-force guessing unless aggressively rate-limited.
  - *Channel Binding:* Both mechanisms share the same transport channel (SMTP to the user's inbox). Typing a code does not make the proof of inbox ownership stronger than clicking a link.
  - *Cross-Device Friction:* The sole practical advantage of a typed code is avoiding cross-device or cross-app browser context switches (e.g., user registers in a desktop browser, reads email on mobile phone, and prefers to type the code back into the desktop browser). However, Keycloak's native action tokens already support cross-browser completion gracefully.
- **Architectural Impact:** Replacing native verification links with typed codes would require replacing Keycloak's core `VERIFY_EMAIL` RequiredActionProvider with an external SPI, abandoning the tested native core engine.
- **Recommendation:** **Reject typed codes for signup verification.** Retain the native signed link mechanism verified in PR #48.

### 2.2 Use Case B: Second-Factor Authentication During Login (MFA)
- **Current Mechanism:** SwiftDrop implements native RFC 6238 Authenticator-App TOTP (PR #49) using apps like Google Authenticator or Microsoft Authenticator.
- **Security Comparison & Critical Vulnerabilities:**
  - *Violation of Factor Independence (NIST SP 800-63B §5.1.3.3):* Under NIST guidelines, an out-of-band authenticator must operate over an independent channel. Because SwiftDrop's password reset workflow is also delivered via email, an attacker who compromises the victim's email account can **both reset the primary password AND intercept the second-factor email OTP**. This "fate-sharing" completely collapses the security boundary of multi-factor authentication back to single-factor email access.
  - *No Phishing Resistance:* Email OTP is easily phished. Adversary-in-the-Middle (AiTM) reverse proxies (such as Evilginx or Modlishka) prompt victims for the 6-digit code in real time and immediately relay it to Keycloak to obtain authenticated sessions.
  - *In-Transit Vulnerabilities:* SMTP is not an end-to-end encrypted protocol. Email hops across intermediate mail transfer agents (MTAs), mail relays, corporate archiving proxies, and POP3/IMAP clients where OTP codes can be intercepted or logged.
  - *SMTP Availability & Latency:* SMTP does not provide real-time delivery guarantees. Greylisting, spam filtering, and queue delays routinely introduce multi-minute latencies. Because an OTP must expire quickly (within 2–3 minutes) to resist brute forcing, delivery delays create extreme user friction, race conditions, and resend storms. If the SMTP provider experiences an outage, **all user logins fail completely**, creating an availability single point of failure (DoS).
  - *App-Based TOTP Superiority:* RFC 6238 TOTP requires no network, no SMS, no email provider, no external dependencies, and operates strictly on the user's isolated physical smartphone.
- **Recommendation:** **Reject Email OTP as a primary or secondary authentication factor.** SwiftDrop's existing native TOTP provides strictly superior security, zero operational dependencies, and zero code maintenance.

### 2.3 Use Case C: Account Recovery or Step-Up Authentication
- **Step-Up Authentication:** High-value actions (e.g., changing merchant bank payout details, updating pricing zones, or granting administrative roles) require *higher* assurance than routine logins. Relying on an emailed code for step-up authentication when email is already the recovery vector introduces a false sense of security.
- **Account Recovery:** When a user loses their TOTP device, SwiftDrop currently provides an administrative reset flow (`python3 platform/identity/configure-totp.py reset-user <username>`) that safely resets MFA credentials and prompts fresh enrollment upon next verified login. Self-service recovery using email links is natively supported in Keycloak without requiring custom typed-code logic.
- **Recommendation:** **Defer Email OTP for recovery/step-up.** Administrative recovery meets all November 1 operational requirements without introducing bespoke SPI code.

---

## 3. Investigation of Keycloak 26.8.1 Native Support

We conducted a technical investigation into Keycloak 26.8.1 (pinned Quarkus distribution) source code, official documentation, and upstream issues to determine if native typed Email OTP capabilities exist.

### 3.1 Investigation Findings

```text
+-------------------------------------------------------------+-------------------------------------------------------+
| Inquiry                                                     | Verified Technical Finding                            |
+-------------------------------------------------------------+-------------------------------------------------------+
| 1. Does Keycloak 26.8.1 have a native Email OTP             | NO. Keycloak core has NO built-in authenticator or    |
|    authenticator?                                           | required action for typed email OTP codes.            |
+-------------------------------------------------------------+-------------------------------------------------------+
| 2. What email capabilities are natively included?           | - Action-token verification links (VERIFY_EMAIL)     |
|                                                             | - Action-token password reset links (UPDATE_PASSWORD) |
|                                                             | - Action-token execute-actions links                  |
|                                                             | - SMTP testing & token-based XOAUTH2 (Keycloak 26.2+) |
+-------------------------------------------------------------+-------------------------------------------------------+
| 3. What does Keycloak's native OTP Policy apply to?         | Native OTPPolicy applies EXCLUSIVELY to RFC 6238 TOTP |
|                                                             | and RFC 4226 HOTP (hardware/software tokens). It does |
|                                                             | NOT govern email or SMS codes.                       |
+-------------------------------------------------------------+-------------------------------------------------------+
| 4. What is Keycloak's official architectural guidance       | Custom SPI extension (Authenticator SPI or            |
|    for email OTP?                                           | RequiredActionProvider SPI) or third-party JARs.      |
+-------------------------------------------------------------+-------------------------------------------------------+
```

### 3.2 Upstream Evidence & Documentation Sources
1. **Keycloak Server Administration Guide (26.8.1):**
   - Official Documentation: [Keycloak 26.8.1 Server Administration Guide](https://www.keycloak.org/docs/26.8.1/server_admin/)
   - Keycloak documents OTP exclusively under "Configuring OTP Policies" (referencing Google Authenticator, FreeOTP, and hardware tokens) and WebAuthn. Nowhere in the official 26.8.1 documentation is an email-based OTP authenticator described as a built-in feature.
2. **Upstream GitHub Feature Discussions & Requests:**
   - [Keycloak Discussion #36910: Email OTP as 2FA](https://github.com/keycloak/keycloak/discussions/36910)
   - [Keycloak Issue #10084: Support Email OTP in Core](https://github.com/keycloak/keycloak/issues/10084)
   - Core maintainers consistently confirm that email OTP is outside the out-of-the-box scope and recommend deploying custom SPI extensions for teams requiring this workflow.
3. **Keycloak Core Source Inspection (`org.keycloak.models.OTPPolicy`):**
   - The native `OTPPolicy` class validates credentials against `org.keycloak.models.credential.OTPCredentialModel`. It strictly evaluates HMAC algorithms (HmacSHA1, HmacSHA256, HmacSHA512) against pre-shared base32 secret keys generated during app pairing. It does not integrate with `EmailSenderProvider` or generate ephemeral email codes.

---

## 4. Evaluation of Alternative Solutions

We evaluate four architectural paths for SwiftDrop:

```text
+-----------------------+---------------------+---------------------+---------------------+---------------------+
| Evaluation Dimension  | Option A:           | Option B:           | Option C:           | Option D:           |
|                       | Native Link + TOTP  | Native KC Email OTP | Vetted 3rd-Party    | Custom In-House     |
|                       | (Current Baseline)  | (Hypothetical)      | Extension (SPI)     | Keycloak SPI        |
+-----------------------+---------------------+---------------------+---------------------+---------------------+
| Feasibility in 26.8.1 | High (Verified)     | IMPOSSIBLE          | Medium              | High                |
| Security Posture      | High (Independent)  | N/A (Does not exist)| Low-Medium (Varies) | Medium (Audited)    |
| Maintenance Burden    | Zero                | N/A                 | High (Upstream lag) | Very High           |
| Upgrade Risk          | Zero (Core stable)  | N/A                 | High (Quarkus break)| High (SPI drift)    |
| External SMTP Failure | Non-blocking login  | N/A                 | Blocks all logins   | Blocks all logins   |
| Custom Code in Repo   | Zero lines          | N/A                 | Binary JAR blob     | ~600 lines Java     |
| Theme Integration     | Native FreeMarker   | N/A                 | Custom templates    | Custom templates    |
| Operational Ownership | Core Keycloak team  | N/A                 | Untrusted 3rd-party | Suthankan1 / Team   |
+-----------------------+---------------------+---------------------+---------------------+---------------------+
```

### 4.1 Option A: Retain Native Email Verification Links & Authenticator TOTP (Recommended Baseline)
- **Description:** Continue using Keycloak's native signed verification links for signup email verification (PR #48) and native RFC 6238 TOTP for MFA (PR #49).
- **Pros:**
  - 100% native Keycloak 26.8.1; zero external JARs, zero custom Java code.
  - Zero upgrade friction during Keycloak minor/major security updates.
  - Full factor independence: email compromise does not compromise TOTP; TOTP loss does not compromise email.
  - Offline login capability: users can log in even during major SMTP outages.
  - Fully tested and verified in PR #48, #49, #50, and #51.
- **Cons:**
  - User cannot type a 6-digit email code; signup verification requires clicking an email link.
  - Users without smartphones cannot use app-based TOTP (currently mitigated by making MFA optional for standard `CUSTOMER` roles).

### 4.2 Option B: Native Keycloak Email OTP
- **Description:** Use an official, native Keycloak email OTP feature.
- **Evaluation:** **Infeasible.** Keycloak 26.8.1 does not provide this capability.

### 4.3 Option C: Vetted Third-Party Extension
- **Description:** Deploy a community-developed Keycloak extension, such as:
  - `mesutpiskin/keycloak-2fa-email-authenticator` (popular 2FA email authenticator)
  - `RedFroggy/keycloak-verify-email-by-code` (code-based registration verification)
  - `5-stones/keycloak-email-otp`
- **Pros:**
  - Fast initial proof-of-concept without authoring custom Java code.
- **Cons:**
  - *Supply Chain & Security Risk:* Introducing third-party JARs into Keycloak's privileged Quarkus classloader creates severe supply chain vulnerabilities. Community plugins often lack formal security audits, rate-limiting safeguards, or timely vulnerability patching.
  - *Upgrade Fragility:* Keycloak's internal SPIs, Quarkus build steps, and FreeMarker contexts frequently undergo breaking changes between minor versions (e.g., 25.x $\to$ 26.x). Community plugins frequently lag behind official Keycloak releases, blocking security updates for the entire platform.
  - *Inconsistent UI & Theme Breakage:* Third-party extensions ship their own legacy FreeMarker templates that conflict with SwiftDrop's custom branded `keycloak.v2` theme and WCAG accessibility standards.

### 4.4 Option D: Custom In-House Keycloak Authenticator SPI
- **Description:** Author, build, and maintain a proprietary Java extension implementing Keycloak's `Authenticator` and `AuthenticatorFactory` SPIs (`DevaromEmailOtpAuthenticatorFactory`).
- **Pros:**
  - Complete control over cryptographic generation, rate limiting, and session binding.
  - Seamless integration with SwiftDrop's branded FreeMarker theme (`platform/identity/themes/devarom/login/`).
  - Tailored to SwiftDrop's exact security invariants and audit requirements.
- **Cons:**
  - *High Engineering & Maintenance Cost:* Requires maintaining a separate Maven Java codebase, Docker multi-stage builds, and extensive unit/integration test harnesses across Keycloak version upgrades.
  - *Operational Complexity:* Introduces SMTP delivery failure modes into primary login paths.
  - *Documentation-Only Guard Violation:* Directly contradicts repository governance unless explicitly authorized as a separate implementation phase.

---

## 5. Email Delivery Architecture & Operational Realities

Any candidate email OTP implementation is strictly bounded by the underlying email transport.

### 5.1 Local Development vs. Production SMTP
- **Local Development (Mailpit):**
  - SwiftDrop runs Mailpit v1.27.4 on loopback (`mailpit:1025`, UI on `127.0.0.1:8025`).
  - Messages are captured instantly in memory with zero delivery latency, zero spam filtering, and zero network hops.
  - **Critical Warning:** Passing tests in Mailpit **does NOT prove real-world email deliverability**. Mailpit cannot simulate internet routing, greylisting, MTA queuing, or spam reputation.
- **Production SMTP:**
  - Production requires an enterprise transactional email provider (e.g., AWS SES, SendGrid, Mailgun) configured with strict DNS authentication: **SPF, DKIM, and DMARC alignment**.
  - Without mature domain reputation warming, transactional emails containing one-time codes are routinely delayed or quarantined by consumer mailbox providers (Gmail, Outlook, Yahoo) as suspicious spam.

### 5.2 The Delivery Latency vs. OTP Expiry Dilemma
- Security best practices mandate short OTP lifespans (maximum **180 seconds / 3 minutes**) to mitigate brute-force guessing.
- Real-world email delivery latencies across public MTAs routinely range from 15 to 120 seconds.
- When delivery exceeds 90 seconds, users instinctively click "Resend Code". This triggers a race condition: generating a new code invalidates the first code just as it arrives in the inbox, leading to severe user frustration and failed logins.

### 5.3 SMTP Outage Resilience
- If Keycloak relies on email OTP for login and the external SMTP provider experiences an outage or rate-limit throttle, **the entire SwiftDrop authentication system becomes unavailable**.
- In contrast, the current baseline (Option A) uses TOTP for MFA, allowing authenticated users to log in uninterrupted during email outages.

---

## 6. User Experience & Theme Integration Design

If an email OTP code challenge were ever mandated by product management, it would be hosted strictly within Keycloak's browser session, reusing the existing SwiftDrop theme.

### 6.1 Keycloak-Hosted UI Flow
```text
[ Browser / React SPA ]
        |
        v  (Redirect via OIDC PKCE)
[ Keycloak /auth ]
        |
        +---> Step 1: Username & Password Form (login.ftl)
        |
        +---> Step 2: Keycloak Dispatches 6-Digit OTP via SMTP
        |
        +---> Step 3: Branded Email OTP Challenge Screen (login-email-otp.ftl)
        |       - 6-digit numeric input (autofocus, inputmode="numeric")
        |       - Masked destination hint: "Sent to s***@example.com"
        |       - Active countdown timer (e.g., "Code expires in 02:45")
        |       - Dynamic "Resend Code" button (disabled during 60s cooldown)
        |       - "Use Authenticator App instead" / "Return to login" escape links
        |
        +---> Step 4: Verification & Authorization Code Redirect
        v
[ React SPA /auth/callback ]
```

### 6.2 Key UI & Accessibility Invariants
- **Design Tokens:** Strictly inherits `--color-ink` (`#173f36`), `--color-teal` (`#20594a`), `--color-accent` (`#d9ed95`), and card surfaces (`#fcfcf7`).
- **Responsive Layout:** Fluid container with zero horizontal overflow down to 360px mobile viewports.
- **Numeric Keyboard Optimization:** Field must use `<input type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code">` to trigger the mobile numeric keypad and support SMS/email OTP auto-fill where supported by user agents.
- **Accessibility (WCAG 2.1 AA):** ARIA live regions for countdown announcements; high-contrast focus rings; descriptive error summaries (`role="alert"`).

---

## 7. Architectural Recommendation & Decision Gate

### 7.1 Primary Recommendation: OUTCOME A (Maintain Current Baseline)
We recommend **OUTCOME A: Continue using native signed email verification links and native RFC 6238 Authenticator-App TOTP without implementing typed Email OTP.**

**Rationale:**
1. **Security Superiority:** Authenticator-app TOTP is cryptographically isolated from email compromise, immune to mailbox snooping, and satisfies NIST SP 800-63B factor independence.
2. **Zero Maintenance Burden:** Uses 100% native Keycloak 26.8.1 core functionality with zero external plugins, zero custom Java code, and zero upgrade fragility.
3. **Operational Resilience:** Logins remain fully operational during external email provider slowdowns or outages.
4. **Verified Baseline:** Both email link verification and TOTP MFA are fully implemented, verified, and evidenced in PR #48, PR #49, PR #50, and PR #51.

### 7.2 Contingency Architectural Specification: OUTCOME C
If product management or security leadership explicitly rejects the recommendation and mandates typed email OTP codes for a specific business requirement, that work **must NOT be implemented in this task**. Instead, it must follow **OUTCOME C**:
1. Open a dedicated GitHub issue for custom Authenticator SPI development.
2. Author an in-house Keycloak SPI (`DevaromEmailOtpAuthenticator`) implementing the strict threat-model mitigations specified in `docs/security/EMAIL_OTP_THREAT_MODEL.md`.
3. Require independent security architecture and peer review before deploying the SPI to any shared environment.

---

## 8. References & Upstream Sources

1. [Keycloak 26.8.1 Server Administration Guide](https://www.keycloak.org/docs/26.8.1/server_admin/)
2. [Keycloak GitHub: Discussion #36910 (Email OTP Discussion)](https://github.com/keycloak/keycloak/discussions/36910)
3. [NIST Special Publication 800-63B: Digital Identity Guidelines (Authentication and Lifecycle Management)](https://pages.nist.gov/800-63-3/sp800-63b.html)
4. [OWASP Multi-Factor Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html)
5. [RFC 6238: TOTP - Time-Based One-Time Password Algorithm](https://datatracker.ietf.org/doc/html/rfc6238)
6. [RFC 7807: Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc7807)
7. [SwiftDrop November 1 Login Contract](../../docs/contracts/LOGIN_CONTRACT.md)
8. [SwiftDrop Keycloak Identity Foundation README](../../platform/identity/README.md)
