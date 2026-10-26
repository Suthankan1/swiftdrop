# Internal gRPC & Protobuf Service Contracts — Specification & Implementation (Issue #92)

**Status:** CONTRACT SPECIFICATION FROZEN & REPRODUCIBLE GENERATION IMPLEMENTED<br/>
**Task Dependency:** Refs [#88 (N10-P01)](https://github.com/Suthankan1/swiftdrop/issues/88) -> Completed in [#92 (N10-B01)](https://github.com/Suthankan1/swiftdrop/issues/92)<br/>
**Implementation Contributor:** Gajanthan23 (Gajanthan)<br/>
**Assigned Owner:** Gajanthan23 / Sinthuha (@Sinthuha-n)<br/>
**Security/Delegation Coordination:** Suthankan1 (Platform Lead, [#100](https://github.com/Suthankan1/swiftdrop/issues/100))<br/>
**Required Reviewers:** Harithkeshan, Vibhushan-2003, thuvarahan-t

---

## 1. Scope & Coordination Boundary

In accordance with repository instructions (`AGENTS.md`) and the contract freeze rules of Issue #88:
- **Canonical Contracts:** Versioned `.proto` contract files are committed in `contracts/grpc/swiftdrop/` across `accounts/v1/`, `pricing/v1/`, and `delivery/v1/`.
- **Java Generation Module:** `shared/proto-contracts` configures the pinned Maven `protobuf-maven-plugin` (protoc 3.25.5, grpc-java 1.68.1) generating Java classes and gRPC service stubs.
- **Runtime Target:** Java 21 LTS, Spring Boot 4.1.1, `io.grpc:grpc-bom:1.68.1`.
- **No Browser gRPC:** gRPC is strictly an internal, private service-to-service transport across backend microservices and the GraphQL Query API. Browsers never establish direct gRPC connections.
- **Zero Shared Entity JARs:** Microservices maintain independent domain and persistence models; wire communication uses strongly typed Protocol Buffers messages exclusively.

---

## 2. Package Namespaces & Syntax Standard

All Protocol Buffer contracts adhere to:
1. `syntax = "proto3";`
2. `java_multiple_files = true;`
3. Standard Java package namespace: `com.swiftdrop.<service>.v1`

| Service | Protobuf Package | Java Package | Contract File | Primary Responsibilities |
|---|---|---|---|---|
| **Accounts & Partners** | `swiftdrop.accounts.v1` | `com.swiftdrop.accounts.v1` | `contracts/grpc/swiftdrop/accounts/v1/accounts.proto` | Courier eligibility verification, merchant key validation, customer contact resolution, admin pending-merchant summary |
| **Pricing & Zones** | `swiftdrop.pricing.v1` | `com.swiftdrop.pricing.v1` | `contracts/grpc/swiftdrop/pricing/v1/pricing.proto` | Quote validation and pricing snapshot verification during parcel booking |
| **Delivery Operations** | `swiftdrop.delivery.v1` | `com.swiftdrop.delivery.v1` | `contracts/grpc/swiftdrop/delivery/v1/delivery.proto` | Internal parcel detail queries, active workload counts, assignment lookups for GraphQL resolvers |

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
   - Field tags once assigned are **permanent**. Never renumber fields. When deprecating or removing a field, mark its tag and name as `reserved`.

---

## 4. Service Definitions & RPC Specifications

### 4.1 Accounts Service (`com.swiftdrop.accounts.v1.AccountsInternalService`)

Used by Delivery Operations, Communications Service, and the GraphQL Query API.

```protobuf
syntax = "proto3";

package swiftdrop.accounts.v1;

import "google/protobuf/timestamp.proto";

option java_multiple_files = true;
option java_package = "com.swiftdrop.accounts.v1";
option java_outer_classname = "AccountsProto";

service AccountsInternalService {
  // Verifies if a courier is active and eligible for new parcel assignments (IF-GRPC-01)
  rpc CheckCourierEligibility (CourierEligibilityRequest) returns (CourierEligibilityResponse);

  // Validates a merchant API key digest during automated order booking (IF-GRPC-02)
  rpc ValidateMerchantKeyDigest (MerchantKeyDigestRequest) returns (MerchantKeyDigestResponse);

  // Resolves recipient notification contact handles (email/phone) without exposing PII in events (IF-GRPC-03)
  rpc ResolveRecipientContact (RecipientContactRequest) returns (RecipientContactResponse);

  // Queries bounded admin pending-merchant review summary for admin dashboard (IF-GRPC-06, Refs #121/#139)
  rpc GetPendingMerchantsSummary (PendingMerchantsSummaryRequest) returns (PendingMerchantsSummaryResponse);

  // Checks account, merchant, or staff eligibility and approval lifecycle state (IF-GRPC-07, Refs #109)
  rpc CheckAccountEligibility (AccountEligibilityRequest) returns (AccountEligibilityResponse);

  // Queries scoped profile summary with server-side minimal field filtering (IF-GRPC-08, Refs #109)
  rpc GetScopedProfileSummary (ScopedProfileSummaryRequest) returns (ScopedProfileSummaryResponse);

  // Batch query for scoped profile summaries bounded to at most 50 subjects (IF-GRPC-09, Refs #109)
  rpc GetBatchProfileSummaries (BatchProfileSummariesRequest) returns (BatchProfileSummariesResponse);

  // Queries bounded available active couriers for dispatcher queue (IF-GRPC-10, Refs #109, #135)
  rpc GetAvailableCouriersSummary (AvailableCouriersSummaryRequest) returns (AvailableCouriersSummaryResponse);
}

message CourierEligibilityRequest {
  string courier_sub = 1; // Keycloak subject string (UUID format)
}

message CourierEligibilityResponse {
  string courier_sub = 1;
  bool is_eligible = 2;
  bool is_active = 3;
  string full_name = 4;
  string rejection_reason = 5; // e.g. "COURIER_SUSPENDED", "COURIER_NOT_FOUND", "ELIGIBLE"
}

message MerchantKeyDigestRequest {
  string key_prefix = 1; // First 12 chars of the key, e.g. "swd_live_abc"
  string key_digest = 2; // SHA-256 hex digest of the API key
}

message MerchantKeyDigestResponse {
  bool is_valid = 1;
  string merchant_id = 2; // UUID string
  string business_name = 3;
  string status = 4; // "APPROVED", "PENDING", "SUSPENDED", "REJECTED"
  repeated string scopes = 5; // e.g. ["parcels:create", "parcels:read"]
}

message RecipientContactRequest {
  string recipient_sub = 1; // Keycloak subject string
  string purpose = 2; // Approved notification purpose (e.g. "PARCEL_STATUS_UPDATE", "DELIVERY_NOTIFICATION", "TRANSACTIONAL", "ACCOUNT_ALERT", "NOTIFICATION")
  string channel = 3; // Permitted notification channel ("EMAIL", "SMS", "SMS_MOCK", "ANY")
}

message RecipientContactResponse {
  string recipient_sub = 1;
  string email = 2; // Excluded if channel is SMS
  string phone_number = 3; // Excluded if channel is EMAIL
  string full_name = 4;
  bool is_eligible = 5; // Policy eligibility flag for dispatching notification
  bool email_permitted = 6; // Whether email notifications are permitted for recipient
  bool sms_permitted = 7; // Whether SMS notifications are permitted for recipient
  string status = 8; // "ACTIVE", "INACTIVE", "SUSPENDED", "PENDING", "MISSING_CONTACT", "NOT_FOUND"
  string rejection_reason = 9; // e.g. "ELIGIBLE", "MISSING_EMAIL_CONTACT", "STAFF_SUSPENDED", etc.
}

message PendingMerchantsSummaryRequest {
  string status_filter = 1; // Defaults to "PENDING" if empty
  int32 page = 2; // 0-based page index (>= 0)
  int32 page_size = 3; // Items per page (bounded: 1 to 50, default 20)
}

message PendingMerchantItem {
  string merchant_id = 1; // UUID string
  string business_name = 2;
  string registration_number = 3;
  google.protobuf.Timestamp submitted_at = 4; // UTC timestamp
  string status = 5; // "PENDING", "APPROVED", "SUSPENDED", "REJECTED"
}

message PendingMerchantsSummaryResponse {
  int32 total_pending_count = 1;
  int32 page = 2;
  int32 page_size = 3;
  repeated PendingMerchantItem items = 4;
}

message AccountEligibilityRequest {
  string subject = 1; // Keycloak subject string (UUID format)
  string required_role = 2; // e.g. "CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "ADMIN" (optional)
  string merchant_id = 3; // Optional merchant tenant UUID string
}

message AccountEligibilityResponse {
  string subject = 1;
  bool is_eligible = 2;
  bool is_active = 3;
  string status = 4; // "APPROVED", "ACTIVE", "PENDING", "SUSPENDED", "INACTIVE", "NOT_FOUND"
  string rejection_reason = 5; // e.g. "ELIGIBLE", "ACCOUNT_NOT_FOUND", "MERCHANT_PENDING", "STAFF_INACTIVE", etc.
  string merchant_id = 6; // UUID string if merchant
  string business_name = 7; // Business name if merchant
}

message ScopedProfileSummaryRequest {
  string subject = 1; // Keycloak subject string
  string scope = 2; // Scope constraint: "MINIMAL", "CUSTOMER", "MERCHANT", "STAFF", "COURIER"
}

message ScopedProfileSummaryResponse {
  string subject = 1;
  string full_name = 2;
  string email = 3; // Permitted only under authorized scopes, strictly omitted/empty otherwise
  string role = 4;
  string status = 5;
  string merchant_id = 6; // UUID string (if merchant)
  string business_name = 7; // (if merchant)
  string staff_id = 8; // (if staff)
  string department = 9; // (if staff)
  string assigned_zone = 10; // (if courier/staff)
}

message BatchProfileSummariesRequest {
  repeated string subjects = 1; // Bounded list of subjects (max 50)
  string scope = 2;
}

message BatchProfileSummariesResponse {
  repeated ScopedProfileSummaryResponse items = 1;
}

message AvailableCouriersSummaryRequest {
  string zone_filter = 1; // Optional zone filter
  int32 page = 2; // 0-based page index (>= 0)
  int32 page_size = 3; // Bounded: 1 to 50, default 20
}

message CourierSummaryItem {
  string courier_sub = 1;
  string full_name = 2;
  string staff_id = 3;
  string assigned_zone = 4;
  bool is_eligible = 5;
  bool is_active = 6;
}

message AvailableCouriersSummaryResponse {
  int32 total_count = 1;
  int32 page = 2;
  int32 page_size = 3;
  repeated CourierSummaryItem items = 4;
}
```

### 4.2 Pricing Service (`com.swiftdrop.pricing.v1.PricingInternalService`)

Used by Delivery Operations during parcel booking (IF-GRPC-04) and by administrative read clients (Refs #114).

```protobuf
syntax = "proto3";

package swiftdrop.pricing.v1;

import "google/protobuf/timestamp.proto";

option java_multiple_files = true;
option java_package = "com.swiftdrop.pricing.v1";

service PricingInternalService {
  // Validates that a quote exists, is not expired, and fee matches
  rpc ValidateQuote (ValidateQuoteRequest) returns (ValidateQuoteResponse);

  // Queries bounded active zones and rate version summaries for admin views (Refs #114)
  rpc GetPricingAdminSummary (PricingAdminSummaryRequest) returns (PricingAdminSummaryResponse);
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
  string validation_code = 7; // "VALID", "QUOTE_EXPIRED", "RATE_SUPERSEDED", "AMOUNT_MISMATCH", "QUOTE_NOT_FOUND"

  // Additive snapshot fields for booking authorization & route binding (Refs #114, backward-compatible)
  string pickup_zone = 8;
  string dropoff_zone = 9;
  string subject_sub = 10;
  string tenant_id = 11;
  bool is_anonymous = 12;
  int64 base_fee_minor_units = 13;
  int64 weight_surcharge_minor_units = 14;
}

message PricingAdminSummaryRequest {
  bool active_only = 1;
  int32 page = 2;
  int32 page_size = 3;
}

message PricingZoneSummaryItem {
  string zone_id = 1;
  string zone_code = 2;
  string name = 3;
  string description = 4;
  bool is_active = 5;
}

message RateVersionSummaryItem {
  string rate_version_id = 1;
  string version_code = 2;
  string currency = 3;
  string status = 4;
  google.protobuf.Timestamp created_at = 5;
  int32 rule_count = 6;
}

message PricingAdminSummaryResponse {
  repeated PricingZoneSummaryItem zones = 1;
  repeated RateVersionSummaryItem rate_versions = 2;
  int32 total_zones = 3;
  int32 total_rate_versions = 4;
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

  // Authorizes whether a requested delivery event is associated with an eligible recipient (IF-GRPC-11, Refs #111)
  rpc AuthorizeEventRecipient (AuthorizeEventRecipientRequest) returns (AuthorizeEventRecipientResponse);
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

message AuthorizeEventRecipientRequest {
  string event_id = 1;      // Authoritative outbox event UUID
  string parcel_id = 2;     // Associated parcel UUID
  string recipient_sub = 3; // Recipient identity subject
  string merchant_id = 4;    // Optional merchant tenant UUID for strict isolation
  bool allow_stale = 5;     // Whether notification on superseded state versions is permitted
}

message AuthorizeEventRecipientResponse {
  bool is_authorized = 1;           // Primary policy authorization flag
  string parcel_id = 2;            // Authoritative parcel UUID
  string event_id = 3;             // Authoritative event UUID
  string recipient_sub = 4;        // Authorized recipient subject
  string merchant_id = 5;          // Merchant tenant UUID if applicable
  string tracking_number = 6;      // Parcel tracking number for human-readable notification text
  string current_status = 7;       // Current parcel status ("CREATED", "IN_TRANSIT", "DELIVERED", etc.)
  string event_type = 8;           // Emitted event type (e.g. "parcel.status.changed.v1")
  int64 event_version = 9;         // State version when event occurred
  int64 current_version = 10;      // Current state version of parcel
  bool is_terminal = 11;           // Whether current parcel status is terminal (DELIVERED/FAILED)
  bool is_stale = 12;              // True if current_version > event_version
  string authorization_reason = 13;// "AUTHORIZED", "AUTHORIZED_STALE", "AUTHORIZED_TERMINAL", "EVENT_NOT_FOUND", "PARCEL_NOT_FOUND", "EVENT_PARCEL_MISMATCH", "MERCHANT_TENANT_MISMATCH", "RECIPIENT_NOT_ASSOCIATED", "STALE_EVENT", "STALE_TERMINAL_EVENT"
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

- [x] Place `.proto` files in `contracts/grpc/swiftdrop/accounts/v1/`, `pricing/v1/`, `delivery/v1/`.
- [x] Configure `protobuf-maven-plugin` with `protoc` 3.25+ and `protoc-gen-grpc-java` 1.68+.
- [x] Create common Java proto module (`shared/proto-contracts`) exportable to consuming services.
- [x] Verify clean compilation with Java 21 LTS (`mvn compile`).
- [x] Define and generate Accounts admin pending-merchant summary RPC (`GetPendingMerchantsSummary`) for #121/#139.
- [x] Implement comprehensive contract tests (Accounts, Pricing, Delivery, Security Metadata, Deadlines).
- [x] Verify bit-for-bit reproducible code generation across independent clean runs.

---

## 7. Build, Generation & Reproducibility Instructions

### 7.1 Prerequisites
- **JDK:** Java 21 LTS (`temurin-21` or Oracle JDK 21).
- **Maven:** Maven 3.9+ (or use the repository's checked-in wrapper `./mvnw` / `mvnw.cmd`).
- **Operating System:** Linux, macOS, or Windows (automatically detected by `os-maven-plugin`).

### 7.2 Generation & Compilation Commands

To generate Java classes and compile the contracts:
```bash
cd shared/proto-contracts
./mvnw clean compile
```

To run all unit and contract verification tests:
```bash
cd shared/proto-contracts
./mvnw test
```

To install the artifact into your local Maven cache (`~/.m2/repository`):
```bash
cd shared/proto-contracts
./mvnw clean install
```

### 7.3 Verifying Clean & Reproducible Generation

Generation output is placed under `shared/proto-contracts/target/generated-sources/protobuf/`:
- `target/generated-sources/protobuf/java/`: Protobuf message builders and descriptors.
- `target/generated-sources/protobuf/grpc-java/`: gRPC service stubs and interfaces.

To verify that generation is 100% deterministic and byte-for-byte reproducible across clean runs:
```powershell
cd shared\proto-contracts
.\mvnw.cmd clean compile
Copy-Item -Recurse target\generated-sources\protobuf "$env:TEMP\gen-run1"

.\mvnw.cmd clean compile
Copy-Item -Recurse target\generated-sources\protobuf "$env:TEMP\gen-run2"

# Compare SHA-256 hashes across runs
Get-ChildItem -Recurse "$env:TEMP\gen-run1" -File | ForEach-Object {
    $rel = $_.FullName.Substring("$env:TEMP\gen-run1".Length)
    $h1 = (Get-FileHash $_.FullName).Hash
    $h2 = (Get-FileHash "$env:TEMP\gen-run2$rel").Hash
    if ($h1 -ne $h2) { Write-Error "Mismatch in $rel" }
}
```

### 7.4 Generated Code Source Control Policy
- Generated code is **strictly excluded** from git (`**/target/` in `.gitignore`).
- Only canonical `.proto` source files in `contracts/grpc/swiftdrop/` are committed.
- Build pipelines regenerate all classes deterministically from source contracts.

---

## 8. Contract Evolution & Backward Compatibility Rules

When modifying Protocol Buffer definitions in future tasks:

1. **Tag Numbers are Permanent:**
   - Once assigned, a field number can **never** be renumbered or reassigned to a different field.
2. **Field Deprecation / Removal:**
   - When deprecating or removing a field, mark its number and name as `reserved`:
     ```protobuf
     reserved 4, 15;
     reserved "old_field_name";
     ```
3. **Monetary Quantities:**
   - Always encode monetary values as `int64` minor units (e.g. `amount_minor_units = 150000` for 1,500.00 LKR).
   - Never use `float` or `double` for currency.
4. **Timestamps:**
   - Use `google.protobuf.Timestamp` for all UTC timestamps.
5. **Identifiers:**
   - Use `string` RFC 4122 UUID format.

---

## 9. CI Contract Verification Gate

Contracts are automatically validated on every PR and push targeting `test` or `main`:

```bash
# Validate syntax, options, field numbering, minor units, and git compatibility
python3 tests/contracts/validate_contracts.py --mode=protobuf --check-compatibility --base-ref=origin/test

# Run Python contract tests
python3 -m unittest tests/contracts/test_protobuf_contracts.py -v
```
