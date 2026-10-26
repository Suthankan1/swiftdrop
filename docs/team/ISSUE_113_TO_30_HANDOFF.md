# Delivery Projections Provider Handoff to GraphQL (#30)

**Source Issue:** [Issue #113 (N10-B09)](https://github.com/Suthankan1/swiftdrop/issues/113)  
**Consuming Issue:** [Issue #30 (Authorized GraphQL deliveryStats resolver)](https://github.com/Suthankan1/swiftdrop/issues/30)  
**Provider Service:** `services/delivery-service`  
**Protobuf Package:** `swiftdrop.delivery.v1`  
**Service Contract:** `contracts/grpc/swiftdrop/delivery/v1/delivery.proto`  
**Provider Commit SHA:** `b2f0635`  

---

## 1. Provider RPC Methods & Signatures

The Delivery service exposes three read-projection RPCs on `DeliveryInternalService`:

### A. Bounded Unassigned Parcel Queue
```protobuf
rpc ListUnassignedParcels (ListUnassignedParcelsRequest) returns (ListUnassignedParcelsResponse);
```
- **Request:**
  - `page`: 0-indexed int32 (>= 0).
  - `page_size`: int32 (1 to 50, defaults to 20 if 0).
  - `zone`: string (optional filter).
- **Semantics:**
  - Returns only parcels in `CREATED` status with no active assignment (`da.active = true`).
  - Parcels with historical or ended assignments (`da.active = false`) are eligible as long as current status is `CREATED`.
  - Ordered deterministically by `createdAt ASC, parcelId ASC`.
  - If `zone` is specified, returns empty results because authoritative zone data is not yet persisted in `delivery_db`.

### B. Courier Workload Projections
```protobuf
rpc GetCourierWorkloadSummary (CourierWorkloadSummaryRequest) returns (CourierWorkloadSummaryResponse);
```
- **Request:**
  - `courier_subs`: repeated string (max 50 couriers).
- **Semantics:**
  - Counts only active assignments (`active = true`) for each requested courier.
  - Ended or historical assignments are excluded.
  - Couriers with zero active assignments return `active_assignments_count = 0`.
  - Rejects batches exceeding 50 couriers with `INVALID_ARGUMENT`.

### C. Daily Delivery Outcomes & Aggregate Statistics (for #30)
```protobuf
rpc GetDeliveryStatsInternal (GetDeliveryStatsRequest) returns (GetDeliveryStatsResponse);
```
- **Request:**
  - `start_time`: `google.protobuf.Timestamp` (inclusive start).
  - `end_time`: `google.protobuf.Timestamp` (exclusive end, half-open `[start, end)`).
  - `zone`: string (optional filter).
  - `status`: string (optional status filter: `"CREATED"`, `"DELIVERED"`, `"FAILED"`, `"IN_TRANSIT"`, or empty for all).
- **Response:**
  - `total_count`: int64 (total parcels in reporting window).
  - `delivered_count`: int64 (terminal `DELIVERED` status history records in window).
  - `failed_count`: int64 (terminal `FAILED` status history records in window).
  - `in_transit_count`: int64 (parcels in `PICKED_UP`, `IN_TRANSIT`, or `OUT_FOR_DELIVERY` before/at `end_time`).
  - `created_count`: int64 (parcels created in window).
  - `average_delivery_hours`: optional double (average hours from parcel creation to first `DELIVERED` status transition; omitted/empty if `delivered_count == 0`).

---

## 2. Authentication, Authorization & Security Contract

1. **Service Identity:**
   - Transport: gRPC over TCP (default port `9083`).
   - Header: `x-swiftdrop-service-token` containing a signed RS256 JWT.
   - Allowed Calling Service: `GRAPHQL_QUERY_SERVICE` (or `DELIVERY_OPERATIONS_SERVICE`).
   - Expected Audience: `swiftdrop-internal`.

2. **Delegated Actor Context:**
   - Header: `x-swiftdrop-actor-sub` (Keycloak user UUID).
   - Header: `x-swiftdrop-actor-roles` (comma-separated roles).
   - Header: `x-swiftdrop-actor-token` (signed end-user Keycloak access token).
   - Allowed Roles: `DISPATCHER` or `ADMIN`.
   - Access by `CUSTOMER`, `COURIER`, or `MERCHANT` is denied with `PERMISSION_DENIED`.

3. **Tracing & Observability:**
   - Header: `x-swiftdrop-correlation-id` (alphanumeric, max 64 chars; auto-generated if absent).

---

## 3. Date, Timezone & Half-Open Interval Semantics

- **Timezone:** Timestamps in requests and responses use authoritative UTC `google.protobuf.Timestamp`.
- **Asia/Colombo Reporting Window:**
  - If `start_time` and `end_time` are omitted, the provider defaults to the current day in `Asia/Colombo` (+05:30).
  - Colombo calendar day `YYYY-MM-DD 00:00:00` translates to `(YYYY-MM-DD - 1) 18:30:00 UTC`.
- **Interval Convention:** `[startInclusive, endExclusive)`.
- **Maximum Reporting Range:** 90 days. Ranges exceeding 90 days or where `start_time > end_time` are rejected with `INVALID_ARGUMENT`.

---

## 4. Error Codes & Deadline Requirements

- `UNAUTHENTICATED (16)`: Missing or invalid service token, or missing required actor delegation headers.
- `PERMISSION_DENIED (7)`: Calling service not permitted, or actor lacks `DISPATCHER`/`ADMIN` role.
- `INVALID_ARGUMENT (3)`: Page index < 0, page size > 50, courier batch > 50, start time > end time, or range > 90 days.
- `NOT_FOUND (5)`: Target parcel does not exist.
- `INTERNAL (13)`: Unexpected service error.
- **Recommended Client Deadline:** 2000 ms.

---

## 5. Known Limitations & Dependencies

1. **Zone Data in Delivery:**
   - The current `delivery_db.parcels` schema does not store `pickup_zone` or `dropoff_zone` columns (owned by Pricing/Booking quote-binding).
   - When a specific `zone` is queried, the provider safely returns 0 / empty results to avoid fabricating unverified zone data.
2. **Average Delivery Duration:**
   - Calculated strictly as `MIN(status_history.occurred_at) - parcels.created_at` for parcels delivered within the window.
   - If no parcels were delivered in the window, `average_delivery_hours` is omitted (null in GraphQL).
