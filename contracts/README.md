# SwiftDrop Machine-Readable Integration Contracts Baseline (v1.0 Frozen)

This directory contains the authoritative, versioned, machine-readable integration contract artifacts frozen under **GitHub Issue [#88 (N10-P01)](https://github.com/Suthankan1/swiftdrop/issues/88)**.

All microservice implementations, client SDKs, integration tests, and database schemas **must strictly adhere** to these frozen contracts to guarantee system-wide interoperability.

---

## 1. Directory Structure & Contract Registry

| Subdirectory | Protocol | File / Artifact | Scope & Description | Status |
|---|---|---|---|---|
| [`rest/`](rest/) | **OpenAPI 3.0.3** | [`openapi-pricing.yaml`](rest/openapi-pricing.yaml) | Pricing & Zones REST API: quote calculation, zone definitions, rate versioning | **FROZEN** |
| [`rest/`](rest/) | **OpenAPI 3.0.3** | [`openapi-delivery.yaml`](rest/openapi-delivery.yaml) | Delivery Operations REST API: parcel booking, tracking, status updates, courier assignments, proof ingestion | **FROZEN** |
| [`rest/`](rest/) | **OpenAPI 3.0.3** | [`openapi-accounts.yaml`](rest/openapi-accounts.yaml) | Accounts & Partners REST API: user profiles (`/users/me`), merchant onboarding, API key management | **FROZEN** |
| [`rest/`](rest/) | **OpenAPI 3.0.3** | [`openapi-communications.yaml`](rest/openapi-communications.yaml) | Communications REST API: webhook subscriptions, delivery outcome logs, retry attempts | **FROZEN** |
| [`rest/`](rest/) | **OpenAPI 3.0.3** | [`problem-detail.yaml`](rest/problem-detail.yaml) | Shared RFC 7807 Problem Detail schema for consistent error responses | **FROZEN** |
| [`graphql/`](graphql/) | **GraphQL SDL** | [`schema.graphql`](graphql/schema.graphql) | Read-only composite dashboard Query API: customer, merchant, courier, dispatcher, admin, and stats views | **FROZEN** |
| [`events/`](events/) | **JSON Schema 2020-12** | [`parcel.status.changed.v1.schema.json`](events/parcel.status.changed.v1.schema.json) | Kafka domain event schema for `parcel.lifecycle.v1` topic (`parcel.status.changed.v1` event type) | **FROZEN** |
| [`events/`](events/) | **JSON Payload** | [`parcel.status.changed.v1.example.json`](events/parcel.status.changed.v1.example.json) | Validated synthetic domain event example payload | **FROZEN** |
| [`grpc/`](grpc/) | **Protocol Buffers v3** | [`README.md`](grpc/README.md) | Internal service RPC methods, package namespaces, message definitions, field tags, and handoff to Issue [#92](https://github.com/Suthankan1/swiftdrop/issues/92) | **SPEC FROZEN** (Code gen in #92) |

---

## 2. Global Contract Invariants

1. **Monetary Representation:** All monetary values across all REST, GraphQL, gRPC, and Kafka contracts are strictly represented as **integer minor units** (e.g. `50000` for 500.00 LKR) with an ISO 4217 three-letter currency code (`"LKR"`). Floating-point currency values are strictly forbidden.
2. **Timestamps:** All dates and timestamps are encoded in ISO-8601 UTC format (`YYYY-MM-DDTHH:mm:ssZ`) or `google.protobuf.Timestamp`.
3. **Data Privacy in Events:** Domain events and public tracking streams strictly exclude customer full street addresses, phone numbers, recipient names, bearer tokens, and private proof-of-delivery URLs.
4. **Optimistic Concurrency:** All state mutations and assignment operations enforce optimistic locking using monotonic integer versions (`version` / `expectedVersion`).
5. **Contract Validation:** All OpenAPI contracts are verified with `@redocly/cli lint`, GraphQL schemas with `graphql-schema-linter`, and JSON events with `jsonschema`.
