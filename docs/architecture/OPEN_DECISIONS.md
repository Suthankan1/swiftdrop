# Decisions to confirm before implementation/submission

Use one issue/PR or an ADR for each decision; record approver/date. Do not treat these as unresolved implementation bugs or silently fill gaps.

| Priority | Decision | Suggested owner/reviewer | Current state |
|---|---|---|---|
| High | Lecturer approval for 9 vs printed 10 members, final Nov 1 deliverable scope/year | Entire team / course staff | **Open** |
| High | Team signoff of named ownership and Platform lead (Suthankan1) cross-module slices | All 9 members | **Proposed (Refs #1)** |
| High | Pinned toolchain baseline (Java 21 LTS, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Maven, React/Vite) | Platform (Suthankan1) + All owners | **Proposed contract ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md))** |
| High | Identity realm/client settings, Google OAuth provider consent app, logout/reload/session/refresh behavior | Platform (Suthankan1) + Customer pair | **Proposed contract ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md))** |
| High | Controlled courier/dispatcher/admin provisioning; pending/suspended merchant behavior | Platform (Suthankan1) + Merchant/Dispatcher pairs | **Proposed contract ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md))** |
| High | Freeze initial REST routes (`GET /api/v1/users/me`), auth codes, and developer port registry | Platform (Suthankan1) + Service Owners | **Proposed contract ([LOGIN_CONTRACT.md](../contracts/LOGIN_CONTRACT.md))** |
| High | Email OTP verification strategy: retain native links & TOTP vs custom SPI | Platform (Suthankan1) + Independent QA (Vibhushan) | **Proposed ([ADR-0009](adr/ADR-0009.md), Refs #47)** |
| Medium | Quote expiry/currency/zone and weight bands | Customer + Dispatcher pairs | **Open** |
| Medium | Allowed `FAILED` stages, including failed-before-pickup, and stage-specific failure-reason/evidence rules | Courier + Dispatcher pairs; HarithKeshan review | **Open — see [Delivery Design Review](ISSUE_12_DELIVERY_DESIGN_REVIEW.md#9-register-of-open-architectural-decisions) (DEC-DEL-01)** |
| Medium | Proof requirement by outcome (`DELIVERED`/`FAILED`), accepted evidence types/combinations, and recipient/signature policy | Courier + Dispatcher pairs; business/lecturer confirmation | **Open** |
| Medium | Proof file limits/validation, authorized retrieval, retention/deletion/replacement, and privacy audit policy | Courier + Platform proof-adapter owner; security/privacy review | **Open** |
| Medium | Merchant MVP uses API key (proposed) vs OAuth client credentials (assignment permits either) | Merchant + Platform | **Proposed API key** |
| Medium | gRPC packages, field compatibility and service authentication | All service owners + Platform | **Draft** |
| Medium | Kafka event schema, topic/partition/retry/DLT handling, approved recipient lookup | Courier/Delivery + Merchant/Platform | **Draft** |
| Medium | SSE reconnect and multi-replica fanout mechanism | Customer/Courier + Platform | **Design target** |
| Later | Retention policies, failure/backoff limits, receipt object storage, K8s/AI bonus acceptance | Relevant owners | **Later** |

See also [decision records](adr/README.md). Even once approved, contract changes should follow review rules.
