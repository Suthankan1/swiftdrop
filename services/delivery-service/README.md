# Delivery Operations Service

Delivery Operations owns parcel booking, parcel lifecycle states, assignments, status audit history, proof metadata, fee snapshots, transactional outbox event publication, and official PDF delivery receipts.

## Architecture & Responsibilities

- **Platform lead's bounded slice (LATER-09):** Bounded Authorized PDF Delivery Receipt Backend Adapter.
- **Customer pair:** Booking foundation, parcel tracking, customer history.
- **Courier pair:** Courier assignment status, delivery notes, proof-of-delivery capture.
- **Dispatcher pair:** Courier assignment, workload balancing, operational tracking.

## Official Receipt Endpoint

Conforms strictly to `contracts/rest/openapi-delivery.yaml`:

- **Path:** `GET /api/v1/parcels/{parcelId}/receipt`
- **Method:** `GET`
- **Security:** Bearer JWT (Keycloak OIDC)
- **Content-Type:** `application/pdf`
- **Headers:**
  - `Content-Disposition: attachment; filename="receipt-SWD-YYYYMMDD-XXXX.pdf"`
  - `Cache-Control: no-store, no-cache, must-revalidate, max-age=0`
  - `Pragma: no-cache`
  - `X-Content-Type-Options: nosniff`

### Security & Object-Level Authorization (BOLA/IDOR Defense)

Receipts contain sensitive commercial and personal delivery data and are strictly access-controlled:
1. **Parcel Creator:** Requester `sub` must match `createdBySub`.
2. **Merchant Tenant:** Requester holding `MERCHANT` role must have matching `merchant_id` claim.
3. **Recipient:** Requester matching verified `phone_number` claim.
4. **Staff Oversight:** Requesters holding `ADMIN` or `DISPATCHER` roles.
5. **Cross-access denial:** Requesters without verified ownership receive `403 Forbidden` RFC 7807 ProblemDetail.
6. **Unauthenticated denial:** Missing or invalid tokens receive `401 Unauthorized` RFC 7807 ProblemDetail.
7. **Eligibility gate:** Non-completed parcels (not `DELIVERED`) or unconfirmed payments fail closed with `404 Not Found` ("Receipt not available or parcel not completed").

### Resource Bounds & Safety

PDF generation is deterministic, in-process, and strictly bounded using Apache PDFBox 3.0.5:
- **Max text length:** 2,000 characters per field.
- **Max financial line items:** 50 items.
- **Max document pages:** 5 pages (receipts format to 1 page).
- **Max output byte size:** 1,048,576 bytes (1 MB).
- **Zero SSRF:** Zero external network calls, zero external fonts, zero external template engines. Uses built-in Standard 14 PDF Helvetica fonts.
- **Zero float errors:** Minor-unit integer arithmetic preserved with exact decimal string formatting.

## Running Tests

```bash
# Run unit and security tests
mvn -f services/delivery-service/pom.xml test

# Package service jar
mvn -f services/delivery-service/pom.xml package
```
