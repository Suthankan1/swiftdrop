# Internal gRPC & Protobuf Service Contracts — Specification & Handoff to Issue #92

**Status:** CONTRACT SPECIFICATION FROZEN FOR ISSUE #92 IMPLEMENTATION  
**Task Dependency:** Refs [#88 (N10-P01)](https://github.com/Suthankan1/swiftdrop/issues/88) -> Handoff to [#92 (N10-B01)](https://github.com/Suthankan1/swiftdrop/issues/92)  
**Assigned Owner for Protocol Buffer Generation:** Sinthuha (@Sinthuha-n)  
**Security/Delegation Coordination:** Suthankan1 (Platform Lead, [#100](https://github.com/Suthankan1/swiftdrop/issues/100))  
**Required Reviewers:** Harithkeshan, Vibhushan-2003, thuvarahan-t  

---

## 1. Scope & Coordination Boundary

In accordance with repository instructions (`AGENTS.md`) and the contract freeze rules of Issue #88:
- **No Conflicting `.proto` Scaffolds:** Issue #88 defines the reviewed RPC methods, packages, message shapes, field types, stable field tag assignments, error codes, and metadata headers. Issue #92 (owned by Sinthuha) will create the versioned `.proto` files in `contracts/grpc/` and configure the Maven `protobuf-maven-plugin` generation pipeline.
- **Runtime Target:** Java 21 LTS, Spring Boot 4.1.1, `grpc-spring-boot-starter` (or native gRPC Java 1.68+).
- **No Browser gRPC:** gRPC is strictly an internal, private service-to-service transport across backend microservices and the GraphQL Query API. Browsers never establish direct gRPC connections.

---

## 2. Package Namespaces & Syntax Standard

All Protocol Buffer contracts must adhere to:
1. `syntax = "proto3";`
2. `java_multiple_files = true;`
3. Standard Java package namespace: `com.swiftdrop.<service>.v1`

| Service | Protobuf Package | Java Package | Primary Responsibilities |
|---|---|---|---|
| **Accounts & Partners** | `swiftdrop.accounts.v1` | `com.swiftdrop.accounts.v1` | Courier eligibility verification, merchant key validation, customer contact resolution for notifications |
| **Pricing & Zones** | `swiftdrop.pricing.v1` | `com.swiftdrop.pricing.v1` | Quote validation and pricing snapshot verification during parcel booking |
| **Delivery Operations** | `swiftdrop.delivery.v1` | `com.swiftdrop.delivery.v1` | Internal parcel detail queries, active workload counts, assignment lookups for GraphQL resolvers |

---

## 3. Data Representation Standards

To prevent cross-service incompatibilities, all `.proto` messages must use the following standard representations:

1. **Monetary Amounts:**
   - **Type:** `int64` minor units.
   - **Rule:** Never use floating-point types (`float`, `double`) for money. E.g., `500.00 LKR` is encoded as `int64 amount_minor_units = 50000`.
   - **Currency:** `string currency = "LKR"` (ISO 4217 three-letter code).
2. **Timestamps:**
   - **Type:** `google.protobuf.Timestamp` (UTC).
3. **Identifiers:**
   - **Type:** `string` (RFC 4122 UUID strings, e.g. `"f47ac10b-58cc-4372-a567-0e02b2c3d479"`).
4. **Weights and Dimensions:**
   - **Weight:** `int32 weight_grams` (or `double weight_kg` with scale 2). Recommended: `int32 weight_grams = 2500` for 2.50 kg to eliminate floating-point drift.
   - **Dimensions:** `int32 length_cm`, `int32 width_cm`, `int32 height_cm`.
5. **Stable Field Numbers:**
   - Field tags once assigned are **permanent**. Never renumber fields. When deprecating a field, mark it `reserved`.

---

## 4. Service Definitions & RPC Specifications

### 4.1 Accounts Service (`com.swiftdrop.accounts.v1.AccountsInternalService`)

Used by Delivery Operations and Communications Service.

```protobuf
syntax = "proto3";

package swiftdrop.accounts.v1;

option java_multiple_files = true;
option java_package = "com.swiftdrop.accounts.v1";

service AccountsInternalService {
  // Verifies if a courier is active and eligible for new parcel assignments
  rpc CheckCourierEligibility (CourierEligibilityRequest) returns (CourierEligibilityResponse);

  // Validates a merchant API key digest during automated order booking
  rpc ValidateMerchantKeyDigest (MerchantKeyDigestRequest) returns (MerchantKeyDigestResponse);

  // Resolves recipient notification contact handles (email/phone) without exposing PII in events
  rpc ResolveRecipientContact (RecipientContactRequest) returns (RecipientContactResponse);
}

message CourierEligibilityRequest {
  string courier_sub = 1; // Keycloak subject string
}

message CourierEligibilityResponse {
  string courier_sub = 1;
  bool is_eligible = 2;
  bool is_active = 3;
  string full_name = 4;
  string rejection_reason = 5; // e.g. "COURIER_SUSPENDED", "COURIER_NOT_FOUND"
}

message MerchantKeyDigestRequest {
  string key_prefix = 1;
  string key_digest = 2; // SHA-256 or Argon2 digest
}

message MerchantKeyDigestResponse {
  bool is_valid = 1;
  string merchant_id = 2; // UUID
  string business_name = 3;
  string status = 4; // "APPROVED", "PENDING", "SUSPENDED"
  repeated string scopes = 5; // ["parcels:create", "parcels:read"]
}

message RecipientContactRequest {
  string recipient_sub = 1;
}

message RecipientContactResponse {
  string recipient_sub = 1;
  string email = 2;
  string phone_number = 3;
  string full_name = 4;
}
```

### 4.2 Pricing Service (`com.swiftdrop.pricing.v1.PricingInternalService`)

Used by Delivery Operations during parcel booking.

```protobuf
syntax = "proto3";

package swiftdrop.pricing.v1;

import "google/protobuf/timestamp.proto";

option java_multiple_files = true;
option java_package = "com.swiftdrop.pricing.v1";

service PricingInternalService {
  // Validates that a quote exists, is not expired, and fee matches
  rpc ValidateQuote (ValidateQuoteRequest) returns (ValidateQuoteResponse);
}

message ValidateQuoteRequest {
  string quote_id = 1; // UUID
  string expected_rate_version = 2;
  int64 expected_amount_minor_units = 3;
  string expected_currency = 4;
}

message ValidateQuoteResponse {
  bool is_valid = 1;
  string quote_id = 2;
  string rate_version = 3;
  int64 verified_amount_minor_units = 4;
  string currency = 5;
  google.protobuf.Timestamp expires_at = 6;
  string validation_code = 7; // "VALID", "QUOTE_EXPIRED", "RATE_SUPERSEDED", "AMOUNT_MISMATCH"
}
```

### 4.3 Delivery Operations Service (`com.swiftdrop.delivery.v1.DeliveryInternalService`)

Used by the GraphQL Query API for dashboard aggregations.

```protobuf
syntax = "proto3";

package swiftdrop.delivery.v1;

import "google/protobuf/timestamp.proto";

option java_multiple_files = true;
option java_package = "com.swiftdrop.delivery.v1";

service DeliveryInternalService {
  // Queries parcel aggregate for internal services / GraphQL resolvers
  rpc GetParcelInternal (GetParcelInternalRequest) returns (GetParcelInternalResponse);

  // Queries active workload count for a specific courier
  rpc GetCourierWorkload (CourierWorkloadRequest) returns (CourierWorkloadResponse);
}

message GetParcelInternalRequest {
  string parcel_id = 1;
}

message GetParcelInternalResponse {
  string parcel_id = 1;
  string tracking_number = 2;
  string current_status = 3; // "CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"
  int64 version = 4;
  int64 amount_minor_units = 5;
  string currency = 6;
  string created_by_sub = 7;
  string merchant_id = 8;
  string active_courier_sub = 9;
  google.protobuf.Timestamp created_at = 10;
}

message CourierWorkloadRequest {
  string courier_sub = 1;
}

message CourierWorkloadResponse {
  string courier_sub = 1;
  int32 active_assignments_count = 2;
}
```

---

## 5. Security & Actor Delegation Metadata Protocol (Coordinating with Issue #100)

Every gRPC call must propagate authenticated metadata headers via gRPC `Metadata`:

| Header Key | Format | Description | Enforcement Rule |
|---|---|---|---|
| `x-swiftdrop-service-token` | JWT String | Signed service-to-service JWT authenticating the calling microservice. | Reject if missing, expired, or invalid signature (`UNAUTHENTICATED`). |
| `x-swiftdrop-actor-sub` | String | Keycloak `sub` of the initiating human user (for delegated requests). | Verified by resource server against tenancy rules. |
| `x-swiftdrop-actor-roles` | Comma-delimited String | Roles of the initiating user (e.g., `DISPATCHER,STAFF`). | Evaluated at service boundary; cannot self-elevate. |
| `x-swiftdrop-correlation-id` | UUID String | End-to-end request tracing identifier. | Injected into MDC logging on each service hop. |

### Deadlines and Timeouts:
- Every client stub **must** set an explicit deadline (recommended default: `2000ms`).
- In case of deadline exceeded (`DEADLINE_EXCEEDED`), callers must fail closed (e.g., abort booking or queue query) rather than assuming permission or success.

---

## 6. Implementation Checklist for Issue #92

- [ ] Place `.proto` files in `contracts/grpc/swiftdrop/accounts/v1/`, `pricing/v1/`, `delivery/v1/`.
- [ ] Configure `protobuf-maven-plugin` with `protoc` 3.25+ and `protoc-gen-grpc-java` 1.68+.
- [ ] Create common Java proto module (`shared/proto-contracts` or equivalent) exportable to consuming services.
- [ ] Verify clean compilation with Java 21 LTS (`mvn compile`).
