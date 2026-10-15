# Nine-member team allocation (proposed for signoff)

This replaces the previous design package's generic M1–M9 developer-role allocation. It reflects the names and pairs **the group explicitly provided**, with **Suthankan1** leading Platform, Security, and Cross-Module Integration. Named individuals' frontend/backend specialization was **not established**, so pairs must choose the internal split themselves. See [Login Integration Contract](../contracts/LOGIN_CONTRACT.md) for the frozen November 1 coordination matrix.

| Team | Members | Primary feature ownership | Integration / cross-review |
|---|---|---|---|
| Customer | **Gajanthan, Sinthuha** | Customer UI + backend flows: signup/profile UX, quote/booking, customer tracking/history | Coordinate receipt interface, pricing API, SSE and auth with Platform |
| Merchant | **Eric, Thuvarahan** | Merchant registration/approval flow, scoped order API, dashboard, webhook configuration UI/contract | Coordinate webhook engine with Platform and Communications owning service |
| Courier | **Abhinaya, Vibhushan** | Assigned-deliveries UI, status changes, notes, failed reasons, proof capture/UI and metadata | Coordinate private proof storage adapter with Platform; Delivery assignment rules with Dispatcher pair |
| Dispatcher/Admin | **Pushmitha, HarithKeshan** | Unassigned queue, assignments/workload, zone/pricing admin, approval/report dashboards | Coordinate GraphQL stats query with Platform; assignment rules with Courier pair |
| Platform & cross-module | **Suthankan1** | Identity (Keycloak + Google OIDC), server JWT/auth conventions, Gateway, Compose starter/CI conventions, shared contract coordination; **four bounded extra slices below** | Review, **not own**, other pairs' complete features |

**Service boundaries cut across actor pairs.** Delivery Operations is one service built by several feature owners; Accounts/Pricing/Communications similarly have features owned by pairs. Avoid one service per actor and avoid every pair creating a competing "delivery-service". Use feature packages inside the correct **service-owned** app; agree PR/code ownership before adding files. Platform supports shared infrastructure; it is not the implicit owner of all microservice implementation.

## Suthankan1 (Platform lead) — explicitly bounded extra features (after Nov 1 login)

| Origin | Platform lead's small owned slice | Still owned by pair | Done boundary |
|---|---|---|---|
| Customer | **PDF delivery receipt generation backend** within Delivery | Customer flow/UI, parcel/booking/history, receipt download wiring and end-to-end UX | Authorized service adapter renders receipt from existing accepted delivery data; tests |
| Merchant | **Webhook delivery + retry worker/engine** within Communications | Merchant registration/order API/dashboard and webhook configuration contracts | Idempotent durable job, HMAC HTTPS delivery, bounded retry + failure status; tests; agree event/recipient contract first |
| Courier | **Private proof-of-delivery object storage adapter** within Delivery | Courier status/state rules, proof metadata and capture UI | Secure store/retrieve adapter, size/type/auth tests; no public object URLs |
| Dispatcher | **`deliveryStats` read-only GraphQL resolver** in the one GraphQL Query API | Dispatcher assignment/workload core logic, report screens and other GraphQL dashboard queries | Aggregate authorized Delivery read data, pagination/filter limits, tests |

**Workload guard:** webhook delivery engine is substantial, not necessarily a "small" task. Treat it as a shared Communications integration with Eric/Thuvarahan and/or rebalance it if platform workload becomes excessive. Do **not** assume these four features must finish by Nov 1. No one should own Kafka infrastructure, complete auth, all services, all dashboards, CI and Kubernetes alone. Review effort with actual issue estimates, update owner agreement and record signoff.

## Proposed Nov 1 responsibilities, independently reviewable

- Platform: Keycloak realm + local/Google PKCE integration plan, gateway routing and protected API JWT proof, reproducible login environment guidelines, auth-negative test coordination, integrated report architecture signoff.
- Customer pair: React login/logout/signup UI and callback/error UX, current-user/role route experience in collaboration with Platform.
- Merchant pair: merchant signup pending state / authentication and approval model in docs; optional thin approved-protected login page if time.
- Courier pair: courier-only page and one authorization denial proof; don't implement status workflow prematurely.
- Dispatcher pair: dispatcher/admin role-specific page and guarded access; document approval role provisioning.
- All nine: review own use-case/flow/ERD diagrams, cross-review one other part, link actual PRs/evidence, confirm group size with lecturer.

**No branch may silently change another team's feature or the event/Protobuf contracts.** Cross-cutting contributors open small PRs into the module owner’s service; module owner reviews and retains acceptance responsibility.

See [module handoff protocol](MODULE_HANDOFF.md) and [workload tracker](CONTRIBUTION_LOG.csv). Any final individual backend/frontend assignments need a group decision.
