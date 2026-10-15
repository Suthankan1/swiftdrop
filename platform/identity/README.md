# Future Keycloak identity configuration — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

Reproducible realm, public SPA client, Google broker, account/profile role mapping.

## Ownership/boundary

Platform owner with Customer pair. Strict public-client PKCE, allowed redirect URIs; never commit Google client secret, passwords or unsanitized realm export.

## First planned slice

First milestone: reproducible **real** local login plus Google broker evidence if configured.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
