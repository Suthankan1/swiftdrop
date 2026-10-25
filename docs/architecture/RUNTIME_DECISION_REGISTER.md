# SwiftDrop Runtime Business Decision Register (November 10 Baseline)

**Milestone:** November 10, 2026 — Full SwiftDrop Release  
**Issue:** [Refs #88 (N10-P01)](https://github.com/Suthankan1/swiftdrop/issues/88) — Runtime Business Decision Resolution & Integration Contract Freeze  
**Author / Integration Coordinator:** Suthankan1 (Platform Lead & Integration Coordinator)  
**Required Human Reviewers:** Harithkeshan (@Harithkeshan), Vibhushan (@Vibhushan-2003)  
**Cross-Team Stakeholders:**  
- Customer Pair: Gajanthan (@Gajanthan23) & Sinthuha (@Sinthuha-n)  
- Merchant Pair: Eric (@EricPraveen) & Thuvarahan (@thuvarahan-t)  
- Courier Pair: Abhinaya (@abhi190503) & Vibhushan (@Vibhushan-2003)  
- Dispatcher/Admin Pair: Pushmitha (@pushmitha20) & Harithkeshan (@Harithkeshan)  
**Target Integration Branch:** `test`  
**Status:** **AUTHOR RECOMMENDATIONS & ARCHITECTURAL CONSENSUS RECORDED — PENDING PEER SIGNOFF**

---

## 1. Executive Summary & Purpose

This document provides the authoritative, cross-cutting **Runtime Business Decision Register** for the SwiftDrop enterprise parcel delivery platform under Architecture Baseline v1.3.

The goal of this register is to establish an explicit, reviewable, implementation-ready baseline for all four core business services (**Accounts & Partners, Pricing & Zones, Delivery Operations, Communications**), the **Spring Cloud Gateway**, and the **GraphQL Query API**. This prevents development teams from building mutually incompatible business logic, conflicting state enums, incompatible money representations, or competing security rules.

### Review and Approval Classification Standards:
Per repository instructions, decisions are categorized as:
- **`APPROVED`**: Decided in the reviewed architecture baseline v1.3, approved ADRs (ADR-0001 through ADR-0008), or verified November 1 login/profile deliverables.
- **`PROPOSED — AWAITING REVIEW`**: Clear, evidence-backed architectural recommendations documented herein, pending formal peer signoff by the designated module owners.
- **`BLOCKED — REQUIRES BUSINESS DECISION`**: Unresolved business or academic policy choices where human module owner / course staff determination is required before implementation can finalize.

---

## 2. Register of Pricing and Quote Decisions

Coordinated across Customer Lead (Sinthuha/Gajanthan), Dispatcher/Admin Lead (HarithKeshan - Issue #93 owner), and Platform.

| Decision ID | Area | Proposed Rule / Invariant | Rationale & Architectural Boundary | Status | Responsible Owner & Reviewer |
|---|---|---|---|---|---|
| **`DEC-PRC-01`** | **Currency** | Default platform currency is **`LKR`** (Sri Lankan Rupee), represented as ISO 4217 code `LKR`. Multi-currency support is structurally allowed via the currency field, but domestic operations operate on LKR. | Aligns with local operational geography (Colombo, Western Province) and course scope; eliminates runtime currency conversion complexity. | **APPROVED** (Baseline v1.3) | HarithKeshan / Sinthuha |
| **`DEC-PRC-02`** | **Money Representation** | All monetary amounts in REST, GraphQL, gRPC, and PostgreSQL are stored and transferred strictly as **integer minor units** (e.g., cents: `1 LKR = 100 minor units`). `500.00 LKR` = `50000`. | Eliminates IEEE 754 floating-point rounding errors and discrepancy between services. Matches OpenAPI `integer` and Protobuf `int64`. | **APPROVED** (v1.3 standard) | HarithKeshan / Suthankan1 |
| **`DEC-PRC-03`** | **Quote Expiry** | Quotes expire **15 minutes** (900 seconds) after calculation (`expiresAt = createdAt + 15m`). Enforced via strict ISO 8601 UTC timestamps. | Protects platform against stale rate usage while providing customers adequate time to complete the booking form. | **PROPOSED — AWAITING REVIEW** | HarithKeshan / Sinthuha |
| **`DEC-PRC-04`** | **Weight Units & Precision** | Metric weight in kilograms (**`kg`**). Allowed precision is **2 decimal places** (10-gram granularity). Minimum weight: `0.01 kg`. Maximum weight: `30.00 kg`. | Matches standard retail courier limits and scale resolutions. | **PROPOSED — AWAITING REVIEW** | HarithKeshan / Vibhushan |
| **`DEC-PRC-05`** | **Parcel Dimensions & Size Categories** | Dimensions measured in centimeters (**`cm`**), integers. Max dimension: `150 cm`. Max combined length + girth: `L + 2*(W + H) <= 300 cm`. Categories:<br>- `SMALL`: max dim <= 25cm, weight <= 1.00kg<br>- `MEDIUM`: max dim <= 50cm, weight <= 5.00kg<br>- `LARGE`: max dim <= 100cm, weight <= 15.00kg<br>- `OVERSIZED`: max dim <= 150cm, weight <= 30.00kg | Standardizes dispatch handling, vehicle loading, and tiered rate calculations. Volumetric weight formula: `(L * W * H) / 5000`. Chargeable weight is `max(actual, volumetric)`. | **PROPOSED — AWAITING REVIEW** | HarithKeshan / Vibhushan |
| **`DEC-PRC-06`** | **Pricing Zones & Rate Versions** | Three hierarchical zones:<br>- `ZONE_1`: Colombo Municipal District (intra-city)<br>- `ZONE_2`: Western Province (suburban/regional)<br>- `ZONE_3`: National / Outstation (island-wide)<br>Rate cards are versioned with immutable strings (e.g., `RATE-2026-V1`). | Zone rules belong strictly to Pricing & Zones. Rate updates create a new version without mutating historical rate tables. | **PROPOSED — AWAITING REVIEW** | HarithKeshan / Pushmitha |
| **`DEC-PRC-07`** | **Quote Validation & Idempotency** | Quotes are **single-use**. At booking time, Delivery calls Pricing via gRPC `ValidateQuote`. If quote is expired, return `422 Unprocessable Entity` (`QUOTE_EXPIRED`). If rate version deactivated, return `422` (`QUOTE_RATE_SUPERSEDED`). Reusing an already booked quote returns `409 Conflict`. Network retries with the same `Idempotency-Key` return the existing booking. | Prevents rate tampering or quote hoarding. Guarantees deterministic booking. | **APPROVED** (Baseline v1.3) | HarithKeshan / Sinthuha |
| **`DEC-PRC-08`** | **Fee Snapshotting Boundary** | When a parcel is booked, Delivery Operations snapshots the agreed fee, currency, rate version, and quote ID into `PARCELS`. Delivery **never** recalculates prices. Pricing **never** writes to `delivery_db`. | Preserves strict microservice data ownership. | **APPROVED** (Baseline v1.3) | HarithKeshan / Sinthuha |

---

## 3. Register of Parcel Lifecycle Rules

Coordinated across Courier Lead (Vibhushan/Abhinaya), Customer Lead (Sinthuha), and Dispatcher Lead (HarithKeshan/Pushmitha).

### 3.1 Authoritative State Transition Matrix

The table below specifies every permitted lifecycle transition in Delivery Operations:

| Current State | Target State | Authorized Actor | Preconditions & Validation Guards | Required Evidence / Input | Concurrency & Version Check | Emitted Event | HTTP Response |
|---|---|---|---|---|---|---|---|
| *(None)* | **`CREATED`** | `CUSTOMER` or `MERCHANT` | Valid accepted quote verified via Pricing gRPC; active sender identity | Pickup & dropoff addresses, recipient details, quote snapshot | Initial version = `1` | `parcel.status.changed.v1` (oldStatus: null) | `201 Created` |
| **`CREATED`** | **`PICKED_UP`** | Assigned `COURIER` | Active courier assignment bound in `DELIVERY_ASSIGNMENTS`; parcel in physical custody | Optional collection note | Optimistic check `expectedVersion == version` -> increments to `version + 1` | `parcel.status.changed.v1` | `200 OK` |
| **`CREATED`** | **`FAILED`** | `CUSTOMER` (unassigned) or `DISPATCHER` | Parcel not yet collected; abort command issued before physical pickup | Mandatory reason code: `CANCELLED_BY_SENDER` or `PICKUP_FAILED_SENDER_ABSENT` | Optimistic check `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`PICKED_UP`** | **`IN_TRANSIT`** | Assigned `COURIER` | Parcel departed origin sorting facility/hub | Hub transit scan note | Optimistic check `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`PICKED_UP`** | **`FAILED`** | Assigned `COURIER` or `DISPATCHER` | Parcel damaged or lost during initial intake | Mandatory reason code: `PARCEL_DAMAGED` or `FORCE_MAJEURE` | Optimistic check `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`IN_TRANSIT`** | **`OUT_FOR_DELIVERY`** | Assigned `COURIER` | Parcel loaded onto last-mile courier delivery vehicle | Vehicle manifest confirmation | Optimistic check `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`IN_TRANSIT`** | **`FAILED`** | Assigned `COURIER` or `DISPATCHER` | Transit accident or irreversible parcel loss | Mandatory reason code: `PARCEL_DAMAGED` or `FORCE_MAJEURE` | Optimistic check `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`OUT_FOR_DELIVERY`** | **`DELIVERED`** *(Terminal)* | Assigned `COURIER` | Physical handover completed at destination | Mandatory proof metadata reference (`proofId`, recipient signature key or photo key, recipient name) | Optimistic check `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |
| **`OUT_FOR_DELIVERY`** | **`FAILED`** *(Terminal)* | Assigned `COURIER` | Delivery attempt unsuccessful | Mandatory reason code: `RECIPIENT_UNAVAILABLE`, `INCORRECT_ADDRESS`, or `REFUSED_BY_RECIPIENT` | Optimistic check `expectedVersion == version` | `parcel.status.changed.v1` | `200 OK` |

### 3.2 Lifecycle Invariant Rules
1. **Forbidden Backward Transitions:** Moving backward (e.g. `IN_TRANSIT -> PICKED_UP`, `DELIVERED -> OUT_FOR_DELIVERY`) is strictly prohibited. Requests fail with `HTTP 422 Unprocessable Entity` (`ILLEGAL_STATE_TRANSITION`).
2. **Terminal Immutability:** `DELIVERED` and `FAILED` are strictly terminal. No subsequent status patches are accepted (`HTTP 409 Conflict` - `TERMINAL_STATE_IMMUTABLE`). Redelivery or dispute handling is outside MVP scope.
3. **Pre-Pickup Failure vs Cancellation (`DEC-DEL-01`):** In accordance with Figure 17 and ERD constraints, early aborts prior to pickup transition from `CREATED` to `FAILED` with explicit pre-pickup reason codes (`CANCELLED_BY_SENDER`, `PICKUP_FAILED_SENDER_ABSENT`). If the team introduces an explicit `CANCELLED` enum value in future, it can be seamlessly supported without altering the transition guard model.
4. **Decoupling Assignment from State:** Dispatcher assignment does **NOT** alter the lifecycle state. The parcel remains in `CREATED` until the courier executes physical pickup.

---

## 4. Register of Courier Assignment Rules

Coordinated across Dispatcher Lead (HarithKeshan/Pushmitha) and Courier Lead (Vibhushan/Abhinaya).

| Decision ID | Topic | Invariant Rule & Specification | Responsible Owner | Status |
|---|---|---|---|---|
| **`DEC-ASG-01`** | **Courier Eligibility** | Courier must hold Keycloak realm role `COURIER` AND active business status in Accounts (`status = 'ACTIVE'`, `eligible_for_dispatch = true`). Verified at dispatch time via synchronous gRPC (`CheckCourierEligibility`). Fails closed on timeout. | Accounts / Delivery | **APPROVED** (v1.3 standard) |
| **`DEC-ASG-02`** | **Assignable Parcel States** | Only parcels in `CREATED` state are eligible for courier assignment. Parcels in `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, or `FAILED` cannot be assigned or reassigned. | Delivery Operations | **APPROVED** (v1.3 standard) |
| **`DEC-ASG-03`** | **One Active Assignment Invariant** | A parcel may have **at most one active assignment** at any moment. Enforced in PostgreSQL via partial unique index: `CREATE UNIQUE INDEX idx_unique_active_assignment ON delivery_assignments (parcel_id) WHERE active = true;`. | Delivery Operations | **APPROVED** (Review #12) |
| **`DEC-ASG-04`** | **Active Workload Capacity** | Recommended operating capacity is **5 active parcels** per courier. Dispatcher UI displays warning if capacity is exceeded. Enforced as soft-cap or hard-cap depending on final dispatcher consensus. | Dispatcher / Courier | **PROPOSED — AWAITING REVIEW** (DA-01) |
| **`DEC-ASG-05`** | **Assignment Acceptance Model** | **Direct Assignment Model:** Assignment by dispatcher creates an immediate active binding in `DELIVERY_ASSIGNMENTS`. No separate `PENDING_ACCEPTANCE` state machine in Delivery for MVP, avoiding operational dispatch deadlocks. Courier views active assignments immediately. | Dispatcher / Courier | **PROPOSED — AWAITING REVIEW** (DA-01) |
| **`DEC-ASG-06`** | **Reassignment Conditions** | If a courier is incapacitated or vehicle breaks down prior to pickup, Dispatcher may reassign: the current assignment row is marked `active = false` (`ended_at = NOW()`), and a new active assignment row is inserted for the substitute courier. Allowed only while parcel is in `CREATED`. | Dispatcher / Courier | **PROPOSED — AWAITING REVIEW** (DA-01) |
| **`DEC-ASG-07`** | **Assignment Audit vs Status History** | Assignment changes are persisted strictly in `DELIVERY_ASSIGNMENTS` (`assignment_id`, `parcel_id`, `courier_sub`, `assigned_by`, `assigned_at`, `active`). They are **never** logged as fake entries in `STATUS_HISTORY` and do **not** emit `parcel.status.changed.v1` events. | Delivery Operations | **APPROVED** (Review #13 / DA-03) |

---

## 5. Register of Merchant Identity & Credential Policy

Coordinated across Merchant Lead (Thuvarahan/Eric) and Platform Lead (Suthankan1).

| Decision ID | Item | Approved / Recommended Specification | Status |
|---|---|---|---|
| **`DEC-MER-01`** | **Machine Authentication Approach** | **Option A (Scoped API Keys)** is selected for merchant machine-to-machine integration. Format: `swd_live_<random_base62_32_chars>`. Header: `X-API-Key: swd_live_...`. | **APPROVED** (Merchant Lead Sign-Off Refs #22 & [ADR-0010](adr/ADR-0010-merchant-credential-lifecycle.md)) |
| **`DEC-MER-02`** | **Key Storage & Hashing** | Plaintext secret is displayed **once** upon generation in the Merchant Portal and never stored. `accounts_db.merchant_api_keys` stores a SHA-256 digest, key prefix (first 13 chars), merchant ID FK, scopes, and expiration. Constant-time verification (`MessageDigest.isEqual`) enforced. | **APPROVED** (Merchant Lead Sign-Off Refs #22 & [ADR-0010](adr/ADR-0010-merchant-credential-lifecycle.md)) |
| **`DEC-MER-03`** | **Authentication vs Approval Separation** | A cryptographically valid API key authenticates the tenant identity, but Delivery verifies that the merchant organization is in `status = 'APPROVED'` in Accounts before accepting orders. Merchants in `PENDING`, `SUSPENDED`, or `REJECTED` state fail closed with `HTTP 403 Forbidden` (`MERCHANT_NOT_APPROVED`). | **APPROVED** (Baseline v1.3 & Review #80) |
| **`DEC-MER-04`** | **Key Scopes & Rotation** | Supported scopes: `parcels:create`, `parcels:read`, `webhooks:manage`. Merchants may have up to 3 active keys to allow zero-downtime key rotation before revoking older keys. | **APPROVED** (Merchant Lead Sign-Off Refs #22 & [ADR-0010](adr/ADR-0010-merchant-credential-lifecycle.md)) |
| **`DEC-MER-05`** | **Frontend Credential Hygiene** | Merchant machine API keys must **never** be exposed in the React frontend, committed to version control, or stored in `.env`. They are strictly for merchant enterprise backend integrations. | **APPROVED** (Repository Rule) |

---

## 6. Register of Kafka Event & Schema Contracts

Coordinated across Delivery Lead (Vibhushan), Communications Lead (Thuvarahan), and Platform Lead (Suthankan1).

| Decision ID | Topic | Specification | Status |
|---|---|---|---|
| **`DEC-EVT-01`** | **Topic & Event Naming** | Pinned Topic: **`parcel.lifecycle.v1`**<br>Event Type: **`parcel.status.changed.v1`**<br>Schema Version: `1.0.0` | **APPROVED** (Baseline v1.3) |
| **`DEC-EVT-02`** | **Partitioning & Ordering** | Partition key is strictly **`parcelId`** (UUID string). All lifecycle events for the same parcel are routed to the same partition, guaranteeing strict per-parcel FIFO ordering. No global ordering across distinct parcels is required. | **APPROVED** (Baseline v1.3) |
| **`DEC-EVT-03`** | **Data Minimization & Privacy** | The event envelope contains only non-sensitive operational identifiers: `eventId`, `eventType`, `schemaVersion`, `aggregateId` (`parcelId`), `aggregateVersion`, `occurredAt`, `correlationId`, `oldStatus`, `newStatus`, `reason`, `merchantId`, `courierSub`. **Zero customer contact information, phone numbers, recipient street addresses, bearer tokens, or private proof URLs are permitted in event payloads.** | **APPROVED** (Review #10 / #12) |
| **`DEC-EVT-04`** | **Delivery Guarantees & Deduplication** | Kafka KRaft provides **durable at-least-once delivery**. Outbox relay retries may cause duplicates. Downstream consumers (Communications, SSE) enforce idempotent deduplication via transactional inbox tables (`PK (consumer_name, event_id)`) and reject stale events where `aggregateVersion <= currentVersion`. Universal exactly-once delivery is explicitly rejected. | **APPROVED** (Review #10) |

---

## 7. Register of Webhook & Notification Contracts

Coordinated across Merchant Lead (Thuvarahan), Communications Lead (Thuvarahan/Suthankan1), and Platform Lead.

| Decision ID | Topic | Specification | Status |
|---|---|---|---|
| **`DEC-COM-01`** | **Webhook Transport Security** | Target webhook URLs must strictly use **HTTPS** (`https://...`, TLS 1.2+). Plain `http://`, `localhost`, `127.0.0.1`, and RFC 1918 private IP ranges are rejected at registration (SSRF defense). | **APPROVED** (Review #10) |
| **`DEC-COM-02`** | **Cryptographic Signing Standard** | Payloads are signed with **HMAC-SHA256**. Header: `X-SwiftDrop-Signature: t={timestamp},v1={hex_digest}`. Signed content is `${timestamp}.${raw_payload_json}`. Receivers reject webhooks where `abs(current_time - t) > 300 seconds` (5-minute replay window). | **PROPOSED — AWAITING REVIEW** (DEC-COM-02) |
| **`DEC-COM-03`** | **Retry Policy & Backoff** | Bounded exponential backoff with jitter across **5 attempts**: immediate, +1m, +5m, +15m, +1h, +4h (maximum retry ceiling 6 hours). Transient errors (5xx, connect timeouts) are retried; non-retryable 4xx errors (400, 401, 403, 404, 410) fail immediately. | **PROPOSED — AWAITING REVIEW** (DEC-COM-01) |
| **`DEC-COM-04`** | **Dead-Letter Handling** | After 5 failed attempts, the webhook delivery record transitions to `EXHAUSTED` in `WEBHOOK_DELIVERIES`. Merchants can inspect failure logs via `/api/v1/merchants/webhooks/{id}/deliveries`. | **PROPOSED — AWAITING REVIEW** (DEC-COM-04) |
| **`DEC-COM-05`** | **Recipient Contact Resolution** | Communications Service resolves customer email/phone for notifications via authenticated internal gRPC calls to Accounts Service (`ResolveRecipientContact`) using the opaque `recipient_sub` from the parcel record. Contact details are not published on Kafka. | **APPROVED** (Review #10) |

---

## 8. Register of SSE & Data Retention Policies

Coordinated across Customer Lead (Gajanthan), Courier Lead (Vibhushan), and Platform Lead (Suthankan1).

| Decision ID | Topic | Specification | Status |
|---|---|---|---|
| **`DEC-SSE-01`** | **SSE Live Streaming Endpoint** | Endpoint: `GET /api/v1/tracking/{trackingNumber}/events`. Public stream sends sanitized status updates. Authenticated stream (`GET /api/v1/parcels/{id}/events`) requires Bearer JWT. | **APPROVED** (Baseline v1.3) |
| **`DEC-SSE-02`** | **Multi-Replica Fan-Out Topology** | Each horizontally scaled Delivery SSE replica registers an **independent, unique Kafka consumer group** (`tracking.<pod-id>`). This ensures every replica receives 100% of the topic partitions and broadcasts updates to its connected browser clients. | **APPROVED** (Review #10 / #12) |
| **`DEC-SSE-03`** | **Authoritative REST Recovery** | On connection drop or reconnect, the React client does not attempt complex Kafka offset replay; it executes authoritative REST `GET /api/v1/tracking/{trackingNumber}` to catch up before listening to fresh SSE events. | **APPROVED** (Review #10) |
| **`DEC-RET-01`** | **Data Retention Periods** | - Parcel master records and status history: **7 years** (commercial/legal tax compliance).<br>- Courier assignment history: **1 year**.<br>- Outbox events and Consumed events (Inbox): **30 days** post-processing.<br>- Webhook delivery logs & attempts: **30 days**.<br>- Private proof-of-delivery photos: **90 days** in S3/MinIO bucket. | **PROPOSED — AWAITING REVIEW** |

---

## 9. Register of Gated Optional Features

In accordance with Section 15 of Issue #88 guidelines:

| Feature | Architectural Decision | Responsible Lead | Current Status |
|---|---|---|---|
| **AI Assistant / LLM Chatbot** | **STRICTLY GATED / DEFERRED.** Optional academic bonus only. No AI assistant code, models, or dependencies shall be introduced prior to complete verification of core business services. | Platform / Lecturer Confirmation | **GATED — NOT IN SCOPE** |
| **Custom Email OTP SPI** | **GATED / REJECTED PER ADR-0009.** Native Keycloak email verification links and RFC 6238 TOTP MFA provide complete, verified security (verified in #18 and #47). Custom SPI development is deferred. | Platform / Security QA | **DEFERRED (ADR-0009)** |
| **Kubernetes / Helm Deployment** | **RESERVED TO PLATFORM LEAD.** Container orchestration, Helm charts, and K8s manifests are owned exclusively by Suthankan1 and are out of scope for Issue #88 contract freeze. | Suthankan1 | **PLATFORM OWNED** |

---

## 10. Human Owner Approval Gate & Action Items

Per repository discipline, an AI agent cannot fabricate human signoff. The following table identifies the exact human approvals required to promote PROPOSED decisions to APPROVED:

| Required Reviewer | Role / Pair | Decisions Requiring Explicit Signoff | Action Link / Issue |
|---|---|---|---|
| **HarithKeshan (@Harithkeshan)** | Dispatcher/Admin Lead & Report Lead | Pricing parameters (`DEC-PRC-03` to `06`), Quote expiry (15m), Zone rules, Pricing foundation (#93) | Refs [#88](https://github.com/Suthankan1/swiftdrop/issues/88), [#93](https://github.com/Suthankan1/swiftdrop/issues/93) |
| **Vibhushan (@Vibhushan-2003)** | Courier Lead & Security QA Lead | State transition matrix, Terminal failure codes, Proof metadata policy, Courier assignment invariants (`DEC-ASG-01` to `07`) | Refs [#88](https://github.com/Suthankan1/swiftdrop/issues/88), [#95](https://github.com/Suthankan1/swiftdrop/issues/95), [#98](https://github.com/Suthankan1/swiftdrop/issues/98) |
| **Thuvarahan (@thuvarahan-t)** | Merchant Lead | Scoped API key policy (`DEC-MER-01` to `04`) **APPROVED** per [ADR-0010](adr/ADR-0010-merchant-credential-lifecycle.md) & PR #22; Webhook HMAC signature standard (`DEC-COM-02`), Webhook retry ceiling (`DEC-COM-03`) | Refs [#22](https://github.com/Suthankan1/swiftdrop/issues/22), [#88](https://github.com/Suthankan1/swiftdrop/issues/88), [#96](https://github.com/Suthankan1/swiftdrop/issues/96), [#97](https://github.com/Suthankan1/swiftdrop/issues/97) |
| **Sinthuha (@Sinthuha-n)** | Customer Lead (BE) | Customer quote-to-booking handoff, Protobuf contract freeze handoff (#92) | Refs [#88](https://github.com/Suthankan1/swiftdrop/issues/88), [#92](https://github.com/Suthankan1/swiftdrop/issues/92) |
