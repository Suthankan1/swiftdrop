# SwiftDrop Contract Validation & Quality Gates

This directory contains executable test automation and backward compatibility checks for SwiftDrop's integration contracts:

1. **REST APIs (OpenAPI 3.0.3):** `contracts/rest/openapi-*.yaml`
2. **Read-only Dashboard API (GraphQL SDL):** `contracts/graphql/schema.graphql`
3. **Kafka Domain Events (JSON Schema 2020-12):** `contracts/events/parcel.status.changed.v1.schema.json`
4. **Internal RPCs (Protocol Buffers v3):** `contracts/grpc/` (Specification frozen; code generation tracked in Issue #92)

---

## 1. Local Execution

Run all contract validations and backward compatibility checks against `origin/test`:

```bash
python3 tests/contracts/validate_contracts.py --check-compatibility --base-ref=origin/test
```

Run specific validation gates:

```bash
# OpenAPI REST contracts only
python3 tests/contracts/validate_contracts.py --mode=openapi

# GraphQL read-only SDL only
python3 tests/contracts/validate_contracts.py --mode=graphql

# Kafka domain events & negative payloads
python3 tests/contracts/validate_contracts.py --mode=kafka

# Delivery guarded status transition & history contract (Issue #23)
python3 tests/contracts/test_delivery_status_contract.py

# Delivery outbox event contract & schema invariants (Issue #31)
python3 tests/contracts/test_outbox_contract.py

# Protobuf contracts validation & compatibility diffing (Issue #92)
python3 tests/contracts/validate_contracts.py --mode=protobuf --check-compatibility --base-ref=origin/test

# Protobuf contract invariant unit tests (Issue #92)
python3 -m unittest tests/contracts/test_protobuf_contracts.py -v
```

---

## 2. CI Automation

In GitHub Actions (`.github/workflows/contract-checks.yml`), this suite executes on all pull requests targeting `test` or `main`.

- **Breaking Change Detection:** Automatically diffs REST, GraphQL, Kafka, and Protobuf contracts against the PR target branch (`check_protobuf_compatibility`, `check_kafka_compatibility`, `check_openapi_compatibility`, `check_graphql_compatibility`).
- **Read-Only Invariant:** Enforces that GraphQL SDL contains 0 mutations and 0 subscriptions.
- **Privacy Gate:** Validates that Kafka event envelopes and payloads forbid private customer data via `additionalProperties: false`.
- **Protobuf Invariants (Issue #92):** Enforces `proto3`, package/Java naming, permanent field tags, integer minor units for monetary fields, UTC timestamps, and rejection of renumbered/removed fields without `reserved`.
- **Java Generation Pipeline:** `shared/proto-contracts` compiles contracts with pinned `protoc:3.25.5` and `protoc-gen-grpc-java:1.68.1`, executing 14 contract and in-process gRPC tests.
