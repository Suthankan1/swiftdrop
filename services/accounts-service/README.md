# Accounts & Partners — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

User/customer/merchant/courier profiles, merchant approvals, scoped API-key metadata, controlled role mapping and internal eligibility gRPC.

## Ownership/boundary

Keycloak owns passwords and OIDC login; this service uses verified Keycloak `sub`. Database owned only by Accounts. Merchant onboarding pair + Platform own respective features; no caller-provided tenant identity.

## First planned slice

First milestone: current-user profile bootstrap and an authorization-protected sample endpoint, not a full merchant API.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
