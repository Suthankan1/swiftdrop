# Future cross-service tests — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

API/gRPC/Kafka contract/integration, Keycloak/JWT negative security checks, SSE recovery and UI e2e.

## Ownership/boundary

Do not claim tests exist yet. Owners generate tests alongside their own feature PRs. Isolate integration fixtures, use Testcontainers where appropriate and report actual coverage.

## First planned slice

First integration suite focuses on real local/Google login, invalid JWT, wrong role and owner denials.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
