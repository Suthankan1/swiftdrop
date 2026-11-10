# SwiftDrop test report and coverage summary

Run date: 10 November 2026 · Branch: `fix/reaudit-findings` (from `main` at `1389708`) · Machine: macOS, JDK 25, Node 24 (container), Docker for Testcontainers.

Every figure below comes from a real run. Modules this branch did not change were run earlier the same day on `main` during the re-audit; they are marked as such.

## Results

| Suite | Unit tests | Integration tests | Result | Line coverage | Branch coverage |
|---|---:|---:|---|---:|---:|
| Shared security adapter | 59 | – | all pass | 80.8% | 55.9% |
| Accounts service | 327 | 28 | all pass | 79.9% | 60.8% |
| Delivery service | 504 | 77 (1 skipped) | all pass ¹ | 79.2% | 60.6% |
| GraphQL query service | 251 | – | all pass | 84.7% | 67.1% |
| AI assistant (MCP + Gemini) | 16 | – | all pass | 86.9% | 66.1% |
| Pricing service ² | 171 | 20 | all pass | 79–89% range | 56–69% range |
| Communications service ² | 365 | 27 | all pass | 79–89% range | 56–69% range |
| API gateway ² | 99 | – | all pass | 79–89% range | 56–69% range |
| Frontend (Vitest) | 1,241 | – | all pass | 83.45% lines, 81.09% statements | – |
| Python policy and contract suites ³ | 619 | – | all pass, 8 skipped | – | – |

¹ The first full run had one failure: an integration test pinned the Flyway migration count at 9, and this branch adds `V10`. After updating that assertion, `DeliveryPersistencePostgresIT` (6) and `BookingEntryPointRecoveryPostgresIT` (3) were re-run and pass.
² Not changed on this branch; figures are from the same-day run on `main`. Coverage for these was reported as the range across services.
³ The suites CI runs (`platform/identity/tests`, `tests/azure-k3s`, `tests/ci`, `tests/container-images`, `tests/contracts`, `tests/hosting-feasibility`, `tests/kubernetes`, `tests/platform-compose`, `tests/terraform-policy`) plus `tests/e2e`. The skipped tests include the opt-in live journeys, which need a running stack.

Frontend type check (`tsc -b`) and lint (`eslint src`) are clean.

## Live system checks (re-audit, same day, isolated stack)

- Live journeys 2/2: customer books and tracks, dispatcher assigns, courier delivers with photo proof, PDF receipt, public tracking without addresses.
- Merchant registration → admin approval → API key → order with `X-API-Key`; invalid key rejected.
- Webhook retries: a 503 endpoint received 4 attempts in 75 s; a 200 endpoint succeeded; 4xx fails fast by design.
- Email notifications delivered (Mailpit) without address or phone.
- Courier isolation: 403 on another courier's parcel; role rules hold across roles.
- No personal data in service logs; no browser console errors.

## What the tests cover (brief mapping)

- **Integration and event flow:** Testcontainers PostgreSQL and Kafka integration tests in accounts, delivery, pricing and communications (outbox publish, idempotent booking, lost-response replay, status history uniqueness).
- **Authorization rules:** gateway security and routing tests, gRPC delegated-actor tests per role, HTTP security tests per controller, frontend route guards.
- **Coverage:** JaCoCo per Java module, Vitest V8 coverage for the frontend.

## How to reproduce

```bash
(cd shared/proto-contracts && ./mvnw -q clean install -DskipTests)
(cd shared/security-adapter && ./mvnw -q clean install)
(cd services/accounts-service && ./mvnw -q clean verify)   # same for each service and ai-assistant
cd frontend && pnpm install --frozen-lockfile && pnpm exec vitest run --coverage
python3 -m pytest platform/identity/tests tests/azure-k3s tests/ci tests/container-images tests/contracts \
  tests/hosting-feasibility tests/kubernetes tests/platform-compose tests/terraform-policy tests/e2e
```

Use `clean install` for the shared modules: an IDE can leave half-compiled classes in `target/`, and packaging those breaks every gRPC test downstream.

Do not run `pytest` from the repository root. It also collects `platform/dependencies/tests/test_dependencies_smoke.py`, an operator script that restarts the local `swiftdrop-kafka` and `swiftdrop-business-postgres` containers.
