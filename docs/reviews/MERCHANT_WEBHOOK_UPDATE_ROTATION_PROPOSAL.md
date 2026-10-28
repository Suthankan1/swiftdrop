# Provider Contract Extension Proposal — Merchant Webhook Update & Secret Rotation (Issue #116 / N10-B17)

**Task:** Issue #116 (`[N10-B17] Implement tenant-bound webhook subscription configuration API`)  
**Primary Owner:** Thuvarahan (@thuvarahan-t), Communications Lead  
**Cross-Team Stakeholders & Reviewers:**  
- **Eric (@EricPraveen)** — Merchant UI Lead (Consumer compatibility & UI lifecycle)  
- **Suthankan1 (@Suthankan1)** — Platform Lead (Security review, secret management & Gateway routing)  
- **Vibhushan (@Vibhushan-2003)** — Courier & Security Lead (Event outbox & security boundary)  
**Target Release:** November 10, 2026 Baseline  
**Base Specification:** `contracts/rest/openapi-communications.yaml` (v1.0.0 frozen baseline)  
**Status:** **PROPOSED — AWAITING CROSS-TEAM SIGNOFF**

---

## 1. Context & Identified Contract Gap

Issue #116 requires that an authenticated, approved Merchant can:
1. Create a webhook subscription (`POST /api/v1/merchants/webhooks`) — **APPROVED in v1.0.0**
2. List webhook subscriptions (`GET /api/v1/merchants/webhooks`) — **APPROVED in v1.0.0**
3. Retrieve subscription details (`GET /api/v1/merchants/webhooks/{id}`) — **APPROVED in v1.0.0**
4. Deactivate a subscription (`DELETE /api/v1/merchants/webhooks/{id}`) — **APPROVED in v1.0.0**
5. Inspect delivery logs (`GET /api/v1/merchants/webhooks/{id}/deliveries`) — **APPROVED in v1.0.0**
6. **Update an existing subscription destination or event filters** — **GAP (Not in v1.0.0)**
7. **Dedicated HMAC signing secret rotation** — **GAP (Not in v1.0.0 / DEC-COM-02 unresolved)**

As documented in `docs/reviews/LATER_03_MERCHANT_WEBHOOK_CONTRACT_REVIEW.md` (Section 3.2):
> "Subscription Mutation (PUT/PATCH): NOT IN CONTRACT... To modify a destination or event filter, the merchant deactivates/deletes the existing subscription and creates a new one. The UI conforms to this by offering registration and deletion without fabricating a nonexistent PUT/PATCH endpoint."
> "Secret Rotation Endpoint: UNRESOLVED ARCHITECTURAL PROPOSAL (DEC-COM-02)... No dedicated /rotate-secret endpoint exists in openapi-communications.yaml v1.0.0."

Per repository governance (`AGENTS.md`), autonomous agents must not unilaterally mutate frozen public OpenAPI specifications without explicit cross-team review. The backend service layer in `WebhookSubscriptionService` has been engineered with tested internal methods (`updateSubscription` and `rotateSecret`), while the public HTTP surface in `WebhookSubscriptionController` strictly honors the frozen v1.0.0 routes.

This proposal formally submits the minimal backward-compatible REST contract extensions for review.

---

## 2. Proposed Contract Extensions

### 2.1 Extension A: Subscription Mutation (`PATCH`)

```http
PATCH /api/v1/merchants/webhooks/{subscriptionId}
Authorization: Bearer <verified-jwt>
Content-Type: application/json
Accept: application/json
```

#### Request Body (`UpdateWebhookSubscriptionRequest`):
```json
{
  "httpsUrl": "https://new-endpoint.example.com/api/v1/swiftdrop-callbacks",
  "allowedEventTypes": ["parcel.status.changed.v1"]
}
```
*All fields optional. Fields omitted or null remain unchanged.*

#### Invariants & Constraints:
1. **Immutable Fields:** `subscriptionId`, `merchantId`, `secretRef`, `createdAt`, and `active` cannot be modified by the client.
2. **Server-Side Validation:** If `httpsUrl` is supplied, it undergoes complete server-side TLS 1.2+ HTTPS and SSRF validation (`WebhookDestinationValidator`).
3. **Event Filter Validation:** If `allowedEventTypes` is supplied, it must contain only approved event types (`parcel.status.changed.v1`).
4. **Tenant Isolation:** Enforces `WHERE subscription_id = :id AND merchant_id = :authenticatedMerchantId`. Cross-tenant mutations return `403 Forbidden` (`TENANT_ACCESS_DENIED`).
5. **Auditing:** Advances `updated_at` timestamp in `webhook_subscriptions`.

