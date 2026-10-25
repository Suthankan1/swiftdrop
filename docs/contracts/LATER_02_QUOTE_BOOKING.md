# LATER-02 — Delivery booking foundation and contract blockers

Owner: Sinthuha. Local audit: 2026-10-24, source HEAD `14560f8`.
Status: **Independent foundation implemented; upstream contract freeze and production booking blocked.**
The task owner's explicit follow-up authorizes independent foundation work only, preserves the existing Pricing response, and prohibits enabling production creation until contract/authorization approval.

## Audit and scope

Reviewed the assignment scope/traceability, repository agent rules, module READMEs, v1.3 baseline/data ownership, ADRs, login/REST/gRPC/interface contracts, runtime decision register, Customer journey review, diagrams 07/10/14/15, roadmap, team ownership, environment conventions, existing Accounts migrations/security/tests, Gateway routing, frontend routes, and contract Git history. No locally indexed LATER-02 issue text was found; the current user task is the acceptance source. Working tree was clean before work.

Evidence:
- `contracts/rest/openapi-delivery.yaml`: CUSTOMER `POST /api/v1/parcels`, quoteId and address/recipient fields, initial response contract.
- `contracts/rest/openapi-pricing.yaml`: public/optional-JWT quote creation and reads; fee breakdown exists in REST, not the internal validation response.
- `contracts/grpc/README.md`: `ValidateQuoteRequest` includes expected rate/amount/currency; response contains only isValid, quoteId, rateVersion, verifiedAmountMinorUnits, currency, expiresAt, validationCode. No generated `.proto` or Pricing runtime exists.
- `docs/report/customer_journey_review.md`, Figures 07/10: same key/payload returns original 201; mismatch returns 409; parcel/history/outbox/idempotency commit together.
- Figures 14/15: conceptual actor/digest/normalized-request fields are not an implemented Pricing protocol.
- Gateway currently routes only users/me. Customer frontend has no quote or booking workflow. Pricing and Delivery were placeholder service directories.

Upstream: Pricing owns calculation, rate eligibility, expiry, and quote persistence; Accounts owns business eligibility. Downstream: future Customer confirmation, courier/dispatcher operations, receipt projection, GraphQL, and Kafka relay/Communications. This change enables none of those workflows.

Stack/conventions: Java 21, Spring Boot 4.1.1, Maven wrapper, Spring MVC/Validation/Security, PostgreSQL/Flyway, Jackson 3; JUnit/Mockito/AssertJ and PostgreSQL integration tests. UUID identifiers, camelCase DTOs, snake_case SQL, integer minor-unit money, ISO-8601 UTC `Instant`/TIMESTAMPTZ, RFC 7807 problem details. Local JDBC transactions use the existing Spring data stack; there is no shared business-entity JAR or cross-service SQL.

## Compatibility and version boundary

Existing OpenAPI v1.0.0, GraphQL, event schema v1.0.0, and Pricing gRPC documentation remain byte-for-byte unchanged. This document describes **Delivery foundation revision 0.1**, not an approved cross-team contract freeze. No public API version is incremented because no new public endpoint is enabled.

`QuoteValidation` mirrors exactly the seven fields in the current `ValidateQuoteResponse`, using Java UUID/Instant/long representations. It adds no owner, merchant, dimension, breakdown, status, reservation, or consumption field. This record is an internal adapter result, not a protobuf implementation.

`BookingQuoteProvider.validate(quoteId, authenticatedSubject)` is a Delivery-owned seam. Its future adapter must resolve the RPC's expected amount/currency/rate authoritatively; the browser cannot supply them. It has no production implementation. `BookingAuthorization.requireAllowed(subject, request)` is a separate mandatory seam for quote-use permission, account eligibility and priced-route/request binding. It also has no implementation. There is no allow-all fallback and anonymous quotes are not assigned assumed owners.

The foundation `BookingService` is not registered as a Spring bean. There is no booking controller or Gateway change. The service security chain permits only health and denies other traffic. Full JWT signature/issuer/audience validation and role conversion must be integrated before a future controller uses `BookingActor.from(validatedJwtAuthentication)`; this helper does not validate JWT signatures itself. It requires CUSTOMER authority and a nonblank subject; merchant-only access is rejected. Booking actor identity is never a request-body field.

## Local command and data contract

Internal command: `book(BookingActor actor, UUID key, BookParcelRequest request)`.

Request uses existing booking fields only:
```json
{
  "quoteId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "pickupAddress": "123 Galle Road, Colombo 03",
  "dropoffAddress": "45 Kandy Road, Gampaha",
  "recipientName": "Example Recipient",
  "recipientPhone": "+94770000000",
  "deliveryNotes": "Call on arrival"
}
```
`quoteId`, addresses, recipient name and phone are required. Delivery foundation storage bounds are 2000 characters per address/notes, 255 for name, 50 for phone. These bounds are local protective constraints requiring inclusion in the future reviewed public schema. Input strings are not silently trimmed/repriced. Missing and null optional notes deserialize identically; explicit empty notes differ. Unknown JSON fields are rejected by the configured mapper; amounts, currency, actor and merchant identifiers are absent from the DTO.

