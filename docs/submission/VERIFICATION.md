# SwiftDrop Final Release Verification

Verification date: 13 November 2026. Release source: [test at 9768726](https://github.com/Suthankan1/swiftdrop/commit/97687263a69655b841dbd659cd68ae34dcfaeb84).

## GitHub Actions

[CI run 43369431149](https://github.com/Suthankan1/swiftdrop/actions/runs/43369431149) completed successfully. All 28 reported jobs succeeded, including the required gate. The counts below come from the run logs. Integration totals include the skipped case. The skipped Delivery test requires the opt-in real MinIO provider.

| Java module | Unit passed | Integration discovered | Integration passed | Integration skipped |
|---|---:|---:|---:|---:|
| Shared Protobuf contracts | 28 | — | — | — |
| Shared security adapter | 59 | — | — | — |
| Accounts | 327 | 28 | 28 | 0 |
| Pricing | 171 | 20 | 20 | 0 |
| Delivery | 504 | 77 | 76 | 1 |
| Communications | 365 | 27 | 27 | 0 |
| GraphQL Query API | 251 | — | — | — |
| API Gateway | 99 | — | — | — |
| AI Assistant | 16 | — | — | — |
| Total | 1,820 | 152 | 151 | 1 |

GitHub also recorded 1,241 passing frontend tests across 75 files. The frontend build, identity tests, Compose checks, contract checks, policy checks and scans completed successfully. The published runtime images for this commit target Linux AMD64.

## Local verification

The frontend lint, TypeScript check, Vitest run and production build passed against the release source. Vitest passed 1,241 tests across 75 files. V8 measured 83.41% line coverage, 79.53% branch coverage, 81.11% statement coverage and 82.02% function coverage. Local execution used the desktop checkout's installed frontend dependencies.

The local Python run from a clean release checkout recorded **725 passed, 8 skipped and 1 failed**. The failure was local Terraform static validation because the AzureRM 4.81.0 provider was missing from the checkout cache. The corresponding GitHub Terraform and Azure checks succeeded. The eight skips include opt-in live journeys.

The local Java integration suite was not rerun for this release. The earlier Docker Desktop Ryuk issue is not reflected in the GitHub Accounts run, which passed all 28 Accounts integrations.

The historical test report in [docs/testing/TEST_REPORT.md](../testing/TEST_REPORT.md) records an earlier re-audit. The linked GitHub run is the evidence for the final release's Java and integration counts.
