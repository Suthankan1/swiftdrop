# Provider Contract Review — Merchant Webhook Subscription API (Issue #21 / LATER-03)

**Task:** Issue #21 (`[LATER-03] Build merchant webhook subscription UI and reviewed provider contract`)  
**Lead Owner / Contributor:** Eric (@EricPraveen), Merchant Lead  
**Cross-Team Reviewers:**  
- **Thuvarahan (@thuvarahan-t)** — Communications Lead & Merchant Lead Collaborator (Issue #116 / N10-B17 provider owner)  
- **Suthankan1 (@Suthankan1)** — Platform Lead (Kafka contracts, webhook retry worker & gateway routing)  
- **Vibhushan (@Vibhushan-2003)** — Courier Lead & Security QA Lead (Delivery status events & outbox boundary)  
**Target Integration Branch:** `test`  
**Feature Branch:** `feat/later-03-merchant-webhook-subscriptions`  
**Contract Baseline Reference:** `contracts/rest/openapi-communications.yaml` (v1.0.0, PR #101 / Issue #88 frozen baseline)  
**Status:** **AUTHORITATIVE CONTRACT REVIEW COMPLETE — IMPLEMENTATION ALIGNED**

---

## 1. Executive Summary & Objective

This document establishes the authoritative provider contract review for the **Merchant Webhook Subscription Management UI** (Issue #21). Per SwiftDrop repository discipline (`AGENTS.md`), before any frontend view or client logic depends on external or downstream microservice endpoints, the provider contract must be substantiated against actual repository documentation, OpenAPI specifications, and architectural invariants.

This review confirms:
1. The authoritative API specification is **`contracts/rest/openapi-communications.yaml` (v1.0.0)**, supported by the **Cross-Service Interface Matrix (`docs/contracts/SERVICE_INTERFACE_MATRIX.md` IF-REST-11 and IF-REST-12)**.
2. The owning backend microservice is **Communications Service** (`services/communications-service/`). The backend implementation is scheduled under **Issue #116 (`[N10-B17]`)**, authored by Thuvarahan (`@thuvarahan-t`).
3. As of the current repository state on `origin/test`, `services/communications-service/` is a documentation placeholder without generated application code. The frontend UI implements full contract compliance, robust mockable API abstractions, validation rules, and truthful unavailable/failure states without fabricating backend completion.

---

## 2. Authoritative Provider Contract Details

The contract is reviewed against `contracts/rest/openapi-communications.yaml`, `docs/architecture/RUNTIME_DECISION_REGISTER.md`, and `docs/reviews/NOV_11_COMMUNICATIONS_MERCHANT_REVIEW.md`.

### 2.1 Confirmed Operations & Endpoints

| HTTP Method | Route | Description | Authorization | Request Schema | Response Schema | Status Codes |
|---|---|---|---|---|---|---|
| **`POST`** | `/api/v1/merchants/webhooks` | Register a new webhook endpoint destination for parcel status events. Generates and returns an HMAC-SHA256 signing secret. | Bearer JWT (`MERCHANT` role) | `CreateWebhookSubscriptionRequest` | `WebhookSubscriptionCreatedResponse` | `201 Created`<br>`400 Bad Request`<br>`401 Unauthorized`<br>`403 Forbidden` |
| **`GET`** | `/api/v1/merchants/webhooks` | List all registered webhook endpoints for the authenticated merchant tenant. | Bearer JWT (`MERCHANT` role) | None | `WebhookSubscriptionListResponse` | `200 OK`<br>`401 Unauthorized`<br>`403 Forbidden` |
| **`GET`** | `/api/v1/merchants/webhooks/{id}` | Retrieve details and operational status of a specific webhook subscription. | Bearer JWT (`MERCHANT` role) | None (`subscriptionId` path UUID) | `WebhookSubscriptionResponse` | `200 OK`<br>`401 Unauthorized`<br>`403 Forbidden`<br>`404 Not Found` |
| **`DELETE`** | `/api/v1/merchants/webhooks/{id}` | Deactivate or remove a webhook subscription. Halts future webhook dispatches. | Bearer JWT (`MERCHANT` role) | None (`subscriptionId` path UUID) | None (Empty body) | `204 No Content`<br>`401 Unauthorized`<br>`403 Forbidden`<br>`404 Not Found` |
| **`GET`** | `/api/v1/merchants/webhooks/{id}/deliveries` | Inspect execution outcome logs and retry history for a webhook endpoint. | Bearer JWT (`MERCHANT` role) | Query: `page` (int, def 0), `size` (int, def 20) | `WebhookDeliveryLogListResponse` | `200 OK`<br>`401 Unauthorized`<br>`403 Forbidden`<br>`404 Not Found` |

### 2.2 Supported Webhook Event Types

- **Supported Event Identifier:** **`parcel.status.changed.v1`**
- **Enum Constraint:** `CreateWebhookSubscriptionRequest.allowedEventTypes` is an array of strings strictly restricted to `["parcel.status.changed.v1"]` in v1.0.0.
- **Trigger Invariant:** Published asynchronously by Delivery Operations via transactional outbox to Kafka topic `parcel.lifecycle.v1` upon parcel status transitions (`PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED`).
- **No Speculative Event Types:** No other event types (e.g. `order.created`, `invoice.generated`) exist in the v1.0.0 contract; the UI must not offer unverified event types.

### 2.3 Payload & Schema Specifications

#### 1. `CreateWebhookSubscriptionRequest`
```json
{
  "httpsUrl": "https://warehouse.example.com/api/v1/swiftdrop-callbacks",
  "allowedEventTypes": ["parcel.status.changed.v1"]
}
```
- `httpsUrl`: String, format `uri`, regex `^https://.+`. Plain `http://`, `localhost`, `127.0.0.1`, and private IP ranges are rejected (SSRF defense per `DEC-COM-01`).
- `allowedEventTypes`: Array of strings, non-empty, containing only `parcel.status.changed.v1`.

#### 2. `WebhookSubscriptionCreatedResponse`
```json
{
  "subscriptionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "merchantId": "8b3f1a20-4e2b-4e1a-8e2b-3a1b2c3d4e5f",
  "httpsUrl": "https://warehouse.example.com/api/v1/swiftdrop-callbacks",
  "allowedEventTypes": ["parcel.status.changed.v1"],
  "hmacSigningSecret": "<server-generated-signing-secret>",
  "active": true,
  "createdAt": "2026-10-24T00:00:00Z"
}
```
The angle-bracketed signing-secret value above is a documentation placeholder, not a usable credential. Real signing secrets are generated server-side and must not be copied into documentation or test fixtures.

- `hmacSigningSecret`: Displayed **ONCE** to the merchant user upon creation. Never persisted in frontend browser storage; never returned in subsequent `GET` requests.

#### 3. `WebhookSubscriptionResponse`
```json
{
  "subscriptionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "merchantId": "8b3f1a20-4e2b-4e1a-8e2b-3a1b2c3d4e5f",
  "httpsUrl": "https://warehouse.example.com/api/v1/swiftdrop-callbacks",
  "allowedEventTypes": ["parcel.status.changed.v1"],
  "active": true,
  "createdAt": "2026-10-24T00:00:00Z"
}
```

#### 4. `WebhookDeliveryItem` (Delivery History)
```json
{
  "deliveryId": "c83b1a20-4e2b-4e1a-8e2b-3a1b2c3d4e5f",
  "eventId": "b3e0c0a8-6f1d-4b8a-9a9d-5f3e2d1c0b9a",
  "parcelId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "status": "SUCCESS",
  "attemptCount": 1,
  "lastAttemptAt": "2026-10-24T00:05:00Z",
  "lastHttpStatusCode": 200,
  "lastResponseTimeMs": 142,
  "errorSummary": null
}
```
- `status`: Enum `[PENDING, PROCESSING, SUCCESS, FAILED, EXHAUSTED]`.
- Sensitive third-party response bodies are intentionally omitted to prevent data leakage.

### 2.4 Error Format: RFC 7807 ProblemDetail

All non-2xx responses conform to RFC 7807 `application/problem+json`:
```json
{
  "type": "https://swiftdrop.com/errors/VALIDATION_ERROR",
  "title": "Invalid Webhook Destination",
  "status": 400,
  "detail": "Destination URL must use secure HTTPS protocol. Plain http:// and private IP ranges are rejected.",
  "instance": "/api/v1/merchants/webhooks",
  "code": "INVALID_WEBHOOK_URL",
  "timestamp": "2026-10-24T00:00:00Z"
}
```

---

## 3. Confirmed vs. Unresolved Contract Details & Gaps

### 3.1 Confirmed Invariants
1. **Tenant Isolation:** Communications scopes all webhook operations by the authenticated caller's Keycloak `sub` / merchant tenant (`WHERE merchant_id = :authenticatedMerchantId`). Cross-tenant access fails with 403 Forbidden.
2. **Merchant Approval Guard (`DEC-MER-03`):** Merchants in `PENDING`, `SUSPENDED`, or `REJECTED` status are locked from operational webhook APIs and fail closed with 403 Forbidden (`MERCHANT_NOT_APPROVED`). The UI reflects this status immediately.
3. **SSRF Transport Defense (`DEC-COM-01`):** Plain `http://`, `localhost`, `127.0.0.1`, RFC 1918 private IPv4/IPv6, and `.local`/`.internal` domains are rejected at registration.
4. **One-Time Secret Presentation:** The `hmacSigningSecret` is generated server-side and returned only in the 201 response. The frontend displays it once, prompts the user to copy it, and never writes it to persistent storage (`localStorage`, `sessionStorage`, or cookies).
5. **Durable Delivery Inspection:** Execution outcomes expose attempt counts, latencies, HTTP codes, and error summaries without storing third-party response bodies.

### 3.2 Documented Gaps & Clarifications
1. **Subscription Mutation (`PUT` / `PATCH`):**
   - *Status:* **NOT IN CONTRACT.** `openapi-communications.yaml` v1.0.0 defines `POST`, `GET`, `DELETE`, and `/deliveries`. There is no `PUT` or `PATCH` endpoint for modifying an existing subscription's URL or event filters.
   - *Resolution:* To modify a destination or event filter, the merchant deactivates/deletes the existing subscription and creates a new one. The UI conforms to this by offering registration and deletion without fabricating a nonexistent PUT/PATCH endpoint.
2. **Secret Rotation Endpoint:**
   - *Status:* **UNRESOLVED ARCHITECTURAL PROPOSAL (`DEC-COM-02`).** In `RUNTIME_DECISION_REGISTER.md`, secret rotation (Option A single secret vs Option B dual secret window) is categorized as `PROPOSED — AWAITING REVIEW`. No dedicated `/rotate-secret` endpoint exists in `openapi-communications.yaml` v1.0.0.
   - *Resolution:* Secret renewal is accomplished via deleting and registering a new subscription endpoint. When issue #116 backend is implemented, if Thuvarahan and Suthankan1 approve a dedicated rotation endpoint, it will be added in a backward-compatible contract revision.
3. **Backend Service Status:**
   - *Status:* **PENDING IMPLEMENTATION.** `services/communications-service/` is currently a placeholder. Issue #116 is assigned to Thuvarahan. When the frontend attempts to call `/api/v1/merchants/webhooks`, the Gateway or local proxy returns 404 or connection failure. The UI gracefully surfaces this as a service unavailable state with informative guidance rather than crashing.

---

## 4. UI Architecture & Traceability Matrix

The Merchant Webhook UI is structured as follows:

- **Route:** `/merchant/webhooks` protected by `ProtectedRoute role="MERCHANT"`.
- **Navigation:** Sub-navigation tab bar linking `/merchant` (Account Overview) and `/merchant/webhooks` (Webhook Subscriptions).
- **Approval Check:** Leverages `/api/v1/users/me` profile query. If merchant status is `PENDING`, `SUSPENDED`, or `REJECTED`, displays the appropriate approval banner and locks subscription mutation controls.
- **Client Service:** `src/features/merchant/api/webhooks.ts` encapsulating all 5 REST operations, error mapping, and SSRF destination validation.
- **Components:**
  - `WebhookSubscriptionList`: Displays configured endpoints, status, events, and actions.
  - `WebhookSubscriptionForm`: Form with real-time HTTPS and private-IP validation.
  - `WebhookSecretAlert`: One-time signing secret presentation with copy action and dismissal.
  - `WebhookDeliveryLogsModal`: Accessible modal dialog displaying delivery outcomes and retry history.
  - `WebhookDeleteConfirmModal`: Accessible confirmation modal before destructive deletion.
