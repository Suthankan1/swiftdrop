# Identity Role Reconciliation Contract & Integration Baseline

**Status:** Technical Integration Contract Baseline · **Target Milestone:** November 10, 2026 · **Issue:** [Refs #107](https://github.com/Suthankan1/swiftdrop/issues/107) · **Architecture Baseline:** SwiftDrop v1.3
**Module Owner:** Platform / IAM (@Suthankan1)
**Functional Reviewer:** @Harithkeshan (Staff/Admin Business Policy)
**Independent Security Reviewer:** @Vibhushan-2003
**Consuming Feature Owners:**
- Issue #97 (Pending Merchant Enrollment): @thuvarahan-t
- Issue #121 (Admin Merchant Approval/Suspension): @thuvarahan-t
- Issue #120 (Merchant Pending Enrollment & Credential Settings UI): @EricPraveen

---

## 1. Architectural Scope & Invariants

The **Keycloak Staff & Merchant-Role Reconciliation Adapter** coordinates role state between the authentication authority (Keycloak) and authoritative business domain models (Accounts Service).

### Non-Negotiable Boundaries:
1. **Keycloak Authority:** Keycloak owns realm identities, authentication factors, credentials, and realm role memberships (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`). Keycloak does not hold domain business profile data (e.g., merchant registration numbers, store descriptions, staff zones).
2. **Accounts Authority:** Accounts Service (`accounts_db`) owns business profiles, active/suspended lifecycle statuses, assigned zones, and approval states.
3. **Server-Only Administration:** Keycloak administration credentials (`client_id`, `client_secret`, or service-account tokens) are strictly server-side environment configurations (`KEYCLOAK_ADMIN_*`). They must **never** be exposed to browsers or embedded in `VITE_*` configuration bundles.
4. **Independent Authorization Enforcement:** Removing a Keycloak role does not instantly invalidate already-issued access JWTs (which remain valid until expiry, typically 5–15 minutes). Therefore, **all business services must independently verify active domain status** (e.g. `status == ACTIVE` or `APPROVED`) on every business operation, never relying solely on the presence of a JWT role claim.
5. **No Direct Keycloak DB Access:** Identity synchronization occurs strictly over Keycloak's supported REST Administration API using Java 21 `HttpClient`.
6. **Strict Role Allowlist:** Only approved roles (`COURIER`, `DISPATCHER`, `ADMIN`, `MERCHANT`) are reconcilable. Public registration assigns only `CUSTOMER`. Requests containing arbitrary or client-supplied role strings are strictly rejected.

---

## 2. Controlled Staff-Role Reconciliation

### 2.1 Authorized Administrative Provisioning
Only an authenticated actor with the `ADMIN` role may initiate staff provisioning or operational role changes.
- **Identity Existence Check:** Keycloak identity existence is verified via `GET /admin/realms/{realm}/users/{id}` before creating any Accounts record. Missing Keycloak subjects fail closed with `404 Not Found`.
- **Role Assignment:** Keycloak role mapping is reconciled via `POST /admin/realms/{realm}/users/{id}/role-mappings/realm`.
- **Idempotency:** Re-provisioning an existing staff member with matching attributes is an idempotent `NO_OP` that verifies Keycloak role presence without duplicating database records. Conflicting attributes produce `409 Conflict`.

### 2.2 Business Status Synchronization
Accounts defines staff lifecycle statuses: `ACTIVE`, `SUSPENDED`, `INACTIVE`.
- **Active State:** Staff profile is `ACTIVE` and assigned one operational role (`COURIER`, `DISPATCHER`, or `ADMIN`).
- **Suspension / Deactivation:** When an administrator suspends or deactivates a staff member:
  1. Accounts updates the profile status to `SUSPENDED` or `INACTIVE` in `accounts_db`.
  2. The adapter revokes the operational role in Keycloak via `DELETE /admin/realms/{realm}/users/{id}/role-mappings/realm`.
  3. All operational endpoints reject subsequent requests immediately based on business status, even if the caller's JWT still contains the operational role claim.

---

## 3. Controlled Merchant-Role Reconciliation

### 3.1 Merchant Lifecycle States
Accounts defines merchant lifecycle statuses: `PENDING`, `APPROVED`, `SUSPENDED`, `REJECTED`.

### 3.2 Mandatory Trigger Policy Decision
The reconciliation adapter supports two configurable trigger policies via `swiftdrop.reconciliation.merchant.trigger-policy`:

| Policy | Enrollment Behavior (Issue #97) | Approval Behavior (Issue #121) | Default / Recommendation |
|---|---|---|---|
| **`ON_APPROVAL`** | Creates `PENDING` record in Accounts. **Does not grant** `MERCHANT` role in Keycloak. Role is a safe no-op. | Admin approval sets status to `APPROVED` and **grants** `MERCHANT` role in Keycloak. | **Active Default (Recommended)** |
| **`ON_ENROLLMENT`** | Creates `PENDING` record and grants `MERCHANT` role in Keycloak. Business APIs strictly deny operations until `APPROVED`. | Status changed to `APPROVED`. Role is already present (`NO_OP`). | Configurable alternative if early portal access is required. |

*Human Policy Confirmation:* The active default is `ON_APPROVAL`. This prevents unapproved customers from possessing the `MERCHANT` role in their identity claims until an administrator verifies business credentials.

### 3.3 Suspension & Rejection Safety
When a merchant is `SUSPENDED` or `REJECTED`:
1. Accounts sets the merchant status to `SUSPENDED` or `REJECTED`.
2. The adapter revokes the `MERCHANT` role in Keycloak.
3. Accounts business endpoints enforce `MerchantAuthorizationService`, denying operational access to any merchant not in `APPROVED` status.

---

## 4. Idempotency, Failure Recovery & Audit

### 4.1 Durable Audit Schema (`identity_reconciliation_audit`)
Every role mutation and reconciliation attempt is recorded in `accounts_db` via Flyway migration `V5`:
- `id`: UUID primary key.
- `target_sub`: Verified Keycloak subject UUID.
- `target_role`: Target role (`COURIER`, `DISPATCHER`, `ADMIN`, `MERCHANT`).
- `action`: `PROVISION_STAFF`, `STATUS_SYNC`, `APPROVE_MERCHANT`, `SUSPEND_MERCHANT`, `RETRY_RECONCILIATION`.
- `status_before` / `status_after`: State transitions.
- `outcome`: `SUCCESS`, `NO_OP`, `FAILED`, `PENDING_RETRY`, `DENIED`.
- `initiated_by`: Authenticated administrator subject UUID or `SYSTEM`.
- `attempt_count`: Bounded retry counter.
- `error_message`: Sanitized failure description (**strictly scrubbed of credentials, tokens, or secrets**).

### 4.2 Stale-Retry Protection (Anti-Privilege Escalation)
In the event of a partial failure (e.g. Accounts updates status but Keycloak admin times out):
- The operation is recorded as `PENDING_RETRY`.
- **CRITICAL INVARIANT:** Recovery retries (`ReconciliationRecoveryService`) **never** blindly execute the original mutation. Instead, recovery re-reads the authoritative state from Accounts.
- If an entity was `SUSPENDED` or `REJECTED` in Accounts after an initial failed approval, the retry strictly enforces **revocation** and aborts any role grant.

---

## 5. Administrative API Specification

All administrative reconciliation endpoints reside under `/api/v1/admin/reconciliation/**` and require `hasRole('ADMIN')`.

| Endpoint | Method | Description | Success Response | Error Codes |
|---|---|---|---|---|
| `/api/v1/admin/reconciliation/staff/provision` | `POST` | Provisions staff profile and reconciles Keycloak role | `200 OK` (`StaffProfileResponse`) | 400, 401, 403, 404, 409, 503 |
| `/api/v1/admin/reconciliation/staff/{sub}/sync` | `POST` | Synchronizes staff role to match Accounts status | `200 OK` (`ReconciliationResult`) | 401, 403, 404, 503 |
| `/api/v1/admin/reconciliation/staff/{sub}/suspend` | `POST` | Suspends staff profile and revokes Keycloak role | `200 OK` (`ReconciliationResult`) | 401, 403, 404, 503 |
| `/api/v1/admin/reconciliation/merchant/{sub}/sync` | `POST` | Synchronizes merchant role based on active policy | `200 OK` (`ReconciliationResult`) | 401, 403, 404, 503 |
| `/api/v1/admin/reconciliation/merchant/{id}/approve` | `POST` | Approves merchant and reconciles `MERCHANT` role | `200 OK` (`ReconciliationResult`) | 401, 403, 404, 503 |
| `/api/v1/admin/reconciliation/merchant/{id}/suspend` | `POST` | Suspends merchant and revokes `MERCHANT` role | `200 OK` (`ReconciliationResult`) | 401, 403, 404, 503 |
| `/api/v1/admin/reconciliation/retry/{auditId}` | `POST` | Re-evaluates authoritative state and retries | `200 OK` (`ReconciliationResult`) | 400, 401, 403, 404, 503 |
| `/api/v1/admin/reconciliation/audit/{sub}` | `GET` | Retrieves full reconciliation audit history for subject | `200 OK` (`List<IdentityReconciliationAudit>`) | 401, 403 |

---

## 6. Cross-Team Integration Handoffs

### 6.1 Handoff to Issue #97: Pending Merchant Enrollment (@thuvarahan-t)
- **Enrollment Entrypoint:** When an authenticated customer submits merchant enrollment, extract the verified Keycloak subject strictly from the JWT principal (`TrustedIdentity.subject()`). Never trust a client-supplied subject ID or role.
- **Reconciliation Hook:** Invoke `MerchantRoleReconciliationService.handleEnrollment(keycloakSub)`.
- **Contract Guarantee:** Under default `ON_APPROVAL`, this hook creates the `PENDING` business profile and returns `NO_OP`. The user retains their `CUSTOMER` role until administrative approval.
- **Duplicate Protection:** Re-submitting enrollment for an existing pending merchant is idempotent and does not create competing tenants.

### 6.2 Handoff to Issue #121: Admin Merchant Approval / Suspension (@thuvarahan-t)
- **Approval Hook:** When an admin approves a merchant, call `MerchantRoleReconciliationService.approveMerchant(adminIdentity, merchantId)`.
- **Suspension Hook:** When an admin suspends a merchant, call `MerchantRoleReconciliationService.suspendMerchant(adminIdentity, merchantId, reason)`.
- **Failure Resilience:** If Keycloak is unreachable during approval or suspension, Accounts records `PENDING_RETRY` in the audit table and raises `KeycloakAdminUnavailableException` (503). An administrator can retry via `/api/v1/admin/reconciliation/retry/{auditId}` once Keycloak connectivity is restored.

### 6.3 Handoff to Issue #120: Merchant Frontend UI (@EricPraveen)
- **Pending Journey:** After enrollment, the SPA queries `/api/v1/merchants/me`. If status is `PENDING`, display the pending verification screen. The access token still contains `CUSTOMER`.
- **Post-Approval Token Refresh:** When Accounts signals `APPROVED` status:
  1. The SPA must perform a silent token refresh via Keycloak OIDC refresh token (`keycloak.updateToken()`).
  2. The newly issued access token contains the `MERCHANT` role in `realm_access.roles`.
  3. If silent refresh fails (e.g. refresh token expired), redirect the user to Keycloak login with PKCE to re-authenticate.
- **Suspension Visibility:** If an already-authenticated merchant is suspended:
  - Even if the browser's current access token still has `MERCHANT`, Accounts APIs will return `403 Forbidden` (`Merchant profile is SUSPENDED`).
  - The UI must handle this 403 response by displaying an account suspension banner and blocking operational views.
- **Zero Frontend Secrets:** No Keycloak administration secrets or client secrets are accessible in browser bundles.

---

## 7. Review & Verification Sign-Off Checklist

- [x] Server-only Keycloak administration adapter implemented via Java 21 `HttpClient`.
- [x] Strict allowlist: `COURIER`, `DISPATCHER`, `ADMIN`, `MERCHANT`.
- [x] Staff provisioning requires authenticated `ADMIN` and verifies target identity in Keycloak.
- [x] Staff suspension revokes privileged role in Keycloak and enforces active status in Accounts.
- [x] Merchant role reconciliation implemented with configurable `ON_APPROVAL` / `ON_ENROLLMENT` trigger policy.
- [x] Stale retry protection: recovery re-reads authoritative source state and enforces revocation for suspended entities.
- [x] Durable audit logging with V5 Flyway migration and credential scrubbing.
- [x] Spring Security guards `/api/v1/admin/**` with `hasRole('ADMIN')`.
- [x] Unit and Spring Security integration test suite (52 new tests, 193 total passing in Accounts Service).
- [ ] Functional Review: @Harithkeshan sign-off.
- [ ] Independent Security Review: @Vibhushan-2003 sign-off.
