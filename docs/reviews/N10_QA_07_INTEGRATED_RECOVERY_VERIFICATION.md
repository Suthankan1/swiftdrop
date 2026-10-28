# N10-QA-07 Integrated Recovery Verification

- **Issue:** #164 — Prove integrated outage, crash and duplicate-event recovery
- **Owner:** @abhi190503
- **Base:** `test` at `d2e2b49b469897776201eb636414d805e18099fe`
- **Branch:** `tests/n10-qa07-integrated-recovery`
- **Date:** 2026-10-28 (Asia/Colombo)
- **Conclusion:** **IMPLEMENTED WITH BLOCKERS — NOT YET FULLY ACCEPTED**

The requested `test/n10-qa07-integrated-recovery` name cannot exist in this repository because a local branch named `test` already occupies that Git ref prefix. The non-conflicting `tests/...` branch was used. Tests ran on Java 21.0.12.1, Maven Wrapper 3.10.0, Python 3.13.5, Docker Desktop 29.8.1, PostgreSQL 16 Testcontainers, and Spring embedded Kafka 4.2.1. No shared Docker service was stopped or reconfigured.

## Scenario matrix

| ID | Failure trigger and real components | Observed invariant / recovery | Duration / threshold | Result | Evidence |
|---|---|---|---|:---:|---|
| A1 | Test-scoped Kafka producer fails after parcel and outbox commit; real PostgreSQL, real relay, embedded Kafka | Parcel remained committed; target outbox changed to `FAILED`, attempt 1 and bounded next attempt; unrelated future event remained untouched; retry published the same parsed JSON with attempt 2; failure metric incremented | 3,336 ms / proposed test bound 20,000 ms; no approved release budget located | **PASS** | `KafkaOutboxRelayIT.testBrokerOutageRecoveryPreservesCanonicalTransaction` |
| B1 | Same canonical event delivered twice through real Kafka and PostgreSQL inbox | One inbox receipt and one notification job | Existing bounded 10 s polling | **PASS** | `CommunicationsKafkaConsumerIT.testDuplicateDeliveryHandledSafely` |
| B2 | Same event processed by notification and webhook consumer groups | Independent group receipts and original event identity retained | Existing bounded 10 s polling | **PASS** | `WebhookKafkaConsumerIT` (7/7 in isolated rerun) |
| C1 | Acknowledgement throws after the real inbox/job transaction commits; same record is redelivered | Durable job survives; redelivery acknowledges without repeating authorization or creating another job | 176 ms / proposed test bound 5,000 ms; no approved release budget located | **PASS** | `CommunicationsKafkaConsumerIT.testCrashAfterCommitBeforeAcknowledgementRecoversIdempotently` |
| D1 | Accounts eligibility provider throws `DEADLINE_EXCEEDED / UNAVAILABLE`; real Delivery PostgreSQL | Assignment transaction rolls back and original active assignment remains | Functional bounded test; IF-GRPC-01 approved deadline is 2,000 ms | **PASS** | `AssignmentLifecyclePostgresIT.testEligibilityProviderFailureRollsBackCleanly` |
| D2 | Pricing gRPC returns `DEADLINE_EXCEEDED`, `UNAVAILABLE`, or `PERMISSION_DENIED` | Adapter fails closed with zero accepted amount and no unauthorized snapshot | Functional test only; approved IF-GRPC-04 deadline is 2,000 ms | **PASS / BLOCKED** | `GrpcBookingQuoteProviderTest` 6/6 pass; runtime default is currently 5,000 ms, inconsistent with the approved interface matrix |
| D3 | Pricing unavailable before booking transaction, then available on retry; real Delivery PostgreSQL | First call leaves no parcel/idempotency residue; same request then creates one parcel, history row, and outbox event | Deterministic immediate fixture; release recovery budget unapproved | **PASS** | `BookingEntryPointRecoveryPostgresIT.pricingOutageRollsBackCustomerBooking` |
| E1/E2 | Same key replay and different-key same-quote contention; real PostgreSQL | Customer path produces one canonical parcel, history, snapshot, and outbox sequence; loser receives deterministic conflict | Existing transaction-bound tests | **PASS** | `BookingPostgresIT` 8/8; Issue #140 contract regressions |
| E3 | Provider failure before commit | No half-created booking, idempotency residue, or invalid outbox event | Deterministic transaction boundary | **PASS** | Booking recovery test above |
| E4 | Merchant response lost after commit, then same-key retry; real PostgreSQL | Actual result is `QUOTE_ALREADY_CONSUMED`, not canonical replay | Not applicable | **BLOCKED** | Disabled regression test documents principal mismatch: persist uses `merchant:<uuid>` while replay queries `<uuid>` |
| E5 | Customer wins, then merchant attempts same quote; real PostgreSQL | Exactly one parcel/history/outbox; merchant loses deterministically | Functional transaction test | **PASS** | `customerAndMerchantEntryPointsShareSingleUseQuoteInvariant` |
| F1 | HTTP 200, 500, 429, timeout, security failure, retry, jitter, exhaustion, stalled lease and authorized replay | Attempt history, original event identity, five-attempt ceiling, terminal visibility, HMAC and SSRF policies are preserved | Deterministic clock/backoff tests; DEC-COM-03 remains proposed, not approved | **PASS / BLOCKED** | Webhook suites 47/47 pass when Kafka IT reruns in isolation; no real external HTTPS receiver was available |
| G1 | SSE delivery, disconnect/cleanup, replica restart and Kafka fanout | Version monotonicity, cleanup counters, authorization, restart, and multi-replica fanout pass; approved recovery is authoritative REST state rather than replaying every missed event | Existing bounded integration polling | **PASS** | Assignment/provider/SSE focused command: 36/36 before the added UNAVAILABLE case; final provider test 6/6 |
| H1 | Fault controls audit | All new failures are test-local mocks/acknowledgements; no endpoint, profile, production configuration, or production source was added | Not applicable | **PASS** | Diff inspection and `git diff --check` |

