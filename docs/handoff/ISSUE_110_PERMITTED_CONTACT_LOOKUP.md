# Permitted Notification Contact Lookup Handoff — Issue #110 (N10-B06)

**Issue Title:** [N10-B06] Expose permitted notification contact lookup from Accounts  
**Issue Link:** [#110](https://github.com/Suthankan1/swiftdrop/issues/110)  
**Producer Module:** Accounts & Partners (`services/accounts-service`)  
**Consumer Module:** Communications (`services/communications-service`)  
**Author / Assigned Owner:** @Gajanthan23 (Gajanthan)  
**Target Branch:** `test`  
**Feature Branch:** `feat/communications-permitted-contact-lookup`  
**Date:** October 25, 2026  
**Architecture Baseline:** SwiftDrop v1.3 (`docs/architecture/BASELINE.md`)  

---

## 1. Executive Summary & Purpose

In the SwiftDrop architecture, domain events emitted across the asynchronous event log (Kafka KRaft `parcel.lifecycle.v1`) strictly omit personally identifiable information (PII) such as recipient email addresses or phone numbers. When the **Communications Service** processes a notification job (for email or SMS delivery), it must securely resolve the permitted contact handles using the opaque `recipient_sub` identity handle.

Issue #110 implements this capability as a secure, synchronous, internal gRPC contract (`IF-GRPC-03`, `AccountsInternalService.ResolveRecipientContact`):
1. **Accounts & Partners:** Implements the authoritative server-side endpoint with channel-scoped projection (`EMAIL` vs. `SMS`), notification purpose validation, fail-closed account/staff/merchant lifecycle eligibility verification, and zero PII logging.
2. **Communications Service:** Implements the type-safe client adapter (`AccountsRecipientContactClient` / `GrpcAccountsRecipientContactClient`) with deadline enforcement, domain mapping (`PermittedRecipientContact`), safe error translation, and zero cross-service SQL or Keycloak queries.

---

## 2. Architecture & Data Ownership Boundaries

### Data Ownership
- **Accounts & Partners OWNS:**
  - `accounts_db.account_profiles` (customer contact data)
  - `accounts_db.staff_profiles` (courier and dispatcher records)
  - `accounts_db.merchants` (merchant tenant and business profile records)
  - Permitted recipient contact handle resolution and eligibility policy.
- **Communications OWNS:**
  - `communications_db.notifications` (notification jobs and execution tracking)
  - Notification scheduling, channel delivery dispatching, and retry loops.
  - Resolving recipient contact data strictly via gRPC `ResolveRecipientContact`.
- **Zero Cross-Service SQL:**
  - Communications **never** connects to or executes queries against `accounts_db` or Keycloak.
  - No shared business entity JARs are introduced; communication is strictly through compiled Protocol Buffers v3 contracts (`shared/proto-contracts`).

### Security Boundaries
- **Caller Service Identity:**
  - The endpoint is secured via `SwiftDropGrpcServerSecurityInterceptor` and `AccountsInternalServiceImpl`.
  - Machine caller must authenticate as `COMMUNICATIONS_SERVICE` using the internal service JWT (`x-swiftdrop-service-token`).
  - Calls originating from other service identities (e.g. `DELIVERY_SERVICE`, `GATEWAY`) are rejected with `PERMISSION_DENIED`.
  - Unauthenticated calls are rejected with `UNAUTHENTICATED`.
- **Zero Actor Delegation:**
  - Background notification processing operates without an interactive user session.
  - In accordance with `GrpcSecurityPolicy`, `ResolveRecipientContact` enforces `DelegationRequirement.FORBIDDEN`.
  - Any request carrying human actor delegation headers (`x-swiftdrop-actor-token`, `x-swiftdrop-actor-sub`) is rejected with `PERMISSION_DENIED` to prevent spoofing.
- **Strict Privacy & Logging Rules:**
  - Recipient email and phone numbers are never logged in application logs or emitted into Kafka events.
  - Only opaque subject identifiers, notification purpose, and eligibility statuses are logged.

---

## 3. Protocol Buffers Contract Specification (`IF-GRPC-03`)

The contract in `contracts/grpc/swiftdrop/accounts/v1/accounts.proto` was compatibly extended:

```protobuf
message RecipientContactRequest {
  string recipient_sub = 1; // Keycloak subject string (UUID or provider sub)
  string purpose = 2;       // Approved notification purpose (e.g. "PARCEL_STATUS_UPDATE", "TRANSACTIONAL")
  string channel = 3;       // Permitted notification channel ("EMAIL", "SMS", "SMS_MOCK", "ANY")
}

message RecipientContactResponse {
  string recipient_sub = 1;
  string email = 2;           // Populated for EMAIL or ANY; strictly omitted for SMS
  string phone_number = 3;    // Populated for SMS, SMS_MOCK, or ANY; strictly omitted for EMAIL
  string full_name = 4;
  bool is_eligible = 5;       // Policy eligibility flag for dispatching notification
  bool email_permitted = 6;   // Whether email notifications are permitted for recipient
  bool sms_permitted = 7;     // Whether SMS notifications are permitted for recipient
  string status = 8;          // "ACTIVE", "INACTIVE", "SUSPENDED", "PENDING", "MISSING_CONTACT", "NOT_FOUND"
  string rejection_reason = 9;// e.g. "ELIGIBLE", "MISSING_EMAIL_CONTACT", "STAFF_SUSPENDED"
}
```

### Backward Compatibility
- Adding field numbers 2-3 to `RecipientContactRequest` and 5-9 to `RecipientContactResponse` maintains 100% backward compatibility under Protocol Buffers v3 rules.
- Existing callers omitting `purpose` or `channel` default to `"PARCEL_STATUS_UPDATE"` and `"ANY"` channel projection.

---

## 4. Accounts Implementation Details

File: `services/accounts-service/src/main/java/com/swiftdrop/accountsservice/grpc/AccountsInternalServiceImpl.java`

1. **Security & Context Validation:**
   - Validates that caller identity matches `SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE`.
   - Validates that no actor delegation context is attached (`context.actorContext().isPresent()` -> fails with `PERMISSION_DENIED`).
2. **Input Validation:**
   - Validates non-blank `recipient_sub`, bounds check (length <= 255 chars), and control-character sanitization.
   - Validates `purpose` against approved purposes: `PARCEL_STATUS_UPDATE`, `DELIVERY_NOTIFICATION`, `TRANSACTIONAL`, `ACCOUNT_ALERT`, `NOTIFICATION`.
   - Validates `channel` against approved channels: `EMAIL`, `SMS`, `SMS_MOCK`, `ANY`.
3. **Recipient Resolution & Lifecycle Checks:**
   - Evaluates `account_profiles` first. If active, populates email and phone number.
   - Evaluates `staff_profiles` if account profile is absent or inactive. Suspended staff (`status != "ACTIVE"`) fail closed with `is_eligible = false` and `rejection_reason = "STAFF_SUSPENDED"` or `"STAFF_INACTIVE"`.
   - Evaluates `merchants` by contact sub. Suspended or pending merchants fail closed with `is_eligible = false` and `rejection_reason = "MERCHANT_SUSPENDED"` or `"MERCHANT_PENDING"`.
   - If not found in any repository, returns gRPC `Status.NOT_FOUND`.
4. **Channel Scoping:**
   - If requested channel is `EMAIL`, `phone_number` is cleared (`""`).
   - If requested channel is `SMS` or `SMS_MOCK`, `email` is cleared (`""`).
   - If the requested channel contact handle is missing or empty on an otherwise active account, sets `is_eligible = false`, `status = "MISSING_CONTACT"`, and `rejection_reason = "MISSING_EMAIL_CONTACT"` or `"MISSING_PHONE_CONTACT"`.

---

## 5. Communications Client Adapter Details

Package: `com.swiftdrop.communicationsservice.client` in `services/communications-service`

1. **`PermittedRecipientContact`:**
   Domain record carrying channel-scoped contact handles, eligibility flags (`isEligible`, `emailPermitted`, `smsPermitted`), and lifecycle statuses.
2. **`AccountsRecipientContactClient`:**
   Type-safe interface providing:
   ```java
   PermittedRecipientContact resolveRecipientContact(String recipientSub, String purpose, String channel);
   PermittedRecipientContact resolveRecipientContact(String recipientSub);
   ```
3. **`GrpcAccountsRecipientContactClient`:**
   Implementation that sets up deadline enforcement (configurable via `grpc.client.accounts.deadline-ms`, default 2000ms), translates gRPC status codes into structured domain exceptions:
   - `NOT_FOUND` -> returns empty / not eligible or throws `AccountsContactException`
   - `UNAVAILABLE` / `DEADLINE_EXCEEDED` -> throws retryable `AccountsServiceUnavailableException`
   - `PERMISSION_DENIED` / `UNAUTHENTICATED` -> throws non-retryable `AccountsSecurityException`
   - `INVALID_ARGUMENT` -> throws `IllegalArgumentException`
4. **Configuration Properties (`application.yml`):**
   ```yaml
   grpc:
     client:
       accounts:
         host: ${ACCOUNTS_GRPC_HOST:localhost}
         port: ${ACCOUNTS_GRPC_PORT:9081}
         deadline-ms: ${ACCOUNTS_GRPC_DEADLINE_MS:2000}
   ```

---

## 6. Test Suite & Validation Evidence

### Contract Tests (`shared/proto-contracts`)
- Command: `python -m unittest discover -s tests/contracts` -> **4/4 passed**
- Command: `./mvnw clean test` in `shared/proto-contracts` -> **19/19 passed**
- Verified tests:
  - `AccountsContractTest.testResolveRecipientContact`
  - `AccountsContractTest.testResolveRecipientContact_ChannelScoping`

### Accounts Service Tests (`services/accounts-service`)
- Command: `./mvnw test -Dtest=AccountsInternalServiceGrpcTest` -> **39/39 passed**
- Command: `./mvnw test` in `services/accounts-service` -> **234/234 passed**
- Verified scenarios:
  - `testResolveRecipientContact_EmailChannelScoping_SuppressesPhone`
  - `testResolveRecipientContact_SmsChannelScoping_SuppressesEmail`
  - `testResolveRecipientContact_MissingRequestedContact_FailsClosed`
  - `testResolveRecipientContact_DisabledStaffRecipient_FailsClosed`
  - `testResolveRecipientContact_UnapprovedMerchantRecipient_FailsClosed`
  - `testResolveRecipientContact_UnknownRecipient_ReturnsNotFound`
  - `testResolveRecipientContact_InvalidPurpose_ReturnsInvalidArgument`
  - `testResolveRecipientContact_InvalidChannel_ReturnsInvalidArgument`
  - `testResolveRecipientContact_UnauthorizedCallingService_ReturnsPermissionDenied`
  - `testResolveRecipientContact_UnauthenticatedCaller_ReturnsUnauthenticated`
  - `testResolveRecipientContact_ForbiddenActorDelegation_ReturnsPermissionDenied`

### Communications Service Tests (`services/communications-service`)
- Command: `./mvnw test` in `services/communications-service` -> **27/27 passed**
- Verified client tests:
  - `resolveRecipientContact_Success_EmailChannelScoping`
  - `resolveRecipientContact_Success_SmsChannelScoping`
  - `resolveRecipientContact_NotFound_ThrowsAccountsContactException`
  - `resolveRecipientContact_DisabledRecipient_ReturnsIneligible`
  - `resolveRecipientContact_ServiceUnavailable_ThrowsRetryableException`
  - `resolveRecipientContact_DeadlineExceeded_ThrowsRetryableException`
  - `resolveRecipientContact_PermissionDenied_ThrowsSecurityException`
  - `resolveRecipientContact_InvalidArgument_ThrowsIllegalArgumentException`

### Security Adapter Tests (`shared/security-adapter`)
- Command: `./mvnw test` in `shared/security-adapter` -> **38/38 passed**

---

## 7. Next Steps & Reviewer Checklist

1. Reviewer sign-off:
   - `@thuvarahan-t` (Communications Lead)
   - `@Sinthuha-n` (Accounts Lead)
   - `@Suthankan1` (Platform Coordinator)
2. Follow-on task: Wire `AccountsRecipientContactClient` into Kafka consumer notification dispatcher in **Issue #32**.
