# Nine-member team allocation (proposed for signoff)

This replaces the previous design package's generic M1–M9 developer-role allocation. It reflects the names and pairs **the group explicitly provided**, with **Suthankan1** leading Platform, Security, and Cross-Module Integration. The team leader's latest explicit decision is that **Suthankan1 owns all authentication implementation (React SPA identity UI/OIDC session flow, Keycloak, Gateway JWT, Accounts shared JWT/security integration and primary auth tests)**. Functional team members retain their business models, their role-specific landing screens and independent security review. Individual non-auth frontend/backend splits remain subject to pair signoff. See [Login Integration Contract](../contracts/LOGIN_CONTRACT.md) for the frozen November 1 coordination matrix.

| Team | Members | Primary feature ownership | Integration / cross-review |
|---|---|---|---|
| Customer | **Gajanthan, Sinthuha** | Customer business UI + backend flows: profile/landing UX, quote/booking, tracking/history (but **not** identity login/signup/PKCE implementation) | Coordinate SPA shell/customer screens with Platform-owned shared authentication |
| Merchant | **Eric, Thuvarahan** | Merchant registration/approval flow, scoped order API, dashboard, webhook configuration UI/contract | Coordinate webhook engine with Platform and Communications owning service |
| Courier | **Abhinaya, Vibhushan** | Assigned-deliveries UI, status changes, notes, failed reasons, proof capture/UI and metadata | Coordinate private proof storage adapter with Platform; Delivery assignment rules with Dispatcher pair |
| Dispatcher/Admin | **Pushmitha, HarithKeshan** | Unassigned queue, assignments/workload, zone/pricing admin, approval/report dashboards | Coordinate GraphQL stats query with Platform; assignment rules with Courier pair |
| Platform & cross-module | **Suthankan1** | **All authentication implementation:** Keycloak/Google OIDC, shared React login/signup/callback/logout/token lifecycle, Gateway JWT, Accounts JWT/resource-server security, primary auth tests; local platform setup and contract coordination; **four deferred extra slices below** | Review, **not own**, other pairs' complete features |

**Service boundaries cut across actor pairs.** Delivery Operations is one service built by several feature owners; Accounts/Pricing/Communications similarly have features owned by pairs. Avoid one service per actor and avoid every pair creating a competing "delivery-service". Use feature packages inside the correct **service-owned** app; agree PR/code ownership before adding files. Platform supports shared infrastructure; it is not the implicit owner of all microservice implementation.

## Suthankan1 (Platform lead) — explicitly bounded extra features (after Nov 1 login)

| Origin | Platform lead's small owned slice | Still owned by pair | Done boundary |
|---|---|---|---|
| Customer | **PDF delivery receipt generation backend** within Delivery | Customer flow/UI, parcel/booking/history, receipt download wiring and end-to-end UX | Authorized service adapter renders receipt from existing accepted delivery data; tests |
| Merchant | **Webhook delivery + retry worker/engine** within Communications | Merchant registration/order API/dashboard and webhook configuration contracts | Idempotent durable job, HMAC HTTPS delivery, bounded retry + failure status; tests; agree event/recipient contract first |
| Courier | **Private proof-of-delivery object storage adapter** within Delivery | Courier status/state rules, proof metadata and capture UI | Secure store/retrieve adapter, size/type/auth tests; no public object URLs |
| Dispatcher | **`deliveryStats` read-only GraphQL resolver** in the one GraphQL Query API | Dispatcher assignment/workload core logic, report screens and other GraphQL dashboard queries | Aggregate authorized Delivery read data, pagination/filter limits, tests |

**Workload guard:** The team lead explicitly chose full authentication ownership, a larger workload than the original platform-only allocation. Defer all four cross-module extras until after Nov 1 and rebalance/reassign them with the respective pairs if needed (webhook engine especially). Independent security QA by Vibhushan does not transfer implementation ownership. Do not also assign Kafka, all services, all dashboards, CI and Kubernetes implementation to the same developer. Review effort and final signoff with all pairs.

## Proposed Nov 1 responsibilities, independently reviewable

- Platform (Suthankan1): **Complete authentication:** real local/Google Keycloak login, React identity signup/login/callback/logout/OIDC session UI and reusable auth provider, Gateway JWT, Accounts independent JWT/role security, primary auth/security tests, reproducible local integration. See [#2](https://github.com/Suthankan1/devarom/issues/2), [#3](https://github.com/Suthankan1/devarom/issues/3), [#36](https://github.com/Suthankan1/devarom/issues/36) and [#37](https://github.com/Suthankan1/devarom/issues/37).
- Customer pair: Gajanthan owns the one React SPA shell and customer *business profile/landing* screens [#6](https://github.com/Suthankan1/devarom/issues/6). Sinthuha owns Accounts business profile, owned PostgreSQL schema, DTO and domain ownership lookups [#5](https://github.com/Suthankan1/devarom/issues/5). They consume Platform auth; neither implements OIDC client, login/password management or shared JWT filters.
- Merchant pair: merchant onboarding and pending/approved business status + merchant role landing page consuming Platform auth; no separate login client.
- Courier pair: courier-only *business* landing page; Vibhushan independently verifies cross-role and resource-owner denials, while Platform owns security implementation.
- Dispatcher pair: dispatcher/admin business landing views and staff profile read models; Platform owns Keycloak role provisioning and shared JWT authorization enforcement.
- All nine: review own use-case/flow/ERD diagrams, cross-review one other part, link actual PRs/evidence, confirm group size with lecturer.

**No branch may silently change another team's feature or the event/Protobuf contracts.** Cross-cutting contributors open small PRs into the module owner’s service; module owner reviews and retains acceptance responsibility.

See [module handoff protocol](MODULE_HANDOFF.md) and [workload tracker](CONTRIBUTION_LOG.csv). Any final individual backend/frontend assignments need a group decision.

## Temporary Issue #6 implementation handoff — 2026-10-15

The team lead explicitly reassigned **implementation of [#6](https://github.com/Suthankan1/devarom/issues/6) temporarily to Suthankan1** due to Gajanthan’s unavailability, to keep the November 1 milestone on schedule. **Gajanthan remains the original Customer feature steward** and Customer pair ownership is unchanged. Attribute this scaffold’s implementation to Suthankan1. Authentication stays in #36; Sinthuha retains Accounts business profiles #5. This exception does not transfer any other issue or module.

## Cross-service auth code ownership

- Shared React identity implementation is owned only by [#36](https://github.com/Suthankan1/devarom/issues/36). Gajanthan creates the **single** Vite SPA shell [#6](https://github.com/Suthankan1/devarom/issues/6); coordinate auth route/component ownership before parallel PRs. Eric/Abhinaya/Pushmitha build their own **role landing experiences** consuming the auth provider, not duplicate login code.
- Shared Accounts JWT/security filter/policy is owned only by [#37](https://github.com/Suthankan1/devarom/issues/37); Accounts app and profile DB are created/owned by Sinthuha [#5](https://github.com/Suthankan1/devarom/issues/5). HarithKeshan owns staff read models, Thuvarahan owns merchant approval/status business rules. Do not create another Accounts service.
- Vibhushan retains **independent** security verification [#18](https://github.com/Suthankan1/devarom/issues/18); independent QA is not primary authentication ownership.
- The team must acknowledge this reassignment. These docs and issue scopes are proposed team coordination until the ownership PR is reviewed/merged; don't claim every member already consented.
