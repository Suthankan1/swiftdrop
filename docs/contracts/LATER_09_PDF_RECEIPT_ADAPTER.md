# Provider Contract Review — Authorized PDF Receipt Backend Adapter (Issue [LATER-09])

- **Task:** `[LATER-09] Implement bounded authorized PDF receipt backend adapter`
- **Feature Branch:** `feature/later-09-authorized-pdf-receipt`
- **Owner / Role:** Platform lead (`Suthankan1`) bounded cross-module slice within Delivery Operations.
- **Authoritative Contract:** `contracts/rest/openapi-delivery.yaml` v1.0.0 (`/api/v1/parcels/{parcelId}/receipt`)
- **Status:** **Independently implemented, verified, and secured; production data provider awaiting upstream booking & payment table merge.**

---

## 1. Audit & Context

Reviewed:
- `contracts/rest/openapi-delivery.yaml` (`getDeliveryReceipt`)
- `docs/team/OWNERSHIP.md` (Platform lead's explicitly bounded slice: PDF delivery receipt generation backend within Delivery Operations)
- `docs/report/customer_journey_review.md` (Use case `u6` Download delivery receipt: cryptographically verifiable PDF receipt generated from immutable fee snapshot and completion metadata)
- `docs/security/AUTHORIZATION_MATRIX.md` (Strict server-side ownership verification)
- `docs/architecture/BASELINE.md` (Delivery Operations service boundary, port `:8083`, OAuth2 resource server)
- Parallel features: LATER-02 (quote-to-booking foundation, PR #20) and LATER-03 (merchant webhooks, PR #21 / #171).

---

## 2. Implementation Boundary

### Boundaries Defined:

1. **Authorization Boundary (`ReceiptAuthorizationService`):**
   - Independent verification of Keycloak JWT bearer token (`swiftdrop-api` audience, issuer, subject, expiration).
   - Strict object-level access control (BOLA/IDOR prevention):
     - Creator: `jwt.sub == receipt.createdBySub`
     - Merchant: `jwt.roles.contains("MERCHANT") && jwt.merchant_id == receipt.merchantId`
     - Recipient: `jwt.phone_number == receipt.recipientPhone`
     - Staff: `jwt.roles.contains("ADMIN") || jwt.roles.contains("DISPATCHER")`
     - Fails closed on any mismatched identity with HTTP 403 ProblemDetail.

2. **Receipt Data Boundary (`ReceiptData` & `ReceiptDataProvider`):**
   - Authoritative domain model with strict invariants:
     - `deliveryStatus == "DELIVERED"` (completed parcels only)
     - `paymentConfirmed == true` (confirmed payments only; never fabricates payment from unconfirmed transactions)
     - `amountMinorUnits >= 0` with ISO-4217 currency (`LKR`, etc.)
     - Non-empty line items list (`ReceiptLineItem`).
   - `DefaultReceiptDataProvider`: Safely fails closed in production pending upstream booking/payment database merge (Phase 6 compliance).
   - Test harness uses synthetic authoritative data providers to test 100% of the workflow without mock data in production.

3. **Bounded PDF Rendering Boundary (`PdfReceiptAdapter`):**
   - In-process, deterministic rendering via Apache PDFBox 3.0.5.
   - Built-in Standard 14 PDF fonts (`HELVETICA`, `HELVETICA_BOLD`); zero external font or template files.
   - Strict resource limits enforced (`ReceiptRenderLimits`):
     - Max text length: 2,000 characters per text field
     - Max line items: 50 items
     - Max document pages: 5 pages
     - Max output size: 1,048,576 bytes (1 MB)
   - Text sanitization: removes control characters and escapes non-Latin1 glyphs.
   - Integer minor-unit arithmetic: exact decimal formatting (`%d.%02d`) without floating-point errors.
   - Zero external HTTP/network calls (SSRF-proof), zero shell execution.

4. **HTTP Delivery Boundary (`ReceiptController`):**
   - Path: `GET /api/v1/parcels/{parcelId}/receipt`
   - Content-Type: `application/pdf`
   - Content-Disposition: `attachment; filename="receipt-SWD-YYYYMMDD-XXXX.pdf"` (sanitized filename)
   - Cache-Control: `no-store, no-cache, must-revalidate, max-age=0`
   - Pragma: `no-cache`
   - X-Content-Type-Options: `nosniff`
   - Standardized RFC 7807 ProblemDetail responses for all failure modes:
     - 400 Bad Request (malformed UUID)
     - 401 Unauthorized (missing/invalid JWT)
     - 403 Forbidden (cross-user IDOR attempt)
     - 404 Not Found (parcel not found or not completed)
     - 422 Unprocessable Content (resource limit exceeded)
     - 500 Internal Server Error (rendering failure without leaking stack trace)

---

## 3. Upstream Dependencies & Production Safety

- **Blocker Status:** The Delivery parcel lifecycle state machine (transitions to `DELIVERED`) and payment confirmation records are under parallel active development (Customer/Courier pairs and payment milestone).
- **Safe Handling:** As required by Phase 6:
  - The bounded PDF adapter and authorization layers are 100% implemented and independently verified with 38 automated tests.
  - The production `DefaultReceiptDataProvider` fails closed (returns `Optional.empty()`), preventing generation of unconfirmed or fabricated receipts in production.
  - No dummy database records or mock states are deployed to production.

---

## 4. Verification Evidence

Executed:
```bash
mvn -f services/delivery-service/pom.xml test
```
**Results:** **38 tests run, 0 failures, 0 errors, 0 skipped.**
- `PdfReceiptAdapterTest`: 7 tests passed (structure, magic bytes, branding, fields, determinism, currency math, resource limits).
- `ReceiptAuthorizationServiceTest`: 10 tests passed (owner, cross-customer, merchant tenant, cross-tenant, recipient phone, staff, courier, blank sub).
- `ReceiptIntegrityTest`: 6 tests passed (unconfirmed payment, uncompleted status, negative amount, currency validation, tracking regex).
- `ReceiptServiceTest`: 4 tests passed (orchestration, not found, access denied, limit violation).
- `ReceiptControllerSecurityTest`: 11 tests passed (anonymous 401, untrusted 401, expired 401, wrong-aud 401, 200 PDF+headers, 403 IDOR, 404 not found, 404 uncompleted, 422 limit, 400 malformed UUID, actuator health).

Regression checks:
- `api-gateway`: 19 tests passed (0 failures).
- `accounts-service`: 105 tests passed (0 failures).
