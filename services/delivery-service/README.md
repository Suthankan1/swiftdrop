# Delivery Operations — LATER-02 independent booking foundation

Owner: Sinthuha (Customer booking). Java 21 / Spring Boot 4.1.1, matching Accounts conventions.

**Production quote-to-booking is blocked and disabled.** There is no booking controller, no registered BookingService bean, and no production implementation of BookingQuoteProvider or BookingAuthorization. Only health is permitted by the foundation security chain. Do not add a permissive adapter to make the service accept bookings.

Read [LATER-02 contract/audit/handoff](../../docs/contracts/LATER_02_QUOTE_BOOKING.md) for evidence, examples, compatibility conflicts, data ownership and required Pricing decisions. Existing Pricing ValidateQuote fields and public OpenAPI contracts are preserved.

Implemented independently:
- Existing booking request fields with Jakarta validation and authenticated CUSTOMER actor extraction.
- Required adapter seams for quote authorization and existing Pricing validation results.
- Internal booking result (not the incomplete public ParcelDetailResponse).
- PostgreSQL transaction spanning parcel, initial history, outbox and stored idempotent response.
- Concurrency-safe local key replay and one booking per quote, immutable fee/provenance, safe structured failures.
- Initial CREATED/version 1 only. No assignments, notifications, Kafka relay, live tracking, merchant orders, or frontend changes.

## Tests

```sh
./mvnw test
./mvnw verify
```
`verify` uses PostgreSQL 16 Testcontainers by default and fails if Docker is unavailable; it does not silently skip database coverage.

Alternatively supply an explicitly disposable PostgreSQL database:
```sh
DELIVERY_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55483/postgres \
DELIVERY_TEST_DB_USERNAME=later02_test \
./mvnw verify
```
Set `DELIVERY_TEST_DB_PASSWORD` externally if required. Maven properties `delivery.test.db.url` and `delivery.test.db.username` are also supported. Each run creates a uniquely named schema and applies Flyway from scratch; use only a disposable database, because test schemas are intentionally retained for inspection.

Unit tests cover server-derived fee/identity, missing/ineligible/expired/mismatched quotes, denied authorization, invalid requests, strict payload fields and principal roles. Exposure tests ensure no booking command/service bean is enabled. PostgreSQL tests cover persistence/provenance/history/outbox, stable retry after provider loss, changed-payload conflict, eight concurrent same-key and different-key attempts, all-table rollback, expiry/authorization rejection and immutable provenance.

## Runtime configuration for later integration

Delivery's reserved port is 8083. `DELIVERY_DB_URL`, `DELIVERY_DB_USERNAME`, and `DELIVERY_DB_PASSWORD` configure its owned PostgreSQL database. No defaults contain credentials. Schema migration is automatic; never point this foundation at another service's database. The application can host health checks but cannot accept bookings. JWT decoder/converter, reviewed Pricing/Accounts adapters and HTTP controller integration remain explicitly deferred.

The baseline requires all four local records at creation; this foundation writes an outbox event but does not publish it. Extending status/version constraints and adding an outbox relay belong to separately reviewed slices.
