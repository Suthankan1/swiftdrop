# Decisions to confirm before implementation/submission

Use one issue/PR or an ADR for each decision; record approver/date. Do not treat these as unresolved implementation bugs or silently fill gaps.

See the consolidated [Runtime Decision Register](RUNTIME_DECISION_REGISTER.md) and [Service Interface Matrix](../contracts/SERVICE_INTERFACE_MATRIX.md) frozen under Issue [#88 (N10-P01)](https://github.com/Suthankan1/swiftdrop/issues/88).

| Priority | Decision | Suggested owner/reviewer | Current state |
|---|---|---|---|
| High | Lecturer approval for 9 vs printed 10 members, final Nov 1 deliverable scope/year | Entire team / course staff | **Open** |
| High | Team signoff of named ownership and Platform lead (Suthankan1) cross-module slices | All 9 members | **Proposed (Refs #1)** |
| High | Pinned toolchain baseline (Java 21 LTS, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Maven, React/Vite) | Platform (Suthankan1) + All owners | **Frozen contract ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md))** |
| High | Identity realm/client settings, Google OAuth provider consent app, logout/reload/session/refresh behavior | Platform (Suthankan1) + Customer pair | **Verified baseline ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md), L03=PASS)** |
| High | Controlled courier/dispatcher/admin provisioning; pending/suspended merchant behavior | Platform (Suthankan1) + Merchant/Dispatcher pairs | **Verified baseline ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md), PR #80, #86)** |
| High | Freeze initial REST routes (`GET /api/v1/users/me`), auth codes, and developer port registry | Platform (Suthankan1) + Service Owners | **Verified baseline ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md), PR #45, #76)** |
| High | Email OTP verification strategy: retain native links & TOTP vs custom SPI | Platform (Suthankan1) + Independent QA (Vibhushan) | **Deferred custom SPI per [ADR-0009](adr/ADR-0009.md), Refs #47** |
| High | Quote currency (LKR), integer minor units, 15m expiry, weight/size validation limits | Customer + Dispatcher pairs | **Frozen in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-PRC-01 to 08); contracts in [contracts/rest/](../../contracts/rest/openapi-pricing.yaml)** |
| High | Allowed `FAILED` stages, pre-pickup cancellation policy, and mandatory failure reason codes | Courier + Dispatcher pairs; HarithKeshan review | **Frozen in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-DEL-01 to 06); contracts in [contracts/rest/](../../contracts/rest/openapi-delivery.yaml)** |
| Medium | Proof requirement by outcome (`DELIVERED`/`FAILED`), accepted evidence types/combinations, and private storage | Courier + Dispatcher pairs; Platform proof-adapter | **Frozen in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-DEL-02); contracts in [contracts/rest/](../../contracts/rest/openapi-delivery.yaml); handoff to #95** |
| High | Merchant machine auth: Scoped API keys (`swd_live_`) vs OAuth client credentials | Merchant (Thuvarahan) + Platform (Suthankan1) | **APPROVED per [ADR-0010](adr/ADR-0010-merchant-credential-lifecycle.md) & [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-MER-01 to 05); Implemented in #22** |
| High | gRPC packages (`com.swiftdrop.*.v1`), field numbers, minor units, and actor context delegation | All service owners + Platform | **Spec frozen in [contracts/grpc/README.md](../../contracts/grpc/README.md); handoff to #92 & #100** |
| High | Kafka event schema (`parcel.status.changed.v1`), topic (`parcel.lifecycle.v1`), partition key (`parcelId`), inbox deduplication | Courier/Delivery + Merchant/Platform | **Frozen in [contracts/events/](../../contracts/events/); schema and example validated; handoff to #96** |
| High | SSE reconnect, keepalive (:keepalive 15s), and multi-replica fanout (`tracking.<pod-id>`) | Customer/Courier + Platform | **Frozen in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-SSE-01 to 03)** |
| High | Assignment stages/capacity, direct assignment model, single-active-assignment constraint, reassignment | Dispatcher + Courier/Delivery | **Frozen in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-ASG-01 to 07); partial unique index agreed** |
| High | Dispatcher operational scopes and explicit Admin delegation/override | Dispatcher/Admin + Platform + Accounts/Merchant owners | **Preserved default role separation; open operational scopes in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md)** |
| High | Assignment audit versus status history, version/concurrency semantics | Delivery Customer/Courier/Dispatcher owners + Platform | **Frozen: separated in `delivery_assignments`; no fake status events** |
| Medium | Assignment notification requirement | Dispatcher/Courier + Communications/Merchant + Platform | **Proposed: deferred assignment push notification to avoid fake status event (DA-04)** |
| High | Webhook retry limits (5 attempts), backoff ceiling (6h), HMAC-SHA256 (`X-SwiftDrop-Signature`), HTTPS enforcement | Merchant Pair + Platform | **Frozen in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-COM-01 to 05); contracts in [contracts/rest/](../../contracts/rest/openapi-communications.yaml)** |
| Medium | Data retention policies (7yr parcels, 1yr assignments, 30d outbox/inbox/webhook logs, 90d proof photos) | Relevant owners | **Proposed in [RUNTIME_DECISION_REGISTER.md](RUNTIME_DECISION_REGISTER.md) (DEC-RET-01)** |
| High | SwiftDrop November 10 Hosting Target & Azure Resource Allowlist | Platform (Suthankan1) + Independent Reviewers (Harithkeshan, Vibhushan) | **Frozen: Strict $0 local k3d/K3s release default; initial Azure allowlist EMPTY per [ADR-0011](adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md) (Refs #172)** |
| High | $0 HTTPS & Public-Access Feasibility with Local Fallback | Platform (Suthankan1) + Independent Reviewers (Harithkeshan, EricPraveen, Vibhushan) | **Frozen: Verified LOCAL-ONLY HTTPS on loopback (:18443); public Internet access REJECTED/NOT APPROVED per [HTTPS_PUBLIC_ACCESS_FEASIBILITY.md](../deployment/HTTPS_PUBLIC_ACCESS_FEASIBILITY.md) (Refs #176)** |

See also [decision records](adr/README.md). Even once approved, contract changes should follow review rules.
