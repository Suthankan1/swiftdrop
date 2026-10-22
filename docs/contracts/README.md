# Contract-First Integration Baseline (v1.0 Frozen)

This directory provides the architectural integration contracts and guidelines for the SwiftDrop enterprise parcel delivery platform.

In accordance with **Issue [#88 (N10-P01)](https://github.com/Suthankan1/swiftdrop/issues/88)**, the authoritative, machine-readable contracts have been frozen in the root [`contracts/`](../../contracts/) directory, supported by the [Runtime Decision Register](../architecture/RUNTIME_DECISION_REGISTER.md) and the [Service Interface Matrix](SERVICE_INTERFACE_MATRIX.md).

| Contract Category | Architectural Guidance | Frozen Machine-Readable Location | Status |
|---|---|---|---|
| **Login & Identity** | [Login & Auth Contract](LOGIN_CONTRACT.md) | Verified in `platform/identity/realm-swiftdrop.json` & `services/accounts-service` | **VERIFIED PASS (Nov 1)** |
| **External REST APIs** | [REST Contracts Guide](REST.md) | [`contracts/rest/`](../../contracts/rest/) (OpenAPI 3.0.3 for Pricing, Delivery, Accounts, Communications) | **FROZEN (Issue #88)** |
| **Read-Only GraphQL** | [GraphQL Query API Guide](GRAPHQL.md) | [`contracts/graphql/schema.graphql`](../../contracts/graphql/schema.graphql) (Read-only SDL for Dashboards) | **FROZEN (Issue #88)** |
| **Internal gRPC / Protobuf** | [gRPC Architecture Plan](GRPC.md) | [`contracts/grpc/README.md`](../../contracts/grpc/README.md) (Package namespaces, RPC specs, handoff to Issue [#92](https://github.com/Suthankan1/swiftdrop/issues/92)) | **SPEC FROZEN (Issue #88)** |
| **Async Business Events** | [Kafka Reliability Guide](KAFKA.md) | [`contracts/events/`](../../contracts/events/) (`parcel.status.changed.v1` JSON Schema & Example) | **FROZEN (Issue #88)** |

---

## Change Management Protocol

1. **Contract-First Rule:** No developer or AI agent shall implement microservice controllers, DTOs, or database schemas that conflict with the frozen contracts in `contracts/`.
2. **Backward Compatibility:** All future updates to contracts must remain backward-compatible within major versions (v1). Breaking field changes require an ADR and team lead approval.
3. **Validation Gate:** All contract PRs must pass `@redocly/cli lint`, `graphql-schema-linter`, and `jsonschema` verification.
