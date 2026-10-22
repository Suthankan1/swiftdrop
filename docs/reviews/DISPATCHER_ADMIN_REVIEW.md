# Dispatcher/Admin use cases and assignment review

**Refs:** [Issue #13 / NOV-17](https://github.com/Suthankan1/swiftdrop/issues/13); delivery to [NOV-19 / Issue #19](https://github.com/Suthankan1/swiftdrop/issues/19).

**Owner:** Pushmitha (@pushmitha20), Dispatcher/Admin pair with HarithKeshan (@Harithkeshan).

**Review date:** 2026-10-22 (Asia/Colombo). **State:** review artifact prepared; independent owner approval pending. No stakeholder policy approval is implied.

## 1. Actual issue scope and starting evidence

Issue #13 was open, assigned to `pushmitha20`, with five unchecked acceptance criteria and no comments. Its timeline linked report assembly #19; prerequisite #1 is closed with checked acceptance criteria. Searches of PR titles, bodies and the issue timeline found no existing Dispatcher/Admin review PR. Existing Customer, Courier, Delivery and Communications reviews were reused below.

This is November 1 **report design**, covering the unassigned queue, manual assignment/workload, controlled administration and daily reporting. It implements no operational dashboard, endpoint, migration, role change, algorithm or Issue #16 work. The checked-in login implementation and Accounts merchant-status provider are distinct from the planned Delivery, Pricing, GraphQL, gRPC, Kafka, Communications and SSE business flows. No runtime/login test was rerun for this documentation change. The external report draft and original academic assignment PDF are unavailable here; the repository's assignment summary and editable sources bound this review.

The feature branch starts at `origin/test` **`4c1fba46740b38f5cbc1834025e10c952c5c6fb3`**. At initial fetch, `origin/main` was **`8983788073e4289407e1ebfb67021e394bd0e34c`**, and `main...test` was **1 / 12**. The sole main-only commit is release merge #73; `git diff origin/test...origin/main` has no content changes. No shared-branch synchronization or history rewrite is needed for this review.

Before submission, `test` advanced to **`466b1fbb91f32d83c34a2da38ac6c5e655e224b1`** (Merchant PR #80; `main...test` **1 / 14**). The unpushed review commit was rebased onto it, preserving both Merchant and Dispatcher status/contribution entries. Application changes from #80 are baseline work, outside this PR's diff.

| Actual acceptance criterion | Review evidence | Acceptance state |
|---|---|---|
| Use cases/activity agree with Courier rules and one active assignment per parcel | Sections 3–6; corrected Figures 04/08; Figure 17 comparison | Documented; Courier/Delivery review pending |
| Dispatcher/Admin powers and controlled merchant/staff provisioning explicit | Sections 3–4 and 7; Figure 04 | Documented; detailed scope/override policy DA-02 remains open |
| No actor-per-service, cross-service SQL or assignment-as-status transition | Sections 5–7; Figures 06/14/15/17 | Preserved in this change |
| Reporting uses authorized read-only GraphQL; commands use REST | Sections 3–5 and 7; Figures 04/08 | Explicit target design |
| Reviewed diagrams/text and contribution links delivered to NOV-19 | Section 11; contribution register; PR and issue handoff | Prepared for report-owner review; not final report approval |

## 2. Reviewed sources

- Scope/governance: [AGENTS.md](../../AGENTS.md), [assignment scope](../requirements/ASSIGNMENT_SCOPE.md), [diagram traceability](../requirements/DIAGRAM_TRACEABILITY.md), [ownership](../team/OWNERSHIP.md), [module handoff](../team/MODULE_HANDOFF.md), [November 1 milestone](../milestones/NOV_01.md), [report outline](../milestones/REPORT_OUTLINE.md), [project status](../project/STATUS.md).
- Architecture: [baseline v1.3](../architecture/BASELINE.md), [data ownership](../architecture/DATA_OWNERSHIP.md), [protocol matrix](../architecture/PROTOCOL_MATRIX.md), [ADRs 0001–0008](../architecture/adr/README.md), [open decisions](../architecture/OPEN_DECISIONS.md).
- Contracts/security: [REST](../contracts/REST.md), [GraphQL](../contracts/GRAPHQL.md), [gRPC](../contracts/GRPC.md), [Kafka](../contracts/KAFKA.md), [login contract](../contracts/LOGIN_CONTRACT.md), [authorization matrix](../security/AUTHORIZATION_MATRIX.md), [authentication](../security/AUTHENTICATION.md), [threat review](../security/THREAT_REVIEW.md).
- Domain context: [Delivery](../../services/delivery-service/README.md), [Accounts](../../services/accounts-service/README.md), [Pricing](../../services/pricing-service/README.md), [Communications](../../services/communications-service/README.md); [Customer review](../report/customer_journey_review.md), [Courier review](../milestones/NOV13_COURIER_REVIEW.md), [Delivery review](../architecture/ISSUE_12_DELIVERY_DESIGN_REVIEW.md), [Communications review](NOV_11_COMMUNICATIONS_MERCHANT_REVIEW.md).
- Primary source/preview pairs: **04** [Draw.io](../diagrams/editable/04_dispatcher_admin_use_cases.drawio) / [SVG](../diagrams/rendered/04_dispatcher_admin_use_cases.svg); **08** [Draw.io](../diagrams/editable/08_assignment_activity.drawio) / [SVG](../diagrams/rendered/08_assignment_activity.svg).
- Cross-checked editable sources and matching preview structure in the [diagram index](../diagrams/README.md): Customer/Merchant/Courier use cases **01–03**, architecture **06**, booking **07/10/11**, status/event activity/sequence **09/12**, logical ERDs **14–16**, parcel lifecycle **17**, reliability **18**. Visual export checks apply to modified Figures 04/08, not every unchanged figure.

## 3. Dispatcher use-case coverage

The identifiers below are **existing Figure 04 cell IDs**, not new requirements. Shared preconditions: valid Keycloak JWT, `DISPATCHER` role and authorized operational/resource scope checked by each provider. Missing/invalid JWT is rejected; wrong role/scope must not reveal protected records. Postconditions describe the target design, not implemented behavior.

| Existing use case / actor | Preconditions and success scenario | Alternatives/failures | Postcondition, rule and service responsibility |
|---|---|---|---|
| `u0` Login; Dispatcher and Admin | Controlled staff identity exists; SPA signs in through Keycloak Code + PKCE and reads own Accounts profile | Denied/expired login or wrong-role entry stops protected access; use existing auth flow | Verified identity/session only; no automatic business privilege. Keycloak/Platform owns identity; Accounts owns profile |
| `u1` View unassigned parcels/details; Dispatcher | Authorized GraphQL Query API calls Delivery for scoped queue/details; courier summaries come from Accounts | Empty queue is valid; unavailable/partial reads cannot establish assignment eligibility; filter/page and re-read before command | Read only; Delivery owns parcel/assignment data, Accounts owns eligibility. Simple authorized detail reads may remain REST |
| `u2` Assign eligible courier; Dispatcher | Inspect scoped parcel and courier, manually select, submit proposed REST assignment command; Delivery rechecks authorization, Accounts eligibility, parcel/version and uniqueness before committing assignment/audit | Wrong role/owner, stale version, duplicate/concurrent request, ineligible courier or provider deadline: no new assignment; see Section 6 | One active binding per parcel; lifecycle status unchanged; attributable `assigned_by`/time in Delivery. No automatic courier selection |
| `u3` View couriers/workload; Dispatcher | Composite read joins authorized provider responses in Query API: Accounts eligibility plus Delivery active assignments/workload | Stale/partial workload is advisory; missing eligibility is not permission to assign; capacity rule DA-01 is undecided | Read only; never derive workload from Accounts role membership or cross-service SQL |
| `u7` View daily/success/failure outcomes; Dispatcher and scoped Admin | Query API reads authorized Delivery outcome summaries, with capped filters/pagination and provider-side scope | Wrong scope, excessive query complexity or provider deadline denies/limits the read; partial-failure semantics need contract review | Read only; `deliveryStats` resolver remains Platform's bounded later slice; reporting does not grant cross-tenant access |

**Checklist disposition:** queue, available/eligible courier display, parcel inspection, assignment status and active workload are covered by existing `u1`–`u3` narratives; they do not add standalone use cases. Reassignment is mentioned conditionally in the authorization matrix, but allowed stages, original-assignment closure and exceptional intervention are not approved. Courier rejection, expiry and escalation have no approved actor action or state machine. Delivery exceptions follow the Courier-owned lifecycle/evidence rules in Figure 17; Dispatcher cannot perform Courier status updates by implication. These gaps are questions DA-01/DA-02, not added features.

## 4. Admin use cases and permission separation

Admin entry has the same JWT/session requirements as `u0`. Each operation additionally needs the relevant service's explicit administration permission and resource scope; possession of `ADMIN` does not provide unrestricted business-data access.

| Existing case / permission | Preconditions and main success flow | Alternatives/failures | Postcondition, rule and responsible owner |
|---|---|---|---|
| `u4` Manage users/couriers; Admin only | Authorized admin reviews business profile/eligibility in Accounts; controlled staff role provisioning uses Keycloak under Platform procedures | Self-granted staff role, Dispatcher administration, unauthorized eligibility edit or malformed command is denied; partial provisioning reconciliation needs DA-02 | Business changes belong to Accounts; identity roles/credentials remain Keycloak. No new role-management endpoint or password storage is approved here |
| `u5` Approve/manage merchants; Admin only | Review merchant onboarding in Accounts; an approved business status is required separately from authentication before order access | Merchant/Dispatcher cannot approve themselves; pending/suspended/rejected status does not grant order capability; stale/unauthorized decision fails closed | Accounts owns auditable approval/status changes; Merchant pair reviews transitions. This review does not claim an approval HTTP endpoint is implemented |
| `u6` Manage zones/pricing; authorized Admin | Review authorized zone/rate configuration; approved commands go by REST to Pricing & Zones | Dispatcher requires explicit delegation (none assumed); invalid/unauthorized pricing change is rejected; quote/rate policy still open | Pricing owns its DB and versions. Existing booked fee snapshots in Delivery remain unchanged |
| `u7` Daily outcome reports; shared, different scopes | Read-only GraphQL over authorized providers; Admin reads permitted administrative aggregates | Elevated role does not authorize arbitrary merchant/customer details; cap query cost and data disclosure | No writes, role elevation or parcel overrides through reporting; record retention/export audit policy requires DA-02 |

Dispatcher-only default operations are queue/assignment/workload. Admin-only defaults are controlled provisioning, merchant approval and zone/pricing changes. Authentication and reporting are shared with different scopes. Admin assignment/reassignment, parcel-state overrides, policy editing, unrestricted audit access and automatic cross-tenant inspection are **not** granted by Figure 04; they require an explicit DA-02 decision. The Accounts merchant provider's existing onboarding inspection permission is a bounded example, not general Admin access. Operational admin/dispatch features remain outside November 1 implementation scope; role landing work belongs to separate issues.

## 5. Assignment walkthrough and lifecycle boundary

1. Customer/Merchant booking (Figures 07/10/11) validates principal/tenant and accepted quote, then Delivery commits the parcel in existing status `CREATED`, history, price snapshot, idempotency and outbox. Cancelling the **booking confirmation** in Figure 07 creates no parcel; it is not cancellation of an existing delivery.
2. The authorized Delivery queue supplies unassigned, nonterminal candidates. Exact assignable stages and operational-zone eligibility need DA-01/DA-02. Accounts supplies active courier eligibility; Delivery supplies active workload. No candidate or unavailable read ends the attempt without a write.
3. Dispatcher inspects details and selects manually. No ranking, routing, capacity number or automatic dispatch is implied. One active assignment **per parcel** does not mean one parcel per courier.
4. The SPA sends the existing proposed `POST /api/v1/assignments` through Gateway to Delivery. Figure 08 already proposed `parcelId`, `courierId`, `expectedVersion`; these are not a newly frozen API. Actor identity comes from validated JWT, never body-supplied `assigned_by` or untrusted headers.
5. Delivery independently verifies JWT/role/resource scope, rechecks Accounts eligibility via authenticated, deadline-bound gRPC, and serializes the parcel/assignment write with version/lock and active-binding uniqueness. The existing Figure 14 invariant means **at most one active row per parcel**, using a partial unique index rather than `UNIQUE(parcel_id, active)`; zero active rows is valid before assignment. This PR creates no index/migration.
6. Delivery commits assignment and attributable audit locally. Assignment does **not** create `ASSIGNED` parcel status or a fake `parcel.status.changed.v1` event. Assignment/version coupling, retired-assignment audit and safe command retry semantics need DA-03; no schema is frozen here.
7. Refresh an authorized canonical read to show the assignment. Courier `u1` reads assigned deliveries (Figure 03); current design does not gate that read on a separate acceptance action. A push notification to the selected courier is a **missing contract**, DA-04, not a completed step in this diagram.
8. No approved assignment state enum, offer/accept/reject/expiry model or timeout scheduler exists. Figure 14 provides `active`, `assigned_at`, `assigned_by` and assignment identity, not an offer state machine. Initial absence -> active binding is supported; deactivation/replacement timing and terminal handling need owner review. Do not introduce `PENDING`, `ACCEPTED`, `REJECTED`, `EXPIRED` or other new assignment enums.
9. Courier lifecycle changes proceed separately (Figures 09/12/17): `CREATED -> PICKED_UP -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED`, with guarded `FAILED` paths. `DELIVERED` and `FAILED` are terminal. The proposed early-failure path remains DEC-DEL-01; no `CANCELLED`/redelivery path is approved. Delivery enforces current assigned Courier, version and agreed evidence policy; Dispatcher/Admin do not gain these actions.
10. For actual parcel-status changes, Delivery commits status/history/outbox atomically, then Kafka asynchronously feeds Communications notifications/webhooks and Delivery SSE. Assignment success, courier notification delivery, parcel tracking and assignment acceptance must not be presented as the same lifecycle.

There is **no assignment-specific sequence diagram** in the existing 20-figure pack and Issue #13 does not require another one. Figure 08 plus the interaction walkthrough above covers the assignment request; existing sequences 10/11 supply booking context and 12 covers later Courier status side effects. A new assignment sequence/state diagram should follow an approved contract/policy if requested later.

## 6. Normal and exception walkthroughs (design rationale only)

These are paper scenarios, **not executed application tests**. Error codes follow existing outlines where given; new exact error payloads/status choices remain contract review work.

| Scenario | Required design treatment / outstanding policy |
|---|---|
| Normal manual assignment | Scoped reads -> explicit selection -> REST -> owning-service authorization/eligibility/concurrency -> assignment/audit -> canonical refresh; no lifecycle status change |
| No available courier | No selection/write; show no eligible candidate and refresh later. No escalation timer or substitute courier is invented (DA-01) |
| Courier assigned elsewhere | Delivery workload may include multiple parcels. Accounts eligibility and any approved capacity rule must be rechecked; do not silently reject every busy courier (DA-01) |
| Courier rejection or assignment expiry | No approved action, duration, state or retry count. Record policy question; do not convert to parcel `FAILED` (DA-01) |
| Duplicate assignment request | Existing active binding must never create a second active row. Exact same-request replay versus conflict response needs DA-03; do not borrow booking idempotency semantics as an approved assignment contract |
| Two dispatchers assign concurrently | Both may read the same queue/version; serialize writes and enforce unique active binding in Delivery. Only one wins; loser refreshes on conflict (409 in current design). No successful assignment audit for loser |
| Parcel already terminal / requested cancellation | Re-read and reject invalid assignment. Existing-delivery cancellation is undecided (DEC-DEL-01); do not add cancellation enum, timer or timeout cancellation |
| Courier becomes unavailable after display | Recheck Accounts at command time and fail closed if ineligible. Post-commit loss of eligibility and reassignment trigger/stage remain DA-01; a gRPC snapshot is not a cross-service atomic guarantee |
| Unauthorized reassignment / Admin intervention | Wrong role/resource scope causes denial (403 or privacy-preserving 404). No automatic Admin bypass. Stage, justification, permission and audit requirements need DA-01/DA-02 |
| Courier delivery exception | Apply only approved stage/failure/evidence rules; unresolved cases go to Courier/Dispatcher policy review. Dispatcher must not issue unapproved status overrides |
| Notification provider fails | Communications owns durable retries/inspection for approved events; never roll back committed Delivery state. Assignment event/recipient policy is absent (DA-04) |
| Duplicate/stale Kafka event | Durable `(consumerName,eventId)` deduplication and aggregate-version checks before side effects; receipt + job before offset commit. Kafka cannot create an assignment by replay |
| gRPC/read timeout before write | Bounded deadline; no eligibility assumption or assignment commit. Read-only query partial results must not authorize a write; contract partial-failure details remain open |
| REST response lost after possible commit | Outcome is unknown, not proven failure. Refresh authoritative state before another attempt; no blind non-idempotent retry. DA-03 must define exact replay/reconciliation behavior |

## 7. Service boundaries, security and audit

| Responsibility | Owner / transport / enforcement |
|---|---|
| Identity and staff provisioning | Keycloak + Platform; existing OIDC/JWT contract. No credential, role or identity implementation changes here |
| Profiles, merchant status, courier business eligibility | Accounts & Partners; authorized provider reads; Delivery -> Accounts uses authenticated gRPC, validated actor context, deadlines and fail-closed behavior |
| Assignment, queue, workload, parcel authorization/audit | Delivery Operations and its own PostgreSQL DB. Courier reference is opaque external `courier_sub`; `courierId` request-to-sub mapping needs provider contract review, not a cross-DB FK |
| Zone/rate policies and quotes | Pricing & Zones, separate DB. Only its service writes its data; accepted booking price snapshots remain in Delivery |
| Composite dashboards/reports | One read-only GraphQL Query API; authorized gRPC provider reads, field/resource/tenant checks, caps/pagination, no direct DB or mutations |
| External commands/simple reads | REST through Gateway to owning service; Gateway applies edge policy, never assignment business logic or the only authorization check |
| Status events and eventual side effects | Delivery transactional outbox -> Kafka -> independent Communications jobs/attempts; Delivery tracking -> SSE -> browser with canonical REST recovery. Assignment-specific event still DA-04 |

All four business services remain independently deployable; Dispatcher/Admin are UI actors, not extra services. No shared writable DB, cross-service SQL join, distributed transaction or shared business-entity JAR is introduced. PostgreSQL, Java 21/Spring Boot and React/TypeScript remain the baseline; diagrams do not prove later protocols are implemented.

Provider authorization must bind JWT subject and verified tenant/resource context. Customer/Merchant cannot directly modify assignments. Courier cannot assign themselves; Courier status/proof requests require their current active binding. Dispatcher reads/commands remain operationally scoped; Admin requests need explicit permissions for the requested resource. GraphQL, gRPC and Gateway cannot bypass provider checks. Public tracking stays redacted, including for staff using that public interface. Do not put tokens, private proof URLs, contact details or addresses into events/logs.

Figure 14's assignment row already records `assigned_by`/`assigned_at`; its `STATUS_HISTORY` describes parcel statuses. **Do not manufacture status history entries just to log an assignment.** DA-03 must specify reassignment actor/reason/prior-and-new binding, authorization context, timestamp/correlation and denied-attempt handling without logging secrets, and distinguish assignment audit from status history. Canonical audit remains in the owning service, not Kafka retention. No new audit table or column is approved by this review.

## 8. Findings and diagram corrections

| ID | Diagram/document | Problem / severity | Correction / resolution state | Responsible owner |
|---|---|---|---|---|
| F13-01 | Figure 04 `u4`/`u5`/note | Broad administration labels hide controlled provisioning and permission scope; High | Clarified existing labels/note: Keycloak staff roles, Accounts eligibility/approval, no blanket Admin override; detailed policy DA-02 open | Pushmitha/HarithKeshan + Platform + Merchant |
| F13-02 | Figure 08 `reject -> select` | 403 routed directly back to selection; stale data reused and no stop/no-candidate path; High | Separate guarded refresh/reselection from denied/unresolved stop; retry reloads authorized reads; no-candidate/read-failure path terminates without writing | Dispatcher + Courier/Delivery |
| F13-03 | Figures 04/08 read labels | Eligibility could be mistaken for availability/workload; Medium | Explicit Accounts eligibility vs Delivery workload and manual selection; no assumed capacity limit | Dispatcher + Courier/Delivery |
| F13-04 | Figure 08 / Figure 14 / Kafka outline | Assignment notification and acceptance/timeout states are absent; High | Explicit open-policy note and DA-01/DA-04; no new enum/topic/event or acceptance gate | Courier/Dispatcher + Communications/Platform |
| F13-05 | Figure 14, Delivery review §4.2 and [PR #77 description](https://github.com/Suthankan1/swiftdrop/pull/77) | Review §4.2 places assignment actors/system timeout cancellations in status history; PR #77 describes `ended_at`, absent from the current ERD. These details exceed the current schema/policy; High | Flagged DA-01/DA-03 for owner correction; this PR does not rewrite Delivery schema or the other owner's review. Retain only evidenced fields; no fake status transition | Vibhushan + Dispatcher/Customer owners |
| F13-06 | Figure 17 / Delivery review | Early failure and cancellation remain open; assigning a courier does not resolve them; High | Preserve DEC-DEL-01 and Courier proof questions; no new lifecycle transitions | Courier/Dispatcher + HarithKeshan |
| F13-07 | Assignment REST outline / Figure 08 | Version update, duplicate-write and lost-response behavior lack a frozen contract; High | Explicit DA-03; preserve concurrency/uniqueness invariant and fail-closed provider checks; no invented endpoint/error schema | Delivery feature owners + Platform |
| F13-08 | Figure 04 `u7`, Figure 08 `load` | Authorized read-only reporting/provider boundary needs explicit narrative; Medium | Clarified GraphQL reads/gRPC providers versus REST commands in both sources and this review | Dispatcher/Admin + Platform |

Only **04** and **08** sources/previews change. Their figure numbers, filenames, diagram IDs and original cell IDs are retained. Figure 08 adds an explicit merge before reads, decision/final nodes and labeled guards; connectors remain editable. Actors stay outside Figure 04's SwiftDrop boundary, with plain associations; no unsupported `include`/`extend` or actor inheritance is added. Figure 08's distinct final nodes cover successful commit, no-candidate/read failure and stopped attempt. Unchanged ERDs/components/status sequences are cross-review dependencies, not implicitly corrected diagrams.

## 9. Decisions requiring owner approval

| ID | Missing decision / concrete questions | Required reviewers | State |
|---|---|---|---|
| DA-01 | Assignment eligibility/stages, courier capacity, post-commit unavailability, reassignment/closure, offer acceptance/rejection/expiry and escalation: which are required at all? How do terminal delivery and possible future cancellation affect active binding? | Dispatcher pair + Abhinaya/Vibhushan; Customer boundary review by Gajanthan/Sinthuha | Open; use current manual assignment only; links to DEC-DEL-01, no new state model |
| DA-02 | Operational zone/tenant scopes, explicit Admin override/delegation, staff/merchant provisioning approvals and reconciliation, audit/report access scope | HarithKeshan + Suthankan1 + Accounts/Merchant owners | Open; existing controlled provisioning/default role separation retained |
| DA-03 | Assignment audit persistence, concurrency token update and interaction with status versions, safe duplicate/lost-response handling, request courier ID mapping, authorized reassignment contract; reconcile Delivery review's audit assumptions and PR #77's `ended_at` detail | Delivery Customer/Courier/Dispatcher owners + Platform | Open; no API/schema/state change made |
| DA-04 | Is assignment notification required? If yes, define event semantics without a parcel-status change, recipients, permitted lookup/payload, outbox consistency, dedupe/retry and delay handling | Courier/Dispatcher + Thuvarahan/Communications + Suthankan1 | Open; do not reuse `parcel.status.changed.v1` to pretend an assignment is a status transition |

These are registered in [OPEN_DECISIONS.md](../architecture/OPEN_DECISIONS.md). They are follow-up design/contract reviews, not permission to implement extra scope or claim team approval.

## 10. Validation evidence

Environment: Windows/PowerShell, Python 3.14, portable official Draw.io Desktop **32.5.5**, and a temporary `@resvg/resvg-js` rasterizer. Validation helpers, downloaded tools and PNG previews are outside the Git repository and are not committed. No repository-wide diagram/document validator was configured.

| Actual check | Observed result |
|---|---|
| Draw.io `--export --format svg --size page --theme light --embed-svg-fonts false --output <matching-preview> <source>` for 04/08 | Both sources loaded and exported successfully to their matching SVG paths |
| Draw.io PNG export and separate SVG rasterization / visual inspection | Labels, actors, editable connectors, decisions, merge/retry routing, final nodes and notes readable; source and SVG content agree. Native Draw.io SVG compatibility-warning fallback can appear in converters that lack SVG extensibility support; use Draw.io export for report conversion as the diagram README advises |
| Temporary Python XML/diagram checks | Draw.io and SVG XML parse; Figure 04 **30/30**, Figure 08 **47/47** source/rendered cell IDs; every label matches, endpoints resolve, nodes stay within canvas; original IDs/figure numbers retained |
| Temporary Python Markdown check | **142** relative links/anchors across changed Markdown files resolve; **0** errors |
| Contribution CSV parse | Nine columns per row; one #13 owner entry; estimate explicitly distinct from recorded time; peer review pending |
| Changed-file secret-pattern scan and manual diff scope review | No private-key, GitHub-token or AWS access-key pattern found; no credentials, executable source, schema, binaries or temporary artifacts in the change. This is not a full secret-scanner certification |
| `git diff --check` | Passed |

Application build/tests were **not run**: no executable project source is changed. Paper concurrency/security scenarios in Section 6 are future test requirements, not passing runtime tests. Git ancestry, staged diff and final branch status are checked again at submission and recorded in the PR/issue handoff.

## 11. Report handoff and conclusion

**Figure 04 caption:** Dispatcher/Admin use cases with distinct operational and administrative permissions. Staff identities are provisioned through Keycloak; Accounts owns business eligibility and merchant approval, Pricing owns zone/rate changes, and authorized daily reports are read-only GraphQL. Elevated identity does not grant unrestricted parcel or cross-tenant powers.

**Figure 08 caption:** Planned manual courier assignment: authorized GraphQL/gRPC reads precede a REST command to Delivery. Provider authorization, eligibility and parcel concurrency/active-assignment checks protect the local assignment commit. Empty/unavailable reads and denied/conflicting commands do not create assignments. Assignment remains separate from parcel status; acceptance/timeout/reassignment and notification policy are explicitly unresolved.

Report sections 2/4/5/6/10 should use these captions, the corrected [04](../diagrams/rendered/04_dispatcher_admin_use_cases.svg) and [08](../diagrams/rendered/08_assignment_activity.svg) previews, findings and decisions above, and the [contribution register](../team/CONTRIBUTION_LOG.csv). Issue #19 receives the PR/commit links after publication. Cross-module owners retain their diagrams/business rules and must review affected dependencies; requested review is not recorded approval.

The bounded review is ready for **independent review**, not automatic closure of Issue #13. Remaining tasks: Courier/Delivery validate Figure 08 and DA-01/DA-03 (including F13-05); HarithKeshan validate Figure 04/captions and DA-02; Platform validate service/security boundaries; Communications review DA-04; report owner integrates accepted text/figures/contribution evidence into NOV-19. No auto-merge or final report submission is authorized by this artifact.

GitHub Project #5 read/update was blocked: the available Git credential lacks `read:project` (and project mutation requires write access); the connector exposes no Projects operation and no browser is connected. Current board status was not verified or changed. Once access is available, use **In Review** if the board supports it while the PR awaits review; do not mark Done merely because this document exists.
