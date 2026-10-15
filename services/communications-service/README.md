# Communications — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

Kafka notification/webhook consumer groups, durable inbox/jobs, signed webhook retry and provider adapters.

## Ownership/boundary

Own Communications DB and side-effect jobs, **not** parcel state. Merchant pair owns subscriptions UI/API; Platform-owned bounded extra is webhook retry worker, reviewed with Merchant pair. Kafka event contains no raw PII.

## First planned slice

Future milestone: one idempotent event receipt + durable job, then signed delivery and retry tests.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
