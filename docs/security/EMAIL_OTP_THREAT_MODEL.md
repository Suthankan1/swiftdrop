# Email OTP Threat Model & Security Specification — Devarom

**Module:** Security & Architecture · **Owner:** Suthankan1 · **Target Milestone:** November 1, 2026 · **Issue:** [Refs #47 (Phase C)](https://github.com/Suthankan1/devarom/issues/47) · **Architecture Baseline:** Devarom v1.3

---

## 1. Scope & Objective

This threat model analyzes the attack surface, abuse vectors, and operational failure modes of implementing **typed Email One-Time Password (OTP) codes** in the Devarom identity architecture. It establishes mandatory security controls and technical invariants that **must** be enforced if any code-based email authentication or verification mechanism is ever authorized for implementation.

This specification adheres to [NIST SP 800-63B (Section 5.1.3.3)](https://pages.nist.gov/800-63-3/sp800-63b.html), the [OWASP Multi-Factor Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html), and Devarom's [Authentication Security Rules](AUTHENTICATION.md).

---

## 2. Threat Analysis: Abuse Cases & Attack Vectors

We identify and model 18 realistic threat scenarios specific to email-delivered OTP codes.

```text
+------+---------------------------------------+-------------------+---------------------------------------------------+
| Ref  | Threat Scenario                       | Severity          | Primary Impact / Vulnerability                    |
+------+---------------------------------------+-------------------+---------------------------------------------------+
| T-01 | Online Brute-Force Guessing           | CRITICAL          | Small numeric keyspace (10^6) exhaustible in mins |
| T-02 | OTP Token Replay                      | HIGH              | Re-submitting valid code within time window       |
| T-03 | In-Transit Eavesdropping / Theft      | HIGH              | Unencrypted SMTP hops, cleartext transit          |
| T-04 | Adversary-in-the-Middle (AiTM) Phishing| CRITICAL         | Real-time proxying of codes (Evilginx)            |
| T-05 | Email Account Compromise & Fate Sharing| CRITICAL         | Password reset + MFA intercept in same mailbox    |
| T-06 | Delivery Latency & Race Conditions    | MEDIUM-HIGH       | Delayed delivery invalidating codes; UX failure   |
| T-07 | Email Forwarding & Inbox Bleed        | MEDIUM            | Multi-recipient rules, corporate archive leaks    |
| T-08 | Account Enumeration & Timing Oracle   | HIGH              | Disclosing user existence via OTP dispatch time   |
| T-09 | Resend Flooding & Mailbox Bombing     | HIGH              | DoS via rapid requests; provider reputation loss  |
| T-10 | Distributed Rate-Limit Bypasses       | HIGH              | Rotating IP proxies to circumvent source throttle |
| T-11 | Concurrent Active Codes Collision     | MEDIUM            | Race condition allowing multiple valid codes      |
| T-12 | Secret Disclosure in Logs / Traces    | CRITICAL          | Plaintext OTP codes leaking into log pipelines    |
| T-13 | Secret Disclosure in URLs / Referrers | HIGH              | Codes leaking via GET parameters or HTTP headers  |
| T-14 | Cross-User Session Contamination      | CRITICAL          | Submitting User A's code into User B's session    |
| T-15 | Authentication Session Fixation       | HIGH              | Pre-seeding auth session before challenge         |
| T-16 | Recovery-Flow Factor Downgrade        | CRITICAL          | Bypassing primary factor via email recovery       |
| T-17 | SMTP Provider Outage (Auth DoS)       | CRITICAL          | External mail failure blocks all platform logins  |
| T-18 | Unauthorized MFA Policy Downgrade     | CRITICAL          | Self-downgrading from TOTP to weak Email OTP      |
+------+---------------------------------------+-------------------+---------------------------------------------------+
```

### Detailed Scenario Assessments

#### T-01: Online Brute-Force Guessing & Keyspace Exhaustion
- **Attack:** A standard 6-digit numeric OTP has only $1,000,000$ possible values ($\approx 20$ bits of entropy). Without aggressive server-side attempt limits, an attacker can script automated submissions against Keycloak's challenge endpoint to guess the code within the validity window.
- **Severity:** Critical.
- **Mitigation Requirement:** Maximum $3$ verification attempts per generated code. On the 3rd failed attempt, the code and the underlying authentication session **must be immediately invalidated**.

#### T-02: OTP Token Replay
- **Attack:** An attacker intercepts an OTP code (e.g., via shoulder surfing, network packet inspection, or mailbox snooping) and attempts to submit it after the legitimate user has already used it, or attempts to reuse it for multiple actions.
- **Severity:** High.
- **Mitigation Requirement:** Atomic single-use validation ("burn-on-read"). The server must delete or flag the code as consumed within the same database transaction that validates it.

#### T-03: In-Transit Eavesdropping & Interception
- **Attack:** Unlike TLS-protected HTTPS browser sessions, SMTP email delivery crosses intermediate Mail Transfer Agents (MTAs). If any hop does not enforce strict TLS (Opportunistic STARTTLS downgraded to cleartext), or if internal mail gateways log headers and bodies, the code is exposed in transit.
- **Severity:** High.
- **Mitigation Requirement:** Enforce TLS on all outbound SMTP connections; use minimal, generic subject lines and body copy that omit sensitive account or role identifiers.

#### T-04: Adversary-in-the-Middle (AiTM) Phishing & Real-Time Relaying
- **Attack:** Attackers deploy transparent reverse proxies (e.g., Evilginx) mimicking Devarom's Keycloak login domain. When the victim enters their password and the subsequent email OTP, the proxy forwards both credentials to the real Keycloak server in real time, capturing the emitted session cookie and OIDC tokens.
- **Severity:** Critical.
- **Architectural Note:** Email OTP is **NOT phishing-resistant**. Unlike FIDO2 / WebAuthn, email codes lack cryptographic origin binding. App-based TOTP is equally susceptible to AiTM, but WebAuthn is the only standard that eliminates this vector.

#### T-05: Email Account Compromise & Fate Sharing (NIST SP 800-63B §5.1.3.3)
- **Attack:** If an attacker gains unauthorized access to the victim's email account (via credential stuffing, session hijacking, or OAuth app consent abuse), they control the primary recovery channel. If Email OTP is also used as a second factor, the attacker can:
  1. Trigger a password reset via email to conquer Factor 1.
  2. Initiate login and receive the Email OTP code in the same inbox to conquer Factor 2.
- **Severity:** Critical.
- **Architectural Impact:** Using email as an MFA factor when email is also the credential recovery channel provides zero independent assurance. It reduces the entire multi-factor model to a single factor (email possession).

#### T-06: Delivery Latency, Jitter, & Race Conditions
- **Attack / Failure:** Email routing across internet MTAs is asynchronous and prone to queue delays, greylisting, and anti-spam scanning. If delivery exceeds 60–90 seconds, users re-click "Send Code". If the first code arrives after the second code was generated, the user enters an already-invalidated code, failing authentication.
- **Severity:** Medium-High.
- **Mitigation Requirement:** Enforce a strict 60-second resend cooldown; generating a new code must explicitly and atomically replace previous codes, and the UI must display an accurate countdown.

#### T-07: Email Forwarding, Multi-Inbox Bleed, & Auto-Archiving
- **Attack:** Many users configure email auto-forwarding rules (e.g., forwarding work emails to personal inboxes or archiving systems). Unencrypted forward hops expose OTP codes to unintended recipients and shared mailbox users.
- **Severity:** Medium.
- **Mitigation Requirement:** Advise users against shared mailboxes; restrict sensitive roles (ADMIN, DISPATCHER) from using disposable or forwarded domains.

#### T-08: Account Enumeration & Timing Oracles
- **Attack:** An attacker submits arbitrary email addresses to the OTP dispatch endpoint. If Keycloak responds faster when an email does not exist, or returns different error messages ("User not found" vs "Code sent"), the attacker compiles a list of registered Devarom accounts.
- **Severity:** High.
- **Mitigation Requirement:** Constant-time execution; uniform generic responses ("If an account exists with this email, a code has been sent") regardless of user existence.

#### T-09: Resend Flooding & Mailbox Bombing
- **Attack:** Attackers script repeated OTP dispatch requests targeting a victim's email address to flood their inbox (email bombing) or exhaust Devarom's transactional SMTP sending quota, leading to financial loss or provider suspension.
- **Severity:** High.
- **Mitigation Requirement:** Mandatory 60-second resend cooldown per user; global IP-based rate limiting (maximum 5 requests per minute per IP address).

#### T-10: Distributed Rate-Limit Bypasses
- **Attack:** Attackers distribute requests across thousands of residential proxy IPs to bypass IP-level rate limits while targeting a single account.
- **Severity:** High.
- **Mitigation Requirement:** Dual-layer rate limiting: rate limits must bind to **both** the client IP address AND the target user identity (`userId` / email hash).

#### T-11: Concurrent Active Codes & Collision Windows
- **Attack:** A race condition where multiple concurrent requests generate distinct active codes for the same user session, increasing the probability of a successful brute-force guess.
- **Severity:** Medium.
- **Mitigation Requirement:** Strict 1:1 active code invariant. Generating code $N+1$ must atomically delete code $N$ in a synchronized transaction.

#### T-12: Secret Disclosure in Logs, Traces, & Error Dumps
- **Attack:** Developers or system administrators log incoming/outgoing HTTP payloads, debug lines, or APM traces containing raw OTP codes or HMAC keys.
- **Severity:** Critical.
- **Mitigation Requirement:** Zero-disclosure logging policy. Raw OTP values, hashes, and cryptographic seeds must never appear in application logs, audit events, or APM spans.

#### T-13: Secret Disclosure in URLs, Query Parameters, & Referrers
- **Attack:** Transmitting OTP codes as HTTP GET query parameters (`/verify?code=123456`) causes the secret to be stored in browser history, proxy access logs, and leaked via the `Referer` header to external resources.
- **Severity:** High.
- **Mitigation Requirement:** OTP codes must **only** be submitted via HTTP POST requests within the body of a form or JSON payload.

#### T-14: Cross-User Code Bleed & Session Cross-Contamination
- **Attack:** User A generates a code; an attacker steals User A's code and enters it into User B's active login session to hijack User B's account or trick the system into authenticating User A as User B.
- **Severity:** Critical.
- **Mitigation Requirement:** Cryptographic session binding. The stored OTP hash must be strictly bound to the specific Keycloak `AuthenticationSessionModel`, `userId`, `client_id`, and realm.

#### T-15: Authentication Session Fixation
- **Attack:** An attacker pre-initializes an authentication session, obtains a valid session cookie, and induces a victim to complete authentication within that fixed session.
- **Severity:** High.
- **Mitigation Requirement:** Keycloak's native session rotation upon successful authentication must be preserved.

#### T-16: Recovery-Flow Abuse & MFA Downgrade
- **Attack:** If an attacker knows a user's password but cannot pass their app-based TOTP challenge, they abuse a "Send code to email" escape hatch to downgrade high-assurance TOTP to low-assurance Email OTP.
- **Severity:** Critical.
- **Mitigation Requirement:** Users enrolled in TOTP must **not** be allowed to unilaterally downgrade to Email OTP without administrative intervention.

#### T-17: SMTP Provider Outage (Authentication Denial of Service)
- **Attack / Failure:** A distributed denial-of-service (DDoS) attack against the external SMTP provider, or a provider configuration outage, prevents OTP emails from reaching users. If email OTP is required for login, all users are completely locked out of Devarom.
- **Severity:** Critical.
- **Mitigation Requirement:** Primary MFA must rely on offline, network-independent mechanisms (RFC 6238 TOTP).

#### T-18: Unauthorized MFA Policy Downgrade via Self-Registration
- **Attack:** A malicious user creates a new account and selects a weak or nonexistent MFA policy while attempting to access privileged resources.
- **Severity:** Critical.
- **Mitigation Requirement:** Public registration strictly confines users to the `CUSTOMER` role. Privileged staff roles (`COURIER`, `DISPATCHER`, `ADMIN`) must be provisioned by administrators with mandatory TOTP.

---

## 3. Mandatory Implementation Invariants for Typed Codes

If an Email OTP implementation is ever authorized by the team, the following technical requirements are **non-negotiable** and must be enforced by the custom SPI:

### 3.1 Cryptographic Generation & Entropy
- **CSPRNG:** Codes must be generated using `java.security.SecureRandom`.
- **Length & Format:** Exactly 6 decimal digits (`[0-9]{6}`), non-sequential, non-repeating (reject trivial sequences like `123456` or `111111`).
- **Keyspace:** $10^6$ combinations ($\approx 20$ bits of entropy).

### 3.2 Expiration & Lifecycle
- **Lifespan:** Maximum **180 seconds** (3 minutes) from generation.
- **Single-Use ("Burn on Read"):** Validated atomically. Upon successful verification, the code record must be permanently destroyed.
- **Replacement on Resend:** Generating a new code immediately revokes and invalidates all prior codes for that user/session.

### 3.3 Server-Side Storage & Session Binding
- **No Plaintext Storage:** Active codes must never be stored in plaintext. Keycloak must store a salted cryptographic hash:
  $$\text{Hash} = \text{HMAC-SHA-256}(\text{SecretKey}, \text{OTP} \parallel \text{AuthSessionId} \parallel \text{UserId})$$
- **Transient Auth Notes:** Store the hash exclusively within Keycloak's transient `AuthenticationSessionModel` user session notes (`authSession.setAuthNote(...)`).
- **Zero Database Persistence:** Never persist OTP codes or hashes to long-term database tables (`keycloak_db` or Accounts PostgreSQL).

### 3.4 Rate Limiting & Brute-Force Throttling
- **Maximum Attempts:** Maximum **3 failed attempts**. On the 3rd incorrect guess:
  1. The active OTP is permanently invalidated.
  2. The authentication session is aborted.
  3. Keycloak logs a security event `LOGIN_ERROR` with detail `code_exhausted`.
- **Resend Cooldown:** Minimum **60 seconds** between successive dispatch requests.
- **Dual-Layer Throttle:**
  - Per-User Limit: Max 3 dispatch requests per 15-minute window.
  - Per-IP Limit: Max 5 dispatch requests per minute across all users.

### 3.5 Safe Auditing & Zero Secrets Logging
- Keycloak audit events (`LOGIN`, `LOGIN_ERROR`, `SEND_VERIFY_EMAIL`) must record the user ID, client ID, remote IP, and event type.
- **Strict Prohibition:** Raw OTP codes, cryptographic HMAC keys, and full email body contents must **NEVER** appear in log files, audit event details, or exception stack traces.

---

## 4. Preservation of Devarom Security Boundaries

Any implementation must strictly preserve Devarom's established architecture:

```text
                                [ React SPA ]
                         (Bearer JWT in JS memory only)
                                     |
                                     v
                       [ Spring Cloud API Gateway ]
                     (Edge JWT Verification, Port 8080)
                                     |
                                     v
                          [ Accounts Service ]
                 (OAuth2 Resource Server, JWKS, Port 8081)
```

1. **Browser Boundaries:**
   - React SPA must **never** execute OTP verification logic.
   - React SPA must **never** receive OTP secrets, seeds, or hashes.
   - Access tokens remain exclusively in JavaScript runtime memory; no tokens or OTP state in `localStorage` or `sessionStorage`.
2. **Backend Boundaries:**
   - Accounts PostgreSQL (`accounts_db`) must **never** store OTP secrets or verification state.
   - Spring Cloud Gateway must **never** expose unauthenticated OTP verification endpoints.
   - Core services validate identity exclusively via Keycloak-signed asymmetric JWTs (RS256).
3. **Identity Foundation:**
   - All credential challenges remain Keycloak-hosted within the `devarom` realm.
   - Preserves Authorization Code Flow with PKCE `S256`.
   - Preserves strict redirect allowlists and CORS origins (`http://localhost:5173`).

---

## 5. Summary Threat Mitigation Matrix

| Threat Ref | Threat Description | Mandatory Mitigation | Verified Mechanism |
|---|---|---|---|
| **T-01** | Brute-force guessing | Max 3 attempts, immediate invalidation | SPI AuthSession counter |
| **T-02** | OTP replay | Single-use burn-on-read | Atomic session note clearance |
| **T-03** | In-transit eavesdropping | Strict SMTP TLS | STARTTLS / TLS 1.3 |
| **T-04** | AiTM Phishing | WebAuthn recommendation (cannot solve with OTP) | Documented limitation |
| **T-05** | Fate sharing with reset | Separate primary MFA to TOTP | Reject Email OTP for MFA |
| **T-06** | Delivery latency race | 60s cooldown, atomic invalidation on resend | EmailCooldownManager |
| **T-08** | Account enumeration | Generic responses, constant-time validation | Uniform Keycloak UI feedback |
| **T-09** | Resend flooding | 60s cooldown, IP rate limits | Gateway & SPI throttle |
| **T-12** | Log disclosure | Zero secrets logging | Keycloak audit masking |
| **T-14** | Cross-user bleed | Cryptographic session binding | Salted HMAC bound to `AuthSessionId` |
| **T-17** | SMTP outage DoS | Primary MFA remains offline TOTP | RFC 6238 Keycloak baseline |
