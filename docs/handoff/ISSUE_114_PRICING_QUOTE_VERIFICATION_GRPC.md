# Pricing Quote Verification gRPC Provider Handoff — Issue #114 (N10-B04)

**Issue Title:** [N10-B04] Implement authenticated Pricing quote-verification gRPC provider  
**Issue Link:** [#114](https://github.com/Suthankan1/swiftdrop/issues/114)  
**Producer Module:** Pricing & Zones (`services/pricing-service`)  
**Consumer Module:** Delivery Operations (`services/delivery-service`), Admin / GraphQL Query Service (`services/graphql-query-service`)  
**Assigned Owner:** Pushmitha (@pushmitha20)  
**Domain & Review Owners:** @Harithkeshan (Pricing domain owner), @Sinthuha-n (Delivery booking owner), @Suthankan1 (Platform integration & gRPC security)  
**Target Branch:** `test`  
**Feature Branch:** `feat/n10-b04-pricing-quote-grpc-provider`  
**Date:** October 28, 2026  
**Architecture Baseline:** SwiftDrop v1.3 (`docs/architecture/BASELINE.md`, DEC-PRC-01 to DEC-PRC-08)  

---

## 1. Executive Summary & Purpose

In SwiftDrop v1.3, price quotations are calculated authoritatively by the **Pricing & Zones Service** and persisted as immutable snapshots in `pricing_db.pricing_quotes`. When a customer or merchant proceeds to book a parcel in **Delivery Operations**, the Delivery service must never trust browser-supplied amounts, fees, or currencies, nor may Delivery silently recalculate pricing using internal business rules.

Furthermore, per SwiftDrop microservices boundaries:
1. Delivery Operations must **never** perform direct cross-service SQL joins against `pricing_db`.
2. Pricing Operations must **never** recalculate prices during quote verification or mutate quote records.
3. Machine identity (`DELIVERY_SERVICE`) and human actor delegation (`CUSTOMER`, `MERCHANT`, `ADMIN`) must be cryptographically verified on every invocation.

Issue #114 implements:
1. **Authenticated Quote Verification Provider (`IF-GRPC-04`):** Implements `PricingInternalService.ValidateQuote` on port `9083` backed by `pricing_db.pricing_quotes`. Validates quote existence, expiry against an injected UTC clock, rate-version consistency, currency, and amount assertions. Enforces strict subject and merchant-tenant isolation. Fails closed on unapproved anonymous quote booking attempts.
2. **Bounded Admin Read Summaries:** Implements `PricingInternalService.GetPricingAdminSummary` providing bounded (max 50 items per page), read-only projections of active/all zones and rate versions for authorized `ADMIN` actors.
3. **Delivery Consumer Adapter Seam:** Implements `GrpcBookingQuoteProvider` in `services/delivery-service` fulfilling the `BookingQuoteProvider` seam with a 2000 ms deadline, ensuring Delivery uses the authoritative snapshot values directly.

---

## 2. Architecture & Data Ownership Boundaries

### Data Ownership
- **Pricing & Zones OWNS:**
  - `pricing_db.pricing_zones` (geographic zones, standard transit hours, active status)
  - `pricing_db.pricing_rate_versions` (versioned tariffs, effective timestamps, status)
  - `pricing_db.pricing_rate_rules` (tiered dimensional/weight rate matrix rules)
  - `pricing_db.pricing_quotes` (immutable quote snapshots: UUID, base fee, weight surcharge, total amount minor units, currency, subject sub, tenant ID, is_anonymous, created_at, expires_at)
- **Delivery Operations OWNS:**
  - `delivery_db.parcels`
  - `delivery_db.bookings` / booking idempotency
  - `delivery_db.outbox_events`
- **Zero Cross-Service SQL:**
  - Delivery **never** accesses `pricing_db`.
  - Pricing **never** accesses `delivery_db`.
  - All communication is mediated strictly via compiled Protocol Buffers v3 contracts (`shared/proto-contracts`).

### Zero Re-pricing Invariant
- Verification strictly reads the persisted `QuoteEntity` snapshot.
- `PricingCalculationService.calculatePrice()` is **never** invoked during validation.
- Quotes issued under older tariff versions remain valid until their expiration instant, even if an administrator creates or activates a newer rate version in the meantime.
- No writes or updates are made to `pricing_quotes` during verification (enforced with `@Transactional(readOnly = true)`).

---

## 3. Protocol Buffers Contract Specification

File: [`contracts/grpc/swiftdrop/pricing/v1/pricing.proto`](file:///Users/suthankan/Desktop/Projects/swiftdrop/contracts/grpc/swiftdrop/pricing/v1/pricing.proto)  
Package: `swiftdrop.pricing.v1`

### RPC Services

```protobuf
service PricingInternalService {
  // IF-GRPC-04: Authoritative quote verification for Delivery Operations
  rpc ValidateQuote (ValidateQuoteRequest) returns (ValidateQuoteResponse);

  // Read-only administrative summary of pricing zones and versioned tariffs
  rpc GetPricingAdminSummary (PricingAdminSummaryRequest) returns (PricingAdminSummaryResponse);
}
```

### Additive Backward-Compatible Snapshot Fields
The approved contract preserves existing field numbers 1..7 for `ValidateQuoteResponse` and adds fields 8..14 to return authoritative route and monetary breakdown snapshot data without breaking existing callers:

```protobuf
message ValidateQuoteResponse {
  bool is_valid = 1;
  string quote_id = 2;
  string rate_version = 3;
  int64 verified_amount_minor_units = 4;
  string currency = 5;
  google.protobuf.Timestamp expires_at = 6;
  string validation_code = 7;

  // Additive snapshot fields
  string pickup_zone = 8;
  string dropoff_zone = 9;
  string subject_sub = 10;
  string tenant_id = 11;
  bool is_anonymous = 12;
  int64 base_fee_minor_units = 13;
  int64 weight_surcharge_minor_units = 14;
}
```

---

## 4. Security & Actor Authorization Posture

### Machine Identity
- Configured in `GrpcSecurityPolicy`:
  - `ValidateQuote`: Allowed calling service = `DELIVERY_SERVICE`.
  - `GetPricingAdminSummary`: Allowed calling services = `GRAPHQL_QUERY_SERVICE`, `DELIVERY_SERVICE`.
- Cryptographic verification via `SwiftDropGrpcServerSecurityInterceptor` inspecting `x-swiftdrop-service-token`.
- Unauthenticated or unauthorized services are rejected with `UNAUTHENTICATED` or `PERMISSION_DENIED`.

### Delegated Actor Verification
- Required delegation headers:
  - `x-swiftdrop-actor-sub`: Initiating user subject UUID.
  - `x-swiftdrop-actor-roles`: Claimed roles (e.g. `CUSTOMER`, `MERCHANT`, `ADMIN`).
  - `x-swiftdrop-actor-token`: Signed Keycloak user JWT for cryptographic verification.
- Validated via `ActorDelegationValidator`:
  - Prevents subject spoofing (header sub must match token `sub`).
  - Prevents role elevation (claimed roles must be granted in token).
  - Extracts `tenant_id` from token `merchant_id` claim.

### Resource & Tenant Isolation Rules
1. **Anonymous Quotes:**
   - In accordance with the baseline security posture, anonymous quotes cannot be used for authenticated parcel booking without an approved adoption/binding protocol.
   - Any attempt to validate an anonymous quote for authenticated booking is rejected with `Status.PERMISSION_DENIED` (`"Anonymous quote cannot be used for authenticated parcel booking without approved binding"`).
2. **Customer Quotes:**
   - A caller with `ROLE_CUSTOMER` can only validate quotes where `subjectSub` equals `actor.actorSub()`. Cross-customer attempts are rejected with `PERMISSION_DENIED`.
3. **Merchant Quotes:**
   - A caller with `ROLE_MERCHANT` can only validate quotes where `tenantId` equals `actor.tenantId()`. Cross-tenant attempts are rejected with `PERMISSION_DENIED`.
4. **Admin Quotes:**
   - `ROLE_ADMIN` does not grant unrestricted access to customer quotes without matching delegation.

---

## 5. Delivery Integration & Consumer Seam

### Adapter Implementation
Delivery Operations contains `GrpcBookingQuoteProvider` implementing `BookingQuoteProvider`:
- Dispatches gRPC request to `PricingInternalService.ValidateQuote` with a 2000 ms deadline.
- Maps response directly to `QuoteValidation`.
- Fails closed on any network error, deadline expiration, or `PERMISSION_DENIED` status.

### Booking Foundation Invariant
In `com.swiftdrop.deliveryservice.booking.BookingService`:
- Verified quote values (`quote.verifiedAmountMinorUnits()`, `quote.currency()`, `quote.rateVersion()`) are authoritatively copied into `ParcelBooking`.
- Delivery does not accept client-provided amounts.
- If quote is expired, ineligible, or tampered with, booking is aborted with HTTP 422 or 503.

### Outstanding Delivery Booking Blockers
As documented in `BookingAuthorization.java` and `LATER_02_QUOTE_BOOKING.md`:
- Address-to-zone resolution and route verification before booking remain an open cross-team protocol.
- Anonymous quote conversion/adoption mechanism is unapproved.
- Therefore, the full HTTP booking controller remains disabled/unregistered until those downstream boundaries are approved.

---

## 6. Verification Evidence

| Test Category | Suite / Class | Tests Run | Result |
|---|---|---|---|
| Protobuf & Schema Compatibility | `tests/contracts/validate_contracts.py` | 4 Gates | **PASS** (0 errors, 0 breaking changes) |
| Python Contract Tests | `tests/contracts/test_protobuf_contracts.py` | 10 tests | **PASS** |
| Shared Proto Java Contracts | `com.swiftdrop.grpc.PricingContractTest`, `ContractDescriptorAndSerializationTest`, `DeliveryContractTest` | 28 tests | **PASS** |
| Shared Security Adapter | `com.swiftdrop.security.grpc.*`, `JwtValidationTest` | 39 tests | **PASS** |
| Pricing gRPC Provider Unit & Security | `com.swiftdrop.pricing.grpc.PricingInternalServiceGrpcTest` | 29 tests | **PASS** |
| Pricing PostgreSQL Integration | `com.swiftdrop.pricing.PricingPersistencePostgresIT` | 12 tests | **PASS** |
| Pricing Service Full Regression | `services/pricing-service` (`mvn clean verify`) | 108 tests | **PASS** (96 unit + 12 IT) |
| Delivery Quote Provider Unit Tests | `com.swiftdrop.deliveryservice.quote.GrpcBookingQuoteProviderTest` | 5 tests | **PASS** |
| Delivery Booking Foundation Tests | `com.swiftdrop.deliveryservice.BookingFoundationTest` | 11 tests | **PASS** |

### Post-Merge Conflict Resolution Notes
- Merged latest `origin/test` (incorporating PR #219: `feat/n10-b08-delivery-grpc-read-models`).
- Reconciled RPC method count in `tests/contracts/test_protobuf_contracts.py` from 16/19 to 20 total RPC methods (Accounts: 8, Delivery: 10, Pricing: 2).
- Clean auto-merge across `GrpcSecurityPolicy.java` and `ContractDescriptorAndSerializationTest.java`.
- Verified 100% green across all 6 test suites and confirmed PR #220 status is `MERGEABLE`.

---

## 7. Reviewers & Next Steps

- **Primary Reviewers:**
  - `@Harithkeshan` — Pricing domain owner (contract & quote validation logic)
  - `@Sinthuha-n` — Delivery booking owner (consumer adapter & quote validation seam)
  - `@Suthankan1` — Platform integration & gRPC security policy
- **Next Dependency:** Issue #123 (Admin rate and zone commands — separate issue owned by HarithKeshan).
