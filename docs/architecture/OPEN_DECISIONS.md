# Decisions to confirm before implementation/submission

Use one issue/PR or an ADR for each decision; record approver/date. Do not treat these as unresolved implementation bugs or silently fill gaps.

| Priority | Decision | Suggested owner/reviewer | Current state |
|---|---|---|---|
| High | Lecturer approval for 9 vs printed 10 members, final Nov 1 deliverable scope/year | Entire team / course staff | **Open** |
| High | Team signoff of named ownership and ninth member's cross-module slices | All 9 | **Proposed** |
| High | Identity realm/client settings, Google OAuth provider consent app, logout/reload/session/refresh behavior | Platform + Customer pair | **Open** |
| High | Controlled courier/dispatcher/admin provisioning; pending/suspended merchant behavior | Platform + Merchant/Dispatcher pairs | **Open** |
| High | Freeze initial REST routes and auth codes; choose developer port registry | Owners + Platform | **Draft** |
| Medium | Quote expiry/currency/zone and weight bands | Customer + Dispatcher pairs | **Open** |
| Medium | Failed-before-pickup and required delivery proof/recipient evidence | Courier + Dispatcher pairs | **Open** |
| Medium | Merchant MVP uses API key (proposed) vs OAuth client credentials (assignment permits either) | Merchant + Platform | **Proposed API key** |
| Medium | gRPC packages, field compatibility and service authentication | All service owners + Platform | **Draft** |
| Medium | Kafka event schema, topic/partition/retry/DLT handling, approved recipient lookup | Courier/Delivery + Merchant/Platform | **Draft** |
| Medium | SSE reconnect and multi-replica fanout mechanism | Customer/Courier + Platform | **Design target** |
| Later | Retention policies, failure/backoff limits, receipt object storage, K8s/AI bonus acceptance | Relevant owners | **Later** |

See also [decision records](adr/README.md). Even once approved, contract changes should follow review rules.