Example internal result (NOT an implemented HTTP response):
```json
{
  "parcelId": "3df7de31-e018-40f4-adfd-cef27e286d32",
  "trackingNumber": "SWD-20261024-12AB34CD",
  "createdBySub": "verified-customer-subject",
  "quoteId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "currentStatus": "CREATED",
  "version": 1,
  "amountMinorUnits": 50000,
  "currency": "LKR",
  "rateVersion": "RATE-2026-V1",
  "pickupAddress": "123 Galle Road, Colombo 03",
  "dropoffAddress": "45 Kandy Road, Gampaha",
  "createdAt": "2026-10-24T12:00:00Z"
}
```
The planned HTTP route remains `POST /api/v1/parcels`, success/replay 201, but **cannot be called successfully in this foundation**. Existing `ParcelDetailResponse` requires weight and dimensions absent from ValidateQuote; the internal result is deliberately not presented as that public DTO.

Snapshot: verified amount, currency, rateVersion, quote expiry and complete existing validation result; caller subject; submitted addresses/recipient details; initial result for replay. Quote ID is an opaque external reference to Pricing. Delivery never recalculates a fee. The original quote breakdown/creation timestamp/dimensions are not fabricated or stored. Booking creation time is separate from quote expiry. Merchant booking is out of scope.

Only initial `CREATED` at version 1 is supported. No assignment, status-update, payment, notification, tracking or receipt operation is implemented. Future lifecycle migrations must extend the initial SQL status/version constraints deliberately.

## Validation and failure semantics

Foundation checks shape before integration, calls authorization before Pricing, validates the returned quote ID, valid flag/code, nonnegative verified amount, ISO currency, nonblank rate version and future expiry (`expiresAt > now`, equality is expired). No local quote cancellation state is invented. Upstream `isValid=false` rejects ineligible quotes; unknown codes fail closed. Nonexistence and authorization denial require adapters to supply safe `BookingFailure` errors after approval of upstream semantics.

| Foundation outcome | Intended HTTP mapping | Code |
|---|---:|---|
| Missing actor | 401 | UNAUTHENTICATED |
| Non-CUSTOMER principal | 403 | FORBIDDEN |
| Adapter rejects quote access | 403 | QUOTE_FORBIDDEN |
| Invalid request/key | 400 | INVALID_REQUEST |
| Adapter confirms missing quote | 404 | QUOTE_NOT_FOUND |
| Same key, different command | 409 | IDEMPOTENCY_CONFLICT |
| Quote already booked in Delivery | 409 | QUOTE_ALREADY_CONSUMED |
| Expiry including exact boundary | 422 | QUOTE_EXPIRED |
| Inactive rate | 422 | QUOTE_RATE_SUPERSEDED |
| Other invalid quote result | 422 | QUOTE_INELIGIBLE |
| Malformed/mismatched provider result | 503 | QUOTE_PROVIDER_INVALID |
| Exhausted identifier collision retries | 503 | BOOKING_UNAVAILABLE |

No HTTP exception advice is exposed yet. `BookingFailure.problem()` creates the existing RFC 7807 shape with `code` and optional `invalidParameters`, without rejected values, token contents, addresses or upstream exception details. Example:
```json
{
  "type": "https://swiftdrop.com/errors/INVALID_REQUEST",
  "title": "Bad Request",
  "status": 400,
  "detail": "Booking request validation failed.",
  "code": "INVALID_REQUEST",
  "invalidParameters": [{"field": "quoteId", "message": "must not be null"}]
}
```
Provider availability/deadline translation, concealment of unauthorized quote existence, and final public error additions require integration review. Raw provider/database exceptions must not be exposed by a future HTTP handler.

## Persistence and local idempotency

Flyway `V1__create_booking_foundation.sql` creates only Delivery-owned tables:
- `parcels`: UUID ID, unique tracking number, unique external quote ID, authenticated creator, request data, immutable fee/provenance snapshot, CREATED/version 1.
- `status_history`: initial transition; FK to parcel; unique parcel/state version; append-only trigger.
- `outbox_events`: existing event envelope, FK to parcel, unique parcel/version, pending-publication index. No Kafka publisher is added.
- `idempotency_records`: composite primary key `(actor_sub, idempotency_key)`, SHA-256 command digest, original result, FK binding parcel to the same creator. No expiration/cleanup policy is invented; records remain until an approved retention migration.

TransactionTemplate provides a READ COMMITTED local transaction. A transaction-scoped PostgreSQL advisory lock serializes the same actor/key; exact persisted key/actor remains authoritative even if the advisory hash collides. Stable serialization of typed command fields produces the digest. Same actor/key and same command returns the stored result without new authorization/Pricing calls, including after quote expiry. Different payload returns 409. Different keys competing for one quote are protected by `UNIQUE(quote_id)` and conflict-aware insertion; only one wins. Tracking collisions retry without aborting the transaction. Errors roll back all four records and release locks.

