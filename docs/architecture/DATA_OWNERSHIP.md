# Data boundaries and logical entities

**Each business service owns schema, migrations, credentials, transactions and data access.** The team may initially share one PostgreSQL server for cost, but create **separate logical databases and application roles**. A cross-service ID is an opaque reference resolved via API—not a database-level FK across service owners.

| Service | Own data examples | Incoming/outgoing references |
|---|---|---|
| Accounts & Partners | profile keyed by Keycloak `sub`, customer/merchant/courier info, merchant approval, role mapping, hashed/revoked API key metadata | Keycloak subject; merchant/courier IDs passed to Delivery |
| Pricing & Zones | zones, rate rules, pricing version, quote with expiry | Quote ID verified by Delivery gRPC, accepted amount/currency snapshot |
| Delivery Operations | parcels, tracking number, creator and merchant references, status history, courier assignments, proof object references, outbox/idempotency | External principal `sub`, merchant/courier/quote refs; own approved rules |
| Communications | webhook subscriptions/attempts, notification jobs, processed-event receipts, retry schedule | Merchant ID/event ID/aggregate version; permitted contacts resolved via owning APIs |
| Keycloak | users/password hashes, identity login/federation/config | `sub` in JWT; never mix identity DB with business tables |
| GraphQL Query API | **No domain persistence** | Consumes authorized gRPC read APIs |

## Consistency principles

- Delivery is the source of truth for parcel state. Never update parcel fields from Communications, GraphQL or SPA directly.
- Quote application saves a fee snapshot: don't recalculate and silently change already accepted delivery cost.
- Assignments are **not** parcel status transitions. Exactly one active assignment for a parcel; enforce concurrency/uniqueness in the owning database.
- Tracking history is append-only canonical audit. Kafka retention/replay is not a durable historical record of record for the business.
- Proofs in private object storage; DB stores access-controlled metadata/reference, not public object URLs.
- Outbox is in Delivery's DB transaction with status/history; Kafka publish can duplicate; consumers dedupe.
- Deliveries end at `DELIVERED` or `FAILED` in the proposed MVP; exact early failure/proof constraints are still open decisions.

Use [ERDs 14–16](../diagrams/README.md) as **logical designs**, not migrations already implemented.
