# ADR-0010: Merchant Machine Credential Lifecycle & Tenant-Bound Order Security

**Status:** Approved by Merchant Lead (@thuvarahan-t) & Platform Lead (@Suthankan1)  
**Date:** 2026-10-24  
**Milestone:** November 10, 2026 — Full SwiftDrop Release  
**Related Issues:** Refs [#22](https://github.com/Suthankan1/swiftdrop/issues/22), [#88](https://github.com/Suthankan1/swiftdrop/issues/88), [#97](https://github.com/Suthankan1/swiftdrop/issues/97), [#100](https://github.com/Suthankan1/swiftdrop/issues/100), [#120](https://github.com/Suthankan1/swiftdrop/issues/120)  
**Decisions Formalized:** `DEC-MER-01`, `DEC-MER-02`, `DEC-MER-03`, `DEC-MER-04`, `DEC-MER-05`

---

## 1. Context

Merchant enterprise partners require automated, secure machine-to-machine (M2M) integration with SwiftDrop to create bulk and automated delivery orders without human browser interaction.
Under Architecture Baseline v1.3, two primary machine authentication approaches were evaluated:
1. **Option A: Scoped High-Entropy API Keys (`swd_live_...`)**
2. **Option B: OAuth 2.0 Client Credentials via Keycloak**

Furthermore, the integration contract between external merchant systems, Accounts & Partners, and Delivery Operations requires strict enforcement of merchant business approval status, tenant isolation, zero recoverable secrets, durable revocation, and safe rotation.

---

## 2. Decision

The Merchant Lead (@thuvarahan-t) and Platform Lead (@Suthankan1) formally approve **Option A: Scoped High-Entropy API Keys** with the following cryptographic and operational invariants:

1. **Token Generation & Entropy (`DEC-MER-01`):**
   - Format: `swd_live_<random_base62_32_chars>` (total 41 characters).
   - Prefix: `swd_live_` + 4 characters (first 13 characters, e.g. `swd_live_7a8b`) stored as public `key_prefix` for safe UI display and candidate indexing.
   - Secret Entropy: Generated using `java.security.SecureRandom` over a 62-character alphabet ($62^{32} \approx 2^{190.5}$ bits of cryptographic randomness).
   - Transport Header: `X-API-Key: swd_live_...` for external merchant REST requests.

2. **One-Way Cryptographic Storage & Confidentiality (`DEC-MER-02`):**
   - Plaintext secret is returned **strictly once** in the `201 Created` response body of `POST /api/v1/merchants/{id}/api-keys`.
   - The plaintext key is **never persisted, never logged, and never retrievable again**.
   - `accounts_db.merchant_api_keys` persists only the one-way **SHA-256 hex digest** (64 lowercase hex characters), `key_prefix`, `name`, `merchant_id`, `scopes`, `created_at`, `expires_at`, `is_revoked`, and `revoked_at`.
   - Constant-time equality (`MessageDigest.isEqual`) is enforced during verification to eliminate timing attacks.

3. **Authentication vs. Business Approval Decoupling (`DEC-MER-03`):**
   - A cryptographically valid API key proves the caller's tenant identity, but **cannot bypass merchant approval policy**.
   - Only merchants in `status = 'APPROVED'` are eligible to generate machine API keys. Issuance requests for `PENDING`, `SUSPENDED`, or `REJECTED` merchants fail closed with `403 Forbidden` (`MerchantNotApprovedException`).
   - When verifying API keys for delivery order creation (`POST /api/v1/merchant/parcels`), Delivery Operations validates with Accounts that the merchant tenant is `APPROVED`. Unapproved merchants fail closed with `HTTP 403 Forbidden` (`MERCHANT_NOT_APPROVED`).

4. **Credential Scopes & Rotation Limits (`DEC-MER-04`):**
   - Supported scopes are restricted strictly to: `parcels:create`, `parcels:read`, `webhooks:manage`.
   - Active key ceiling: Each merchant tenant may maintain **at most 3 active (unrevoked, unexpired) API keys**. Attempting to create a 4th key without revoking an existing key fails with `409 Conflict` (`ApiKeyLimitExceededException`), enabling zero-downtime key rotation while preventing key sprawl.

5. **Durable Revocation (`openapi-accounts.yaml` Extension):**
   - Immediate durable revocation is exposed via `DELETE /api/v1/merchants/{id}/api-keys/{keyId}` (with `POST .../revoke` alias).
   - Revocation updates `is_revoked = true` and records `revoked_at`.
   - Revoked keys are rejected immediately and permanently by subsequent verification.
   - Repeated revocation is safe and idempotent (`204 No Content`).

6. **Frontend Credential Hygiene (`DEC-MER-05` / Issue #120 Handoff):**
   - Machine API keys must never be stored in browser `localStorage`, `sessionStorage`, or embedded into Vite environment variables (`VITE_*`).
   - The Merchant Portal (Issue #120) provides a controlled one-time reveal modal upon generation, with immediate in-memory dismissal.
   - Metadata listing (`GET /api/v1/merchants/{id}/api-keys`) returns only safe metadata items (`keyId`, `name`, `keyPrefix`, `scopes`, `createdAt`, `expiresAt`, `isRevoked`), omitting plaintext secrets and cryptographic hashes.

---

## 3. Alternatives Considered & Trade-Offs

| Alternative | Pros | Cons / Reason for Rejection |
|---|---|---|
| **OAuth 2.0 Client Credentials (Keycloak)** | Standard OAuth token flow; reuses Keycloak token engine | Requires provisioning dedicated Keycloak confidential clients per merchant, which complicates self-service tenant onboarding, couples merchant management directly to Keycloak administrative credentials, and creates multi-hop token exchange overhead on every API call. |
| **API Keys without Hashing (Plaintext DB)** | Simple database queries | Catastrophic security vulnerability: database leaks expose full administrative access to all merchant automated dispatching. Rejected per corporate baseline and assignment guidelines. |
| **Argon2id Digest instead of SHA-256** | High computational cost against brute force | For high-entropy secrets (32 characters from 62-character alphabet with 190+ bits of entropy), dictionary attacks and rainbow tables are mathematically impossible. SHA-256 provides instant, non-blocking hardware-accelerated verification suitable for high-throughput order ingestion without CPU starvation. |

---

## 4. Consequences & Architectural Boundaries

- **Accounts & Partners:** Sole authority for merchant credential generation, hashing, database persistence (`accounts_db.merchant_api_keys`), metadata listing, revocation, and gRPC key validation (`AccountsInternalService.ValidateMerchantKeyDigest`).
- **Delivery Operations:** Sole authority for parcel booking (`POST /api/v1/merchant/parcels`), parcel persistence (`delivery_db.parcels`), outbox publication, and tracking. Calls Accounts via gRPC to validate the key digest and tenant approval.
- **Frontend SPA (Issue #120):** Displays the one-time copy modal upon generation. Never stores keys in browser storage.
- **Data Boundary:** `delivery_db` never stores merchant API keys or digests. `accounts_db` never stores parcel orders.
