# SwiftDrop Contract Validation & Quality Gates (Issue #140 / N10-QA-01)

This directory contains executable test automation, backward compatibility checks, and cross-protocol regression suites for SwiftDrop's integration contracts:

1. **REST APIs (OpenAPI 3.0.3):** `contracts/rest/openapi-*.yaml` and `contracts/rest/problem-detail.yaml`
2. **Read-only Dashboard API (GraphQL SDL):** `contracts/graphql/schema.graphql`
3. **Kafka Domain Events (JSON Schema 2020-12):** `contracts/events/parcel.status.changed.v1.schema.json`
4. **Internal RPCs (Protocol Buffers v3):** `contracts/grpc/` (`accounts.proto`, `delivery.proto`, `pricing.proto`)
5. **Cross-Protocol Invariants:** Integer minor unit currency, ISO-8601 UTC timestamps, bounded pagination, and status enum alignment.

---

## 1. Canonical Cross-Protocol Test Runner

The canonical test entry point executes the entire contract regression suite, validates mandatory artifacts, and records Git commit SHA evidence:

```bash
# Run complete cross-protocol regression suite (Python unit tests, diff gates, standalone invariants, Java gRPC tests)
python3 tests/contracts/run_contract_regressions.py

# Optional: write structured machine-readable test evidence report
python3 tests/contracts/run_contract_regressions.py --output-json reports/contract-regression-report.json

# Optional: skip Java shared/proto-contracts maven tests (e.g. in restricted environments)
python3 tests/contracts/run_contract_regressions.py --no-java-grpc
```

The runner:
- **Fails Closed:** Returns exit code 1 immediately if any mandatory contract artifact is missing.
- **Records Evidence:** Captures HEAD commit SHA, base ref SHA, Python/Java versions, and test counters.
- **Zero Failures Allowed:** Executes all unit test suites, standalone invariant scripts, and baseline compatibility diffs without skips or warnings.

---

## 2. Test Suites & Regressions

### A. REST Contract Regressions (`test_rest_contracts.py`)
- **OpenAPI 3.0.3 Validation:** Validates `openapi-accounts.yaml`, `openapi-communications.yaml`, `openapi-delivery.yaml`, `openapi-pricing.yaml`, and `problem-detail.yaml`.
- **$ref Integrity & Error Formats:** Validates component schema references and enforces RFC 7807 `ProblemDetail` compliance (`type`, `title`, `status`, `detail`, `instance`, `code`).
- **Real Provider-Backed Simulation:** Exercises real Spring handler request/response logic for customer (`POST /api/v1/parcels`) and merchant (`POST /api/v1/merchant/parcels`) booking endpoints, asserting status codes (201, 400, 401, 403, 404, 409, 422) against schema definitions.
- **Negative Compatibility Fixtures:** Detects removed endpoints, dropped HTTP methods, introduced required parameters without defaults, and missing 2xx responses.

