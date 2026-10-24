# SwiftDrop Merchant Credential Frontend Integration Handoff

**Author:** Thuvarahan (@thuvarahan-t) — Merchant Backend Owner (Issue #22)  
**Recipient:** Eric Praveen (@EricPraveen) — Merchant Frontend & UI Owner (Issue #120)  
**Target Release:** November 10 Baseline (Architecture Baseline v1.3)  
**Backend Branch:** `feat/issue-22-merchant-credential-lifecycle`  
**Reference Issues:** [#22](https://github.com/Suthankan1/swiftdrop/issues/22), [#120](https://github.com/Suthankan1/swiftdrop/issues/120)  
**Architectural Decisions:** [ADR-0010](../architecture/adr/ADR-0010-merchant-credential-lifecycle.md), `DEC-MER-01` through `DEC-MER-05` in [Runtime Decision Register](../architecture/RUNTIME_DECISION_REGISTER.md)

---

## 1. Executive Summary & Responsibility Boundary

Issue #22 implements the authoritative, bounded Merchant Credential Lifecycle within **Accounts & Partners Service** (`accounts_db.merchant_api_keys`) and establishes the tenant-bound API contract.

This document serves as the formal consumer handoff for Eric Praveen to implement the **Merchant API Key Management UI** under **Issue #120** within `frontend/src/features/merchant/`.

### Strict Boundary Separation:
- **Accounts & Partners (Backend / #22):** Cryptographic key generation, SHA-256 one-way hashing, tenant ownership verification, approval state enforcement, active key rotation limits (max 3), metadata listing, and durable revocation.
- **Merchant Frontend (React SPA / #120):** Key creation modal, one-time reveal dialog with "Copy to Clipboard", active keys table, revocation confirmation modal, and approval-state banner integration.
- **Frontend Prohibitions:**
  - ❌ NEVER generate machine API keys on the client.
  - ❌ NEVER store API keys in `localStorage`, `sessionStorage`, cookies, or browser storage.
  - ❌ NEVER embed machine API keys in Vite `.env` variables or client-side bundles.
  - ❌ NEVER attempt to re-fetch plaintext keys (the backend does not store recoverable secrets).
  - ❌ NEVER expose stored digests in client views.

---

## 2. API Endpoints Specification

All endpoints are hosted by Accounts Service (port `8081`, proxied via Gateway port `8080` under `/api/v1/merchants/...`).  
Requests require a valid Keycloak Bearer JWT with realm role `MERCHANT` and subject bound to the target merchant tenant.

### 2.1 Generate API Key (One-Time Reveal)
- **Method & Route:** `POST /api/v1/merchants/{id}/api-keys`
- **Headers:**
  - `Authorization: Bearer <keycloak-access-token>`
  - `Content-Type: application/json`
  - `Accept: application/json`
- **Request Body (`CreateApiKeyRequest`):**
  ```json
  {
    "name": "Warehouse ERP Integration",
    "scopes": ["parcels:create", "parcels:read"],
    "expiresInDays": 90
  }
  ```
  - `name`: string (1-100 characters, mandatory, trimmed).
  - `scopes`: array of strings (optional; defaults to `["parcels:create", "parcels:read"]`). Permitted scopes: `parcels:create`, `parcels:read`, `webhooks:manage`.
  - `expiresInDays`: integer (optional; defaults to 90 days; min 1, max 365).
- **Response (`201 Created` - `ApiKeyCreatedResponse`):**
  ```json
  {
    "id": "c1a2b3c4-d5e6-7f8a-9b0c-1d2e3f4a5b6c",
    "name": "Warehouse ERP Integration",
    "key": "swd_live_7xK9mP2vL8wQ4zR1tY6uI0oA5sD3fG8h",
    "keyPrefix": "swd_live_7xK9",
    "scopes": ["parcels:create", "parcels:read"],
    "expiresAt": "2027-01-22T22:00:00Z",
    "createdAt": "2026-10-24T22:00:00Z"
  }
  ```
  - **Important:** `key` contains the plaintext secret and is **returned exactly once** in this response. It will never be returned again.
  - Response carries `Cache-Control: no-store`.

### 2.2 List API Key Metadata (Safe Read)
- **Method & Route:** `GET /api/v1/merchants/{id}/api-keys`
- **Headers:**
  - `Authorization: Bearer <keycloak-access-token>`
  - `Accept: application/json`
- **Response (`200 OK` - `ApiKeyListResponse`):**
  ```json
  {
    "keys": [
      {
        "id": "c1a2b3c4-d5e6-7f8a-9b0c-1d2e3f4a5b6c",
        "name": "Warehouse ERP Integration",
        "keyPrefix": "swd_live_7xK9",
        "scopes": ["parcels:create", "parcels:read"],
        "expiresAt": "2027-01-22T22:00:00Z",
        "createdAt": "2026-10-24T22:00:00Z",
        "revokedAt": null
      },
      {
        "id": "e5f6a7b8-c9d0-1e2f-3a4b-5c6d7e8f9a0b",
        "name": "Legacy Staging Script",
        "keyPrefix": "swd_live_3bA1",
        "scopes": ["parcels:read"],
        "expiresAt": "2026-12-01T10:00:00Z",
        "createdAt": "2026-09-01T10:00:00Z",
        "revokedAt": "2026-10-20T14:15:00Z"
      }
    ]
  }
  ```
  - Plaintext keys and SHA-256 hashes are **strictly omitted**.
  - Response carries `Cache-Control: no-store`.

### 2.3 Revoke API Key
- **Method & Route:** `DELETE /api/v1/merchants/{id}/api-keys/{keyId}`  
  *(Alternative alias: `POST /api/v1/merchants/{id}/api-keys/{keyId}/revoke`)*
- **Headers:**
  - `Authorization: Bearer <keycloak-access-token>`
- **Response (`204 No Content`)**:
  - The key is immediately and durably marked as revoked (`revoked_at = NOW()`).
  - Subsequent verification by Delivery Operations fails closed (`401 Unauthorized`).
  - Revocation is idempotent: revoking an already revoked key safely returns `204 No Content`.

---

## 3. UI Requirements & UX Guidelines for Issue #120

### 3.1 Merchant Business Status Gates
Reusing `<MerchantApprovalBanner>` and Accounts `MerchantStatus`:
- **`APPROVED`**: Full access. Key creation button and active keys table are enabled.
- **`PENDING`**: Render disabled "Generate API Key" button with tooltip: *"API key management requires verified merchant approval. Your application is currently under administrative review."*
- **`SUSPENDED`**: Render notification banner: *"Your merchant account is suspended. All active API keys are rejected at order intake. Contact support@swiftdrop.test."*
- **`REJECTED`**: Deny access to API key management view.

### 3.2 One-Time Key Reveal Modal
When a user submits the "Generate API Key" form:
1. Modal displays the newly generated key `swd_live_...` in a monospace read-only text box.
2. Provide a **"Copy to Clipboard"** button with visual feedback (*"Copied!"*).
3. Display a prominent **Security Warning Banner**:
   > ⚠️ **Save this API key immediately!**  
   > For your security, SwiftDrop does not store this secret and will never show it to you again. If you lose this key, you must revoke it and generate a replacement.
4. Closing the modal must destroy the key secret from component state memory. Do not persist in React state beyond the modal lifecycle.

### 3.3 Active Keys Table & Rotation Limit (Max 3)
- Render keys in a clean table showing: **Name**, **Prefix** (e.g. `swd_live_7xK9...`), **Scopes** (badges), **Created Date**, **Expiration Date**, **Status** (`Active`, `Expired`, `Revoked`), and **Actions** (`Revoke`).
- If the merchant already has **3 active keys**, disable the "Generate API Key" button and display helper text:
  > *"Maximum active API key limit reached (3/3). Revoke an existing key to issue a replacement."*
- Safe rotation workflow: Merchants with 1 or 2 keys can generate a replacement key, configure their external ERP system, verify traffic, and then revoke the deprecated key without downtime.

### 3.4 Revocation Confirmation Dialog
- When user clicks "Revoke", show a confirmation modal:
  > **Are you sure you want to revoke "{keyName}"?**  
  > Any external integration or warehouse script using key `{keyPrefix}...` will immediately fail to authenticate. This action is irreversible.
- On confirmation, issue `DELETE /api/v1/merchants/{id}/api-keys/{keyId}`, refresh the key list, and display a success toast.

---

## 4. Error Handling & RFC 7807 Problem Detail Specifications

| HTTP Status | Error Code / Problem Type | Cause | Frontend Recommendation |
|---|---|---|---|
| `400 Bad Request` | `INVALID_API_KEY_REQUEST` | Blank name, expiration <= 0 or > 365 days | Display inline validation error on form fields. |
| `400 Bad Request` | `INVALID_API_KEY_SCOPE` | Unrecognized scope string | Check scope checkboxes against permitted set (`parcels:create`, `parcels:read`, `webhooks:manage`). |
| `403 Forbidden` | `TENANT_ACCESS_DENIED` | Caller JWT subject does not match merchant tenant | Show *"Access Denied — You are not authorized to manage this merchant tenant."* |
| `403 Forbidden` | `MERCHANT_NOT_APPROVED` | Merchant status is `PENDING`, `SUSPENDED`, or `REJECTED` | Guide user to status banner; disable key creation. |
| `404 Not Found` | `API_KEY_NOT_FOUND` | Key ID does not exist or belongs to another merchant | Display *"Key not found"*; refresh active keys table. |
| `409 Conflict` | `API_KEY_LIMIT_EXCEEDED` | Merchant already has 3 active keys | Show limit banner and offer quick revocation action. |

---

## 5. Security & Confidentiality Directives

1. **No Client Storage:** Do not use `localStorage.setItem('merchant_api_key', ...)` under any circumstances.
2. **Clipboard Safety:** Use `navigator.clipboard.writeText()` securely within an explicit user click handler.
3. **Transient Memory:** Clear plaintext key from React state immediately upon closing the one-time reveal dialog (`setGeneratedKey(null)`).
4. **No Console Logging:** Ensure `console.log(response.data)` is removed so secrets do not leak to browser DevTools.