## Invariants and observability

- No lost committed parcel or outbox event, no recovery-created parcel, and no modification of the unrelated outbox fixture.
- No duplicate Communications inbox, notification job, or webhook job for the same consumer/event identity.
- No half-created booking, phantom idempotency record, invalid price snapshot, or double active assignment in the executed provider-failure paths.
- The outbox failure counter increased by exactly one during injection. Persistent outbox status, attempts, next-attempt time, and final publication supplied before/failure/recovery evidence.
- Webhook attempt rows, retry/exhaustion state, and SSE connection/version behavior are covered by existing focused suites. No identifier was introduced as a metric label.
- Test logs and this report use synthetic/redacted values. No token, authorization header, signing key, customer address, or private endpoint is evidence.

## Commands and actual results

`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` was set only to use the Windows trust store; TLS verification was not disabled.

| Command (from component directory) | Actual result |
|---|---|
| `mvnw.cmd -Dtest=KafkaOutboxRelayIT test` | 4 run, 4 passed |
| `mvnw.cmd -Dtest=CommunicationsKafkaConsumerIT test` | 3 run, 3 passed |
| `mvnw.cmd -Dtest=BookingEntryPointRecoveryPostgresIT,BookingPostgresIT,GrpcBookingQuoteProviderTest test` | 17 run, 16 passed, 1 skipped blocker |
| Delivery assignment/provider/SSE focused suite | 36 run, 36 passed; the provider class subsequently ran 6/6 after adding `UNAVAILABLE` coverage |
| Communications webhook focused suite | 47 unique tests; combined run had one 10-second Kafka group-readiness failure, then `WebhookKafkaConsumerIT` passed 7/7 alone; the other 40 reports passed |
| `python -X utf8 tests/contracts/run_contract_regressions.py --no-java-grpc` with `PYTHONUTF8=1` | 94 passed, 0 failed, 0 errors |
| `shared/proto-contracts/mvnw.cmd -B test` | 28 passed, 0 failed, 0 skipped |
| `git diff --check` | Passed |

The canonical no-argument contract runner was attempted first and failed for two Windows-runner reasons: child Python output used CP1252 for check-mark characters, and the runner executed Unix `mvnw` directly (`WinError 193`). Equivalent Python gates and Java Protobuf tests were then run separately and passed. This report does not reinterpret the failed wrapper invocation as a passing command.

## Outstanding limitations and owner handoff

1. **Delivery booking owner — merchant idempotency defect:** `BookingService` persists the principal as `merchant:<merchantId>`, while `BookingRepository.replayMerchant` looks up `merchantId.toString()`. The response-lost replay therefore returns `QUOTE_ALREADY_CONSUMED`. Production logic was not changed from this QA branch.
2. **Delivery/Pricing owners — deadline mismatch:** `SERVICE_INTERFACE_MATRIX.md` freezes IF-GRPC-04 at 2,000 ms, but `PricingGrpcClientConfig` and `GrpcBookingQuoteProvider` default to 5,000 ms. Approval and an owner fix are required before the timing criterion can pass.
3. **Communications owners — webhook policy/budget:** DEC-COM-03 is still `PROPOSED — AWAITING REVIEW`. Tests prove the implemented bounded behavior, but cannot call it an approved release budget.
4. **Platform/Communications owners — external receiver:** no operator-provided public HTTPS test receiver was available. A localhost receiver would be rejected correctly by the production SSRF policy; that security was not weakened. HTTP transport behavior is covered with the existing isolated client/pipeline harness, so the real synthetic external receiver criterion remains blocked.
5. **All recovery owners — budgets:** only the interface-matrix 2,000 ms gRPC deadlines are approved. The 20-second outbox and 5-second acknowledgement values are explicitly proposed test bounds, not release requirements.
6. **Test infrastructure:** the focused webhook aggregate run showed one readiness timeout under simultaneous embedded-Kafka suites; its isolated rerun passed. This should remain visible as a flake signal rather than being hidden by widening the assertion.

## Security and change scope

Only service test sources and this evidence document change. No production business logic, API/schema, workflow, Docker configuration, secrets, `.env`, generated output, or CI workflow changes are included. No destructive Docker, database, Kafka, Git, or filesystem operation was performed.

One next small task: the Delivery booking owner should correct the merchant replay principal mismatch and enable the blocked regression test; QA can then rerun E4 without broadening this PR.