#### Response:
- **`200 OK`**: Returns updated `WebhookSubscriptionResponse` (omitting secret).
- **`400 Bad Request`**: Malformed payload, non-HTTPS URL, or invalid event filter.
- **`401 Unauthorized`**: Missing or invalid Bearer JWT.
- **`403 Forbidden`**: Caller does not own the subscription or merchant is unapproved.
- **`404 Not Found`**: Subscription does not exist.

---

### 2.2 Extension B: Dedicated HMAC Secret Rotation (`POST`)

```http
POST /api/v1/merchants/webhooks/{subscriptionId}/rotate-secret
Authorization: Bearer <verified-jwt>
Accept: application/json
```

#### Invariants & Architectural Decision (`DEC-COM-02`):
1. **Single-Secret vs. Dual-Secret Window:**
   - *Option A (Single-Secret / Immediate Cutover):* Generates a new 256-bit cryptographically secure secret (`swd_whsec_...`), encrypts it via AES-256-GCM, atomically replaces `secret_ref`, and immediately uses the new key for future outbound HMAC signatures.
   - *Option B (Dual-Secret Grace Window):* Stores both current and previous secret references for a 24-hour overlap window, allowing merchants to update their verification systems without dropped events.
   - *Recommendation:* Adopt Option A for November 10 baseline simplicity. If zero-downtime rotation is needed by enterprise merchants, Option B can be added with an additional `previous_secret_ref` column and expiry timestamp.
2. **One-Time Presentation:** The newly generated plaintext secret is returned **once** in the rotation response and never again exposed.
3. **Cache Control:** Sets `Cache-Control: no-store` header on the response.
4. **Tenant Isolation:** Replaces key material strictly when tenant ownership and `APPROVED` status are verified.

#### Response:
- **`200 OK`**: Returns `WebhookSubscriptionCreatedResponse`:
```json
{
  "subscriptionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "merchantId": "8b3f1a20-4e2b-4e1a-8e2b-3a1b2c3d4e5f",
  "httpsUrl": "https://warehouse.example.com/api/v1/swiftdrop-callbacks",
  "allowedEventTypes": ["parcel.status.changed.v1"],
  "hmacSigningSecret": "<newly-generated-signing-secret>",
  "active": true,
  "createdAt": "2026-10-24T00:00:00Z"
}
```
- **`401 Unauthorized`**: Missing or invalid Bearer JWT.
- **`403 Forbidden`**: Cross-tenant rotation or unapproved merchant.
- **`404 Not Found`**: Subscription not found.

---

## 3. Compatibility & Impact Analysis

| System Component | Current State | Impact of Proposed Extension |
|---|---|---|
| **Merchant UI (`#21`)** | Supports deactivation and re-registration. | Backward-compatible. Eric can optionally add "Edit" and "Rotate Secret" buttons without breaking existing flows. |
| **API Gateway** | Routes `/api/v1/merchants/webhooks/**` to Communications. | Already routed correctly via `communications-service-webhooks` wildcard route. No gateway changes required. |
| **Database Schema** | `webhook_subscriptions` (V1). | Fully compatible with existing table structure. Option A requires zero schema migrations. |
| **Outbound Webhook Worker (`#142`)** | In development. | Directly retrieves active `secret_ref`, decrypts with AES-256-GCM master key, and signs payloads with HMAC-SHA256. |

---

## 4. Required Peer Sign-Off Matrix

| Reviewer | Area | Sign-Off Status |
|---|---|---|
| **@thuvarahan-t** | Communications Lead / Provider Implementation | **APPROVED (Author)** |
| **@EricPraveen** | Merchant UI Lead | Pending Review |
| **@Suthankan1** | Platform & Gateway Security Lead | Pending Review |
| **@Vibhushan-2003** | Courier / Security QA Lead | Pending Review |

Once signed off by `@EricPraveen` and `@Suthankan1`, `contracts/rest/openapi-communications.yaml` will be incremented to v1.1.0, and the two endpoints will be wired into `WebhookSubscriptionController`.
