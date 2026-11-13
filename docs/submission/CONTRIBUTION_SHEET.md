# SwiftDrop — Contribution Sheet

**Group:** Minveli_2.0 · **Module:** Enterprise Application Development · **Team size:** nine members

The roster and index numbers come from the November 1 submission. The team responsibilities are cross-referenced against the [contribution log](https://github.com/Suthankan1/swiftdrop/blob/97687263a69655b841dbd659cd68ae34dcfaeb84/docs/team/CONTRIBUTION_LOG.csv) and the [ownership record](https://github.com/Suthankan1/swiftdrop/blob/97687263a69655b841dbd659cd68ae34dcfaeb84/docs/team/OWNERSHIP.md). Praveen APE is the submission display name for the member recorded as Eric in the repository.

| Member | Index | Team / role |
|---|---|---|
| Suthankan B. | 234202J | Platform, security and cross-module integration lead |
| Abhinaya J. | 234006L | Courier module — front end |
| Vibhushan G. | 235115D | Courier module — backend; independent security QA |
| Thuvarahan T. | 234210G | Merchant module — backend |
| Gajanthan S. | 234065N | Customer module — front end and service foundations |
| Pushmitha V. | 234214X | Dispatcher/Admin module — front end |
| Sinthuha N. | 234197U | Customer module — backend and Accounts domain |
| Harithkeshan R. | 234077D | Dispatcher/Admin module — backend; report lead |
| Praveen APE | 234163M | Merchant module — front end, webhooks and release QA |

## Responsibilities and main contributions

### Suthankan B. (234202J) — Platform, Security and Cross-Module Integration Lead
- Owned all authentication: Keycloak realm, roles, custom login theme, e-mail verification, optional TOTP, Google identity broker; React OIDC client with PKCE and in-memory tokens; Spring Cloud Gateway JWT validation; shared resource-server security.
- Platform: Protobuf contracts with the team, Docker images, full Docker Compose runtime, GitHub Actions pipeline with security scanning and contract checks, Kubernetes (k3d/K3s) with Helm, monitoring, backups and recovery drills.
- Final phase (November): integration blockers and security/privacy hardening across services; Same Day service tier; admin user controls and gateway enforcement of disabled accounts; AI delivery assistant with Gemini and MCP tools (bonus); dispatcher reassign/unassign; system audits and their remediation; design documentation and submission package.
- Cross-module slices: PDF receipts, webhook retry engine support, proof storage adapter, `deliveryStats` resolver.

### Abhinaya J. (234006L) — Courier Module, Front End
- Guarded courier landing page; mobile-friendly assigned-deliveries list; authorised parcel details; stage and notes actions with conflict recovery.
- Backend: courier dashboard GraphQL resolver; policy-gated proof retention cleanup with a hardened transaction.
- Accessibility, session-recovery, monitoring-query and integrated failure-recovery tests.

### Vibhushan G. (235115D) — Courier Module, Backend and Independent Security QA
- Independent security tester for the login milestone (36 passed, 0 failed, 4 blocked scenarios).
- Delivery Operations: guarded transitions with append-only history, guarded assignment, transactional outbox with a bounded Kafka relay, proof metadata and endpoints, complete lifecycle transitions.
- Authenticated SSE streaming with REST recovery and multi-replica broadcast over Kafka; webhook egress rotation and replay; multi-actor authorisation regression suite.

### Thuvarahan T. (234210G) — Merchant Module, Backend
- Merchant approval state and tenant authorisation provider in Accounts; merchant design diagrams.
- Tenant-bound webhook subscription API; audited admin merchant approval and suspension; tenant-bound merchant booking with idempotency.
- Quote snapshot JSONB mapping, secret-scanner fixes, correlation IDs and bounded metrics, dependency upgrades.

### Gajanthan S. (234065N) — Customer Module, Front End and Service Foundations
- Customer steward; OIDC sequence and Accounts ERD review; customer diagrams.
- Protobuf contracts and Java generation; Delivery Operations foundation; Accounts gRPC for courier eligibility, scoped profiles and contact lookup.
- Customer quote and booking screens, dashboard summary cards, authenticated tracking with SSE recovery, public tracking stream; gateway business routes and error handling.

### Pushmitha V. (234214X) — Dispatcher/Admin Module, Front End
- Protected dispatcher and admin landing pages; versioned admin zone and rate commands in Pricing.
- Admin merchant review, user and staff management, zone and pricing editor screens.
- `adminDashboard` resolver; daily outcomes reports with reporting-period utilities; capacity benchmarks.

### Sinthuha N. (234197U) — Customer Module, Backend and Accounts Domain
- Accounts domain steward; Accounts and Pricing ERDs.
- Delivery booking foundation, customer history and receipts, bounded PDF receipt adapter, authorised parcel reads, gRPC reads for customers/merchants/couriers, recipient authorisation read, secure public tracking.
- Cross-protocol contract regressions, end-to-end booking-to-delivery test, post-rollback consistency verification.

### Harithkeshan R. (234077D) — Dispatcher/Admin Module, Backend and Report Lead
- Staff profile read models and controlled provisioning; first academic report draft and contribution matrix.
- Pricing & Zones service bootstrap: Flyway schema, versioned rate domain, expiring quote API with snapshots.
- Read-only GraphQL Query API scaffold, gRPC transport and complexity limits; paginated dispatcher queue with manual assignment; admin reads and staff lifecycle APIs.

### Praveen APE (234163M) — Merchant Module, Front End, Webhooks and Release QA
- Merchant landing page with approval state; independent clean-clone setup verification.
- Signed HTTPS webhook attempts with response classification; durable scheduler with exponential retry and exhaustion; owner of the webhook delivery engine.
- `merchantDashboard` resolver and merchant order dashboard/detail; merchant, webhook and operator acceptance tests; release-candidate acceptance and blocker triage.

## Signatures

Each member confirms the responsibilities listed for them.

| Member | Signature | Date |
|---|---|---|
| Suthankan B. | | |
| Abhinaya J. | | |
| Vibhushan G. | | |
| Thuvarahan T. | | |
| Gajanthan S. | | |
| Pushmitha V. | | |
| Sinthuha N. | | |
| Harithkeshan R. | | |
| Praveen APE | | |
