# Provider Contract Review — Customer History, Tracking & Receipt Download (Issue #27 / LATER-01)

**Task:** Issue #27 (`[LATER-01] Build Customer History/Tracking View and Receipt Download Wiring`)  
**Lead Owner / Contributor:** Sinthuha (@Sinthuha-n), Customer Lead  
**Cross-Team Reviewers:**  
- **Gajanthan (@Gajanthan23)** — Customer FE Lead (Customer journey & SPA navigation)  
- **Suthankan1 (@Suthankan1)** — Platform Lead & Security Lead (PDF receipt backend adapter PR #200 & Gateway JWT routing)  
- **Vibhushan (@Vibhushan-2003)** — Courier Lead & Security QA Lead (Delivery status machine PR #204 & independent security review)  
**Target Integration Branch:** `test`  
**Feature Branch:** `feature/later-01-customer-history-tracking`  
**Contract Baseline Reference:**  
- `contracts/rest/openapi-delivery.yaml` (v1.0.0, PR #101 / Issue #88 frozen baseline)  
- `contracts/rest/problem-detail.yaml` (RFC 7807 / RFC 9457)  
- `contracts/graphql/schema.graphql` (v1.0.0, `CustomerDashboard`, `PublicTrackingView`)  
- `docs/contracts/SERVICE_INTERFACE_MATRIX.md` (IF-REST-04, IF-REST-05, IF-REST-06, IF-GQL-01)  
- `docs/contracts/LATER_09_PDF_RECEIPT_ADAPTER.md` (Issue #26 / PR #200)  
**Status:** **AUTHORITATIVE CONTRACT REVIEW & IMPLEMENTATION ALIGNED**

---

## 1. Executive Summary & Objective

This document establishes the authoritative provider contract review for the **Customer History, Tracking View, and Secure PDF Receipt Download Wiring** (Issue #27). Per SwiftDrop repository discipline (`AGENTS.md`), before any frontend view or client logic depends on external or downstream microservice endpoints, the provider contract must be substantiated against actual repository documentation, OpenAPI specifications, and architectural invariants.

This review confirms:
1. The authoritative API specification is **`contracts/rest/openapi-delivery.yaml` (v1.0.0)**, supported by the **Cross-Service Interface Matrix (`docs/contracts/SERVICE_INTERFACE_MATRIX.md` IF-REST-04, IF-REST-05, IF-REST-06)** and **GraphQL Composite Schema (`contracts/graphql/schema.graphql`)**.
2. The owning backend microservice is **Delivery Operations** (`services/delivery-service/`). Crucial backend providers have been merged into `origin/test`:
   - **LATER-09 / Issue #26 (PR #200):** Bounded authorized PDF delivery receipt adapter (`ReceiptController.java` at `GET /api/v1/parcels/{parcelId}/receipt`) independently verified with 38 automated tests.
   - **Issue #23 (PR #204):** Guarded parcel transitions and append-only status history (`StatusController.java`).
   - **Issue #24 (PR #206):** Active courier assignment command (`AssignmentController.java`).
   - **Issue #20 / LATER-02 (PR #194):** Delivery booking foundation and canonical schema.
3. The frontend customer history view, tracking modal view, and PDF receipt download wiring have been implemented with complete contract fidelity, zero client-side PDF fabrication, robust RFC 7807 problem-detail handling, accessible WCAG 2.1 AA keyboard/screen-reader navigation, and verified with 194 automated frontend tests.

---

## 2. Authoritative Provider Contract Details

The contract is reviewed against `contracts/rest/openapi-delivery.yaml`, `docs/architecture/BASELINE.md`, and `docs/report/customer_journey_review.md`.

### 2.1 Confirmed Operations & Endpoints

| HTTP Method | Route | Description | Authorization | Request Schema | Response Schema | Status Codes |
|---|---|---|---|---|---|---|
| **`GET`** | `/api/v1/parcels` | Paginated delivery history listing for the authenticated customer subject. | Bearer JWT (`CUSTOMER` role) | Query: `page` (int, def 0), `size` (int, def 10) | `CustomerDeliveriesPage` | `200 OK`<br>`401 Unauthorized`<br>`403 Forbidden` |
| **`GET`** | `/api/v1/parcels/{parcelId}` | Retrieve full authoritative parcel details, current lifecycle state, addresses, and fee snapshot. | Bearer JWT (`CUSTOMER`, `COURIER`, or staff) | None (`parcelId` path UUID) | `ParcelDetailResponse` | `200 OK`<br>`401 Unauthorized`<br>`403 Forbidden`<br>`404 Not Found` |
| **`GET`** | `/api/v1/tracking/{trackingNumber}` | Public redacted parcel tracking view with milestone checkpoints. Redacts all sensitive PII. | Anonymous (Public, Gateway rate-limited) | None (`trackingNumber` path string) | `PublicTrackingResponse` | `200 OK`<br>`404 Not Found` |
| **`GET`** | `/api/v1/parcels/{parcelId}/receipt` | Download official PDF delivery receipt (bounded rendering via Apache PDFBox backend adapter). | Bearer JWT (Parcel creator, merchant, or recipient) | None (`parcelId` path UUID) | Binary PDF (`application/pdf`) | `200 OK`<br>`400 Bad Request`<br>`401 Unauthorized`<br>`403 Forbidden`<br>`404 Not Found`<br>`422 Unprocessable` |

---

## 3. Lifecycle States & Milestone Semantics

The tracking view visualizes the authoritative forward state machine defined in `openapi-delivery.yaml` and `docs/reviews/ISSUE_23_STATUS_HISTORY_READINESS.md`:

```
[CREATED] ──> [PICKED_UP] ──> [IN_TRANSIT] ──> [OUT_FOR_DELIVERY] ──> [DELIVERED] (Terminal)
    │              │               │                   │
    └──────────────┴───────────────┴───────────────────┴─────────────> [FAILED] (Terminal)
```

1. **`CREATED`**: Booking committed; awaiting courier assignment and first-mile dispatch.
2. **`PICKED_UP`**: Collected from sender origin; parcel in physical courier custody.
3. **`IN_TRANSIT`**: Moving between sorting hubs and provincial transport facilities.
4. **`OUT_FOR_DELIVERY`**: Loaded onto courier last-mile vehicle for delivery to destination.
5. **`DELIVERED`**: Completed successfully at destination address. **Only completed parcels are eligible for official PDF receipt issuance.**
6. **`FAILED`**: Terminal failure state (e.g., `RECIPIENT_UNAVAILABLE`, `PARCEL_DAMAGED`, `FORCE_MAJEURE`). Receipt is unavailable.

---

## 4. Security & Privacy Boundaries

1. **Broken Object-Level Authorization (BOLA / IDOR) Prevention:**
   - The frontend forwards Keycloak Bearer JWT access tokens in the `Authorization: Bearer <token>` header.
   - Delivery Operations independently extracts `jwt.sub` and verifies `WHERE created_by_sub = :callerSub` (or checks merchant tenancy).
   - Mismatched caller subject returns HTTP 403 ProblemDetail (`"Access is denied."`).
   - The frontend never infers ownership merely from route parameters or client-side variables.

2. **Secure PDF Receipt Download Wiring:**
   - **Zero Client-Side PDF Generation:** PDF documents are generated exclusively on the Delivery Operations backend using Apache PDFBox with immutable fee snapshots and completion timestamps.
   - **Content-Type Validation:** Client strictly verifies `Content-Type: application/pdf` before processing. Non-PDF responses (JSON errors, HTML error pages) throw `DeliveryError('invalid-response')`.
   - **Filename Sanitization:** Attachment filenames from `Content-Disposition` (e.g. `receipt-SWD-20261025-XXXX.pdf`) are sanitized against directory traversal (`../`, `/`, `\`) and dangerous characters, guaranteeing a safe `.pdf` filename.
   - **Resource & Memory Management:** Blobs are converted to temporary Object URLs, downloaded via DOM anchor click, and immediately revoked via `window.URL.revokeObjectURL(url)` to prevent browser memory leaks.
   - **Idempotency & Concurrency Guard:** The download button is disabled with an accessible spinner during active downloads, preventing duplicate concurrent requests.

3. **Public Tracking Redaction (DEC-DEL-02):**
   - Public tracking endpoint (`GET /api/v1/tracking/{trackingNumber}`) strictly redacts recipient full street addresses, phone numbers, driver identities, and private proof URLs.

4. **Server-Sent Events (SSE) Protocol Boundary:**
   - Real-time live tracking updates will stream over an SSE channel (`GET /api/v1/parcels/{id}/events`) in Phase 3b.
   - Live streaming requires an active SSE server broker. As no SSE server exists in the current milestone, the frontend implements on-demand REST tracking queries and transparently surfaces the SSE protocol roadmap notice without fabricating a fake WebSocket or SSE stream.

---

## 5. Automated Verification Evidence

Executed local verification commands:
```bash
cd frontend && pnpm typecheck && pnpm lint && pnpm test --run && pnpm build
```

**Results:**
- **TypeScript Typecheck:** 0 errors (strict mode enabled).
- **ESLint:** 0 warnings, 0 errors.
- **Vitest Suites:** **16 test files / 194 tests passed, 0 failed.**
  - `CustomerHistoryPage.test.tsx`: 11 tests passed (route protection, populated listing, empty state, pagination, PDF download success/error/IDOR, tracking modal view, network retry).
  - `delivery.test.ts`: 18 tests passed (filename sanitization, download trigger, object URL revocation, error mapping, schema validation).
  - All existing frontend regression suites passed cleanly.
- **Vite Production Build:** Successfully built production bundle in 121ms (`dist/index.html`, `dist/assets/*.css`, `dist/assets/*.js`).
