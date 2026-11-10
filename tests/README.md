# Cross-service tests

These suites check the platform beyond each service's own unit and integration tests
(`./mvnw verify` in each service, `pnpm test` in `frontend/`).

| Folder | What it checks | How to run |
| --- | --- | --- |
| `contracts/` | REST, gRPC, GraphQL and event contracts stay consistent with the code | `python3 -m pytest tests/contracts -q` |
| `platform-compose/` | Compose file safety: no secrets, required keys fail closed, isolation | `python3 -m pytest tests/platform-compose -q` |
| `kubernetes/` | Helm chart rendering, network policies, security contexts, pinned images | `python3 -m pytest tests/kubernetes -q` (needs `helm`) |
| `ci/` | CI workflow structure and the fail-closed release gate | `python3 -m pytest tests/ci -q` |
| `terraform-policy/` | Infrastructure-as-code policy checks | `python3 -m pytest tests/terraform-policy -q` |
| `e2e/` | In-process journey harnesses (no running stack needed) | see `e2e/README.md` |
| `e2e/live/` | **Live journeys against a running local stack**: login, quote, booking, dispatch, courier stages, proof, receipt, public tracking and access rules, all through the gateway | start the stack with `platform/compose/start.sh`, then `SWIFTDROP_LIVE_E2E=1 python3 -m pytest tests/e2e/live -q` |

The live journeys are the only suite that exercises the real services, Keycloak, Kafka and the gateway together.
They are skipped unless `SWIFTDROP_LIVE_E2E=1` is set. They create throwaway users with random passwords, delete
them afterwards, and restore every Keycloak setting they change. They only accept loopback URLs.

Read `AGENTS.md` at the repository root before adding tests: report only tests you actually ran.