### B. GraphQL Read-Only Regressions (`test_graphql_regressions.py`)
- **Read-Only Invariant:** Asserts strictly 0 mutations and 0 subscriptions on the schema.
- **Public Schema Decision (#119):** Verifies `Query.publicTracking` is retired from GraphQL in favor of REST (`/api/v1/public/parcels/track/{trackingNumber}` per #115/#133).
- **Resolver Execution & RBAC:** Executes authorized queries against reference dashboard resolvers (`customerDashboard`, `adminDashboard`, `deliveryStats`) with mock JWT security contexts.
- **Security & Syntax Rejection:** Validates that mutation operations and subscription operations are rejected at parse/validation time, and anonymous/unauthorized access is denied.

### C. gRPC & Protobuf Regressions (`test_grpc_compatibility_and_deadlines.py`)
- **Protobuf v3 Validation:** Validates `accounts.proto`, `delivery.proto`, and `pricing.proto`.
- **Admin Pending-Merchant RPC (#121/#139):** Verifies `GetPendingMerchantsSummary` RPC signature, bounded pagination (`pageSize <= 50`), field tags, and ADMIN actor validation.
- **Data Conventions:** Enforces integer minor unit currency (`int64`), `google.protobuf.Timestamp`, and proto3 syntax.
- **gRPC Status & Deadline Mapping:** Tests standard status mappings (`DEADLINE_EXCEEDED`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `INVALID_ARGUMENT`).
- **Negative Compatibility Fixtures:** Detects breaking changes including removed fields without `reserved`, renumbered tags, incompatible type changes, and altered package namespaces.

### D. Kafka Event Contract Regressions (`test_kafka_event_contract.py`)
- **JSON Schema 2020-12:** Validates `parcel.status.changed.v1.schema.json` with Draft 2020-12 validator and ISO-8601 date-time format checks.
- **Lifecycle Positive Fixtures:** Validates all 6 lifecycle events (`CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED` with all 7 failure reasons).
- **PII Leakage Prevention:** Enforces `additionalProperties: false` and validates that customer PII (`recipientPhone`, `recipientEmail`, `streetAddress`) is rejected.
- **Additive Compatibility Policy:** Validates that adding optional fields under backward-compatible policies is accepted, while breaking envelope modifications fail.

### E. Cross-Protocol Conventions (`test_cross_protocol_conventions.py`)
- **Monetary Units:** Enforces integer minor units (`int64`, `Int!`, `integer`) with ISO-4217 currency across REST, GraphQL, gRPC, and Kafka. Zero floating-point money.
- **Timestamps:** Enforces UTC ISO-8601 strings across REST/GraphQL/Kafka and `google.protobuf.Timestamp` across gRPC.
- **Bounded Pagination:** Enforces consistent 0-based page index, default page size of 20, and max upper bound of 50 across all protocols.
- **Status & Tracking Consistency:** Enforces consistent status enum names and canonical tracking number regex format (`SWD-YYYYMMDD-[A-Z0-9]{4,8}`).

### F. Concurrent Booking Regressions (`test_concurrent_booking_regression.py`)
- **October 23 Amendment Contention:** Simulates concurrent same-quote booking across customer (`POST /api/v1/parcels`) and merchant (`POST /api/v1/merchant/parcels`) entry points with different idempotency keys using synchronized thread barriers.
- **Single Canonical Sequence:** Asserts exactly 1 canonical parcel record, 1 fee snapshot, 1 status history record, and 1 outbox event sequence.
- **Deterministic Losing Behavior:** Asserts the losing concurrent request receives deterministic `409 QUOTE_ALREADY_CONSUMED`.
- **Idempotent Replay:** Asserts replaying the winning request with the original idempotency key returns the original parcel with 0 duplicate records. Conflicting payloads with the same key yield `409 IDEMPOTENCY_CONFLICT`.
- **No Orphan Quotes:** Asserts an injected transaction failure rolls back all database records, leaving the quote unconsumed and available for subsequent retry.

### G. Abort & Cancellation Actor Matrix (`test_abort_actor_matrix.py`)
- **Role & State Matrix:** Evaluates full abort/cancellation authorization across 5 roles (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`) and 6 lifecycle states (`CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED`).
- **Sender Cancellation:** Customer/Merchant sender can cancel unassigned `CREATED` parcel; cannot cancel after courier pickup.
- **Courier Delivery Abort:** Assigned courier can fail parcel during delivery with reason code; unassigned courier cannot abort.
- **Dispatcher Operational Abort:** Dispatcher can abort in operational states (`CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`) with administrative reasons.
- **Terminal Immutability:** Terminal states `DELIVERED` and `FAILED` are strictly immutable for all actors.
- **Optimistic Concurrency:** State transitions reject stale `aggregateVersion`, preventing race conditions.

---

## 3. Standalone Script & Module Execution

Run individual test suites directly with Python's standard test runner:

```bash
# Discover and run all unit tests
python3 -m unittest discover -s tests/contracts -p "test_*.py" -v

# Run individual suites
python3 -m unittest tests/contracts/test_rest_contracts.py -v
python3 -m unittest tests/contracts/test_graphql_regressions.py -v
python3 -m unittest tests/contracts/test_grpc_compatibility_and_deadlines.py -v
python3 -m unittest tests/contracts/test_kafka_event_contract.py -v
python3 -m unittest tests/contracts/test_cross_protocol_conventions.py -v
python3 -m unittest tests/contracts/test_concurrent_booking_regression.py -v
python3 -m unittest tests/contracts/test_abort_actor_matrix.py -v

# Standalone invariant scripts
python3 tests/contracts/test_delivery_status_contract.py
python3 tests/contracts/test_outbox_contract.py
python3 tests/contracts/test_delivery_stats_graphql_contract.py

# Contract baseline diff & schema compatibility
python3 tests/contracts/validate_contracts.py --check-compatibility --base-ref=origin/test

# Java Protobuf generation and in-process gRPC tests
mvn test -f shared/proto-contracts/pom.xml
```

---

## 4. CI/CD Integration Note

Per team module ownership rules and the Issue #140 specification, CI workflow files (`.github/workflows/*`) are preserved without modifications. The canonical command:

```bash
python3 tests/contracts/run_contract_regressions.py
```

is designed for direct integration by the Platform/CI engineer in pull request checks targeting `test` and `main`.
