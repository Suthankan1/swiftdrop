# Pricing & Zones — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

Zone/rate rules and expiring fee quotes; internal quote validation by gRPC.

## Ownership/boundary

Separate Pricing DB. Fee represented in minor units/currency; accepted quote is snapshotted in Delivery. Pricing policy owner review from Customer + Dispatcher pairs.

## First planned slice

Future milestone: one quote contract and a tested rate/expiry validation rule.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