This is **local single-use enforcement in Delivery**, not Pricing consumption. No cross-database foreign key exists. The required adapter attests quote existence; missing/mismatched validation cannot create a row through the service. PostgreSQL snapshot consistency checks and immutable-provenance triggers prevent later fee/quote/creator reassignment. A trusted database operator could insert a fictional external quote: existence cannot be enforced across owned databases with a local FK.

The Pricing validation and Delivery commit are not a distributed atomic transaction. No guarantee against remote cancellation/rate change between validation and commit is claimed. Approval must define whether validation-time eligibility is sufficient or an idempotent reservation protocol is required.

## Minimum upstream decisions required before release

1. **Quote authorization:** reconcile public anonymous Pricing REST quotes with the conceptual ERD's actor/merchant binding. Define safe adoption/claiming or authenticated ownership and how the backend proves it. Do not simply trust an actor header from an unverified caller.
2. **Authoritative input:** the existing RPC requires expected rate/amount/currency while BookParcelRequest supplies only quoteId. Approve a server-side lookup path or a backward-compatible new internal lookup/validation RPC. Do not extend the current response silently.
3. **Payload binding:** define how full pickup/dropoff addresses map to quoted zones and how normalized payload/request digest is checked. A valid cheap quote must not authorize an unrelated expensive route.
4. **Response completion:** identify an authoritative source for weight/dimensions required by ParcelDetailResponse and any required immutable price breakdown. Add only reviewed additive fields/new RPC version if necessary.
5. **Eligibility/atomicity:** define missing/cancelled/consumed quote codes, cancellation races, account eligibility, and whether validation-time attestation or a reservation/confirm protocol is required. Local UNIQUE quote_id already prevents duplicate Delivery bookings.
6. **Idempotency compatibility:** OpenAPI currently says header optional; reviewed Customer flow says required. The internal foundation requires a key, but no published contract is changed. Owners must approve required-header change or specify behavior for existing keyless callers.
7. **Runtime integration:** implement authenticated gRPC/deadlines/actor delegation, approve JWT integration and Gateway routing, and publish real HTTP request/response/error tests. Only then register BookingService and controller.

These are blockers, not implied approvals. Pricing code, contracts and other team modules remain unchanged. Next small task: Pricing/Customer owners approve the lookup and authorization boundary needed for IF-GRPC-04.

## Validation and handoff

See the Delivery README for commands and test-environment details. Tests use synthetic adapters only inside test scope and real PostgreSQL persistence. They prove local invariants, not a real Pricing integration. No frontend, merchant, Pricing, Gateway, or shared contract runtime was modified. No commit/push was made.

### Executed checks (2026-10-24)

- Delivery: `mvn verify -Ddelivery.test.db.url=jdbc:postgresql://127.0.0.1:55483/postgres -Ddelivery.test.db.username=later02_test` — **13 unit/exposure + 8 PostgreSQL integration tests passed; 0 failures/errors/skips; package built**. Final run also used Maven offline mode after dependency resolution.
- Accounts: `mvn test` — **105 passed, 0 failures/errors/skips**, including existing merchant and staff tests.
- Gateway: `mvn test` — **19 passed, 0 failures/errors/skips**.
- Tests ran from an isolated copy of the clean source revision with the Delivery changes, before application to the original checkout.
- Docker was unavailable. Database tests ran against isolated local **PostgreSQL 17.11**, not the default PostgreSQL 16 container. The Testcontainers/PG16 execution path was not run.
- Accounts PostgreSQL integration tests, browser/frontend tests, live Keycloak/gRPC/Pricing/Gateway booking integration and Kafka publication were not run. No Pricing implementation or quote tests exist locally. No claim of production quote eligibility/authorization verification is made.
- Initial verification exposed a Spring Security MVC test-context configuration issue; adding explicit `EnableWebSecurity` fixed it. The final full Delivery verification passed after that fix and after final SQL constraint changes.

### Changed-file inventory

Only `services/delivery-service/` and this document changed:
- Build/bootstrap: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `README.md`, `src/main/resources/application.yml`.
- Application: `DeliveryServiceApplication.java`, `BookingFoundationSecurity.java`.
- Booking package: `BookParcelRequest.java`, `ParcelBooking.java`, `BookingActor.java`, `BookingFailure.java`, `BookingRepository.java`, `BookingService.java`.
- Quote integration seams: `BookingQuoteProvider.java`, `BookingAuthorization.java`, `QuoteValidation.java`.
- Migration: `src/main/resources/db/migration/V1__create_booking_foundation.sql`.
- Tests: `BookingFoundationTest.java`, `BookingExposureTest.java`, `BookingPostgresIT.java`.

Definition of done: the explicitly narrowed independent foundation is implemented and tested. **Original full LATER-02 remains blocked** until upstream approval and real quote-to-booking integration are completed. No cross-team contract signoff is claimed.
