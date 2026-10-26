# Delivery Event-to-Recipient Authorization Read Handoff — Issue #111 (N10-B07)

**Issue Title:** [N10-B07] Expose Delivery event-to-recipient authorization read for Communications  
**Issue Link:** [#111](https://github.com/Suthankan1/swiftdrop/issues/111)  
**Producer Module:** Delivery Operations (`services/delivery-service`)  
**Consumer Module:** Communications (`services/communications-service`)  
**Assigned Owner:** Sinthuha Nadesan (@Sinthuha-n)  
**Target Branch:** `test`  
**Feature Branch:** `feat/n10-b07-delivery-recipient-authorization-read`  
**Date:** October 26, 2026  
**Architecture Baseline:** SwiftDrop v1.3 (`docs/architecture/BASELINE.md`)  

---

## 1. Executive Summary & Purpose

In the SwiftDrop microservices architecture, domain events emitted across the asynchronous event log (Kafka KRaft `parcel.lifecycle.v1`) strictly omit personally identifiable information (PII) such as customer names, phone numbers, and street addresses. When the **Communications Service** processes an incoming event to trigger customer or courier notification jobs (via email or SMS), it must not simply trust caller-supplied claims or assume any user subject is authorized to receive notification for a given parcel.

Furthermore, Communications must never directly query `delivery_db` or bypass service boundaries.

Issue #111 implements a **secure, bounded, read-only Delivery-owned authorization interface** (`IF-GRPC-11`, `DeliveryInternalService.AuthorizeEventRecipient`):
1. **Delivery Operations (Provider):** Validates the machine caller identity (`COMMUNICATIONS_SERVICE`), enforces zero human delegation (`DelegationRequirement.FORBIDDEN`), verifies event-to-parcel provenance against authoritative outbox records, evaluates tenant boundaries and legitimate recipient association (primary customer or assigned courier), and enforces state staleness and terminal lifecycle rules.
2. **Communications Service (Consumer Client Adapter):** Implements a resilient gRPC client adapter (`DeliveryRecipientAuthorizationClient` / `GrpcDeliveryRecipientAuthorizationClient`) with deadline enforcement (2000ms), typed domain mapping (`DeliveryEventAuthorization`), retryable failure translation (`DeliveryServiceUnavailableException`), and fail-closed security rejection (`DeliverySecurityException`).

---

## 2. Architecture & Data Ownership Boundaries

### Data Ownership
- **Delivery Operations OWNS:**
  - `delivery_db.parcels` (canonical parcel aggregate, status, version, customer `sender_sub`, merchant `merchant_id`)
  - `delivery_db.outbox_events` (immutable transactional outbox records with `event_id`, `aggregate_id`, `aggregate_version`, `event_type`)
  - `delivery_db.delivery_assignments` (active courier-to-parcel assignment records with `courier_sub`)
  - `delivery_db.status_history` (append-only lifecycle audit trail)
- **Communications OWNS:**
  - `communications_db.consumed_events` (deduplication inbox receipts)
  - `communications_db.notifications` (scheduled and executed notification jobs)
  - `communications_db.webhook_subscriptions` and `webhook_deliveries`
  - Evaluating delivery event authorization strictly via `IF-GRPC-11` before dispatching notifications.
- **Zero Cross-Service SQL:**
  - Communications **never** connects to or queries `delivery_db`.
  - No shared business entity JARs are introduced. All communication is strictly mediated through compiled Protocol Buffers v3 contracts (`shared/proto-contracts`).

### Security Boundaries
- **Caller Service Identity:**
  - The endpoint is secured via `SwiftDropGrpcServerSecurityInterceptor` and `DeliveryInternalServiceImpl`.
  - Machine caller must authenticate as `COMMUNICATIONS_SERVICE` using the internal service JWT (`x-swiftdrop-service-token`).
  - Calls originating from unauthorized services or unauthenticated callers are rejected with `PERMISSION_DENIED` or `UNAUTHENTICATED`.
- **Zero Human Actor Delegation:**
  - Background notification dispatch operates without an interactive user session.
  - In accordance with `GrpcSecurityPolicy`, `AuthorizeEventRecipient` enforces `DelegationRequirement.FORBIDDEN`.
  - Any request carrying human actor delegation headers (`x-swiftdrop-actor-token`, `x-swiftdrop-actor-sub`) is rejected with `PERMISSION_DENIED` to prevent user impersonation or spoofing on background workers.
- **Strict Privacy & Logging Rules:**
  - Authorization responses contain no unnecessary addresses, phone numbers, emails, personal details, or raw payloads.
  - Delivery logs contain only opaque event UUIDs, parcel UUIDs, recipient subjects, and authorization reasons.

---

## 3. Protocol Buffers Contract Specification (`IF-GRPC-11`)

The contract in `contracts/grpc/swiftdrop/delivery/v1/delivery.proto` was extended:

```protobuf
service DeliveryInternalService {
  // Authorizes whether a requested delivery event is associated with an eligible recipient (IF-GRPC-11, Refs #111)
  rpc AuthorizeEventRecipient (AuthorizeEventRecipientRequest) returns (AuthorizeEventRecipientResponse);
}

message AuthorizeEventRecipientRequest {
  string event_id = 1;      // Authoritative outbox event UUID
  string parcel_id = 2;     // Associated parcel UUID
  string recipient_sub = 3; // Recipient identity subject (Keycloak sub)
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

### Backward Compatibility
- Adding the new RPC `AuthorizeEventRecipient` and messages `AuthorizeEventRecipientRequest` / `AuthorizeEventRecipientResponse` to `swiftdrop.delivery.v1.DeliveryInternalService` maintains 100% backward compatibility under Protocol Buffers v3 rules.
- Existing RPCs (`GetParcelInternal`, `GetCourierWorkload`) remain completely unaffected.

---

## 4. Delivery Operations Implementation Details

File: `services/delivery-service/src/main/java/com/swiftdrop/deliveryservice/grpc/DeliveryInternalServiceImpl.java`

1. **Security Policy Enforcement:**
   - Enforces that caller is authenticated as `SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE`.
   - Enforces that no human delegation context is attached (`context.actorContext().isPresent()` -> fails closed with `PERMISSION_DENIED`).
2. **Input Validation:**
   - Validates that `event_id` and `parcel_id` are non-blank, properly formatted UUID strings.
   - Rejects invalid UUID syntax with `Status.INVALID_ARGUMENT`.
3. **Authoritative Event & Provenance Verification:**
   - Resolves `outbox_events` by `event_id`. If absent, returns `is_authorized = false`, `reason = EVENT_NOT_FOUND`.
   - Verifies event aggregate pairing: `event.getAggregateId()` must match `request.parcelId`. If mismatched, logs a security warning and returns `is_authorized = false`, `reason = EVENT_PARCEL_MISMATCH` (anti-forgery defense).
4. **Authoritative Parcel & Tenant Verification:**
   - Resolves `parcels` by `parcel_id`. If absent, returns `is_authorized = false`, `reason = PARCEL_NOT_FOUND`.
   - If `merchant_id` is supplied in the request, enforces that `parcel.getMerchantId()` matches. If mismatched, returns `is_authorized = false`, `reason = MERCHANT_TENANT_MISMATCH`.
5. **Recipient Relationship Verification:**
   - Checks if `recipient_sub` matches the customer who created/owns the parcel (`parcel.getSenderSub()`).
   - Checks if `recipient_sub` matches the currently active courier assigned to the parcel via `delivery_assignments` (`deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)`).
   - If `recipient_sub` matches neither customer nor active courier, returns `is_authorized = false`, `reason = RECIPIENT_NOT_ASSOCIATED`.
6. **State Staleness & Terminal Lifecycle Rules:**
   - Computes `isStale = currentVersion > eventVersion`.
   - Evaluates terminal status: `isTerminal = currentStatus IN (DELIVERED, FAILED)`.
   - If event is stale (`currentVersion > eventVersion`):
     - If the parcel has reached a terminal status and the stale event was non-terminal: rejects with `is_authorized = false`, `reason = STALE_TERMINAL_EVENT` to prevent sending outdated in-transit alerts after delivery is finalized.
     - If non-terminal and `allow_stale = false`: rejects with `is_authorized = false`, `reason = STALE_EVENT`.
     - If `allow_stale = true`: authorizes with `reason = AUTHORIZED_STALE`.
   - For fresh events: authorizes with `is_authorized = true`, `reason = AUTHORIZED` (or `AUTHORIZED_TERMINAL`).

---

## 5. Communications Service Client Adapter

Package: `com.swiftdrop.communicationsservice.client`

1. **Domain Record (`DeliveryEventAuthorization`):**
   ```java
   public record DeliveryEventAuthorization(
           boolean isAuthorized,
           UUID parcelId,
           UUID eventId,
           String recipientSub,
           UUID merchantId,
           String trackingNumber,
           String currentStatus,
           String eventType,
           long eventVersion,
           long currentVersion,
           boolean isTerminal,
           boolean isStale,
           String authorizationReason
   ) {}
   ```
2. **Client Interface (`DeliveryRecipientAuthorizationClient`):**
   ```java
   public interface DeliveryRecipientAuthorizationClient {
       DeliveryEventAuthorization authorizeEventRecipient(
               UUID eventId,
               UUID parcelId,
               String recipientSub,
               UUID merchantId,
               boolean allowStale
       );
   }
   ```
3. **Implementation (`GrpcDeliveryRecipientAuthorizationClient`):**
   - Applies bounded deadline (default 2000ms).
   - Attaches machine caller authentication token (`x-swiftdrop-service-token`).
   - Maps `StatusRuntimeException`:
     - `UNAVAILABLE`, `DEADLINE_EXCEEDED` -> `DeliveryServiceUnavailableException` (marked retryable for background job schedulers).
     - `PERMISSION_DENIED`, `UNAUTHENTICATED` -> `DeliverySecurityException` (fatal, fail-closed).
     - `INVALID_ARGUMENT` -> `IllegalArgumentException`.
     - Other unexpected gRPC failures -> `DeliveryAuthorizationException`.

---

## 6. Test & Verification Evidence

All automated test suites were executed and verified against Java 21 LTS:

### A. Delivery Operations Tests (`services/delivery-service`)
- **Suite:** `DeliveryInternalServiceGrpcTest`
- **Total Tests:** 20 | **Failures:** 0 | **Errors:** 0 | **Skipped:** 0
- **Coverage Highlights:**
  - `testAuthorizeEventRecipient_PrimaryCustomer_Success`
  - `testAuthorizeEventRecipient_ActiveCourier_Success`
  - `testAuthorizeEventRecipient_UnrelatedRecipient_Rejected`
  - `testAuthorizeEventRecipient_ForgedEventParcelMismatch_FailClosed`
  - `testAuthorizeEventRecipient_EventNotFound_FailClosed`
  - `testAuthorizeEventRecipient_ParcelNotFound_FailClosed`
  - `testAuthorizeEventRecipient_MerchantTenantMismatch_FailClosed`
  - `testAuthorizeEventRecipient_StaleEvent_RejectedWhenStaleDisallowed`
  - `testAuthorizeEventRecipient_StaleEvent_AuthorizedWhenStaleAllowed`
  - `testAuthorizeEventRecipient_StaleEventOnTerminalParcel_StrictlyRejected`
  - `testAuthorizeEventRecipient_TerminalEvent_Authorized`
  - `testAuthorizeEventRecipient_UnauthenticatedCaller_Rejects`
  - `testAuthorizeEventRecipient_UnauthorizedServiceCaller_Rejects`
  - `testAuthorizeEventRecipient_ActorDelegationSpoofing_Rejects`
  - `testAuthorizeEventRecipient_InvalidUuid_Rejects`
  - `testGetParcelInternal_Success`
  - `testGetCourierWorkload_Success`
- **Module Regression:** 164 total tests passing across all features (`BookingFoundationTest`, `BookingExposureTest`, `KafkaOutboxRelayTest`, `DeliveryConfigurationNegativeTest`, `DeliveryFlywayMigrationTest`, `DeliveryEntityTest`).

### B. Communications Service Tests (`services/communications-service`)
- **Suite:** `DeliveryRecipientAuthorizationClientTest`
- **Total Tests:** 10 | **Failures:** 0 | **Errors:** 0 | **Skipped:** 0
- **Coverage Highlights:**
  - `testAuthorizeEventRecipient_Success`
  - `testAuthorizeEventRecipient_AuthorizedStale`
  - `testAuthorizeEventRecipient_Unauthorized`
  - `testAuthorizeEventRecipient_EventNotFound_FailClosed`
  - `testAuthorizeEventRecipient_ServiceUnavailable`
  - `testAuthorizeEventRecipient_DeadlineExceeded`
  - `testAuthorizeEventRecipient_PermissionDenied`
  - `testAuthorizeEventRecipient_Unauthenticated`
  - `testAuthorizeEventRecipient_InvalidArgument`
  - `testAuthorizeEventRecipient_RequiredArgsValidation`
- **Module Regression:** 37 total tests passing across all features (`AccountsRecipientContactClientTest`, `DurableJobPersistenceServiceTest`, `CommunicationsPersistencePostgresIT`, `CommunicationsConfigurationNegativeTest`, `CommunicationsEntityTest`, `CommunicationsDataOwnershipTest`).

### C. Shared Libraries Tests
- `shared/proto-contracts`: 20/20 tests passing (`AccountsContractTest`, `DeliveryContractTest`, `PricingContractTest`, `ContractDescriptorAndSerializationTest`, `SecurityMetadataAndDeadlineTest`).
- `shared/security-adapter`: 39/39 tests passing (`JwtValidationTest`, `GrpcDeadlineAndCorrelationTest`, `GrpcServiceAuthenticationTest`, `GrpcDelegationSpoofingTest`, `DirectServiceSecurityTest`).

### D. Contract Compatibility & Linter Gates
- `python3 tests/contracts/validate_contracts.py --check-compatibility --base-ref=origin/test`:
  - Gate 1 (OpenAPI): PASSED
  - Gate 2 (GraphQL): PASSED
  - Gate 3 (Kafka Schemas): PASSED
  - Gate 4 (Protobuf Contracts): PASSED (12 RPCs validated, zero breaking changes detected).

---

## 7. Next Steps for Dependent Issues

1. **Issue #32 (Communications Kafka Consumer & Notification Dispatch):**
   - Consume `parcel.status.changed.v1` events from `parcel.lifecycle.v1`.
   - Call `DeliveryRecipientAuthorizationClient.authorizeEventRecipient(...)` to verify recipient eligibility and state staleness.
   - If authorized, call `AccountsRecipientContactClient.resolveContact(...)` to fetch the authorized contact handle (`EMAIL` or `SMS`).
   - Schedule and execute the notification job.
