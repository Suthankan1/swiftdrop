# Delivery Operations — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

Parcel booking, tracking number/history, courier assignment/status, proof metadata, receipts, reporting, transactional outbox, SSE.

## Ownership/boundary

One service, multiple feature owners: Customer (booking), Courier (status/proof), Dispatcher (assignment), Platform (receipt/proof storage adapters). Enforce database invariants, actor ownership and single active assignment.

## First planned slice

Future milestone: booking+history first, later assignment/status/outbox. Do not create separate business service per role.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
