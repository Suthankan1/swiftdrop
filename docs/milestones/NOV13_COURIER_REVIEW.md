# NOV-13 Courier use cases, status flows and proof policy review

**Issue:** [#11](https://github.com/Suthankan1/swiftdrop/issues/11)

**Owner:** Abhinaya (`@abhi190503`) with Courier partner Vibhushan

**Required reviewers:** Thuvarahan (`@thuvarahan-t`) for the Merchant webhook boundary; HarithKeshan (`@Harithkeshan`) for assignment and lifecycle consistency

**Review status:** Author review complete; independent human review and all business-policy decisions remain pending.

## Scope and evidence boundary

This is a documentation and diagram review for the November 1 report milestone. It does not implement Courier pages, Delivery commands, proof upload/storage, Kafka, Communications workers, webhooks, GraphQL, gRPC, or SSE.

The existing login slice is implemented as recorded in [project status](../project/STATUS.md). Delivery Operations, Communications, the Courier business workflow, proof handling, Kafka/outbox publication, webhooks, and SSE are **PLANNED** target architecture and are not executable November 1 Courier features.

Reviewed sources:

- [Assignment scope](../requirements/ASSIGNMENT_SCOPE.md)
- [Architecture baseline v1.3](../architecture/BASELINE.md)
- [Data ownership](../architecture/DATA_OWNERSHIP.md)
- [Protocol matrix](../architecture/PROTOCOL_MATRIX.md)
- [Open decisions](../architecture/OPEN_DECISIONS.md)
- [Team ownership](../team/OWNERSHIP.md)
- Figure 03: [editable source](../diagrams/editable/03_courier_use_cases.drawio) and [rendered SVG](../diagrams/rendered/03_courier_use_cases.svg)
- Figure 09: [editable source](../diagrams/editable/09_status_event_activity.drawio) and [rendered SVG](../diagrams/rendered/09_status_event_activity.svg)
- Figure 12: [editable source](../diagrams/editable/12_courier_status_sequence.drawio) and [rendered SVG](../diagrams/rendered/12_courier_status_sequence.svg)
- Consistency checks: Figures [08](../diagrams/rendered/08_assignment_activity.svg), [14](../diagrams/rendered/14_delivery_erd.svg), [16](../diagrams/rendered/16_communications_erd.svg), [17](../diagrams/rendered/17_parcel_state_machine.svg), and [18](../diagrams/rendered/18_kafka_sse_topology.svg)

## Courier permission and ownership boundaries

- A Courier may read only deliveries with an active assignment to that authenticated courier. A role alone is insufficient.
- A Courier may request an applicable parcel transition, but Delivery Operations validates the JWT role, active assignment, expected version, allowed transition, and any approved stage-specific evidence rule.
- Dispatcher assignment is a separate Delivery-owned relation and audit action. It is not a parcel lifecycle transition and is not performed by the Courier.
- Notes and failure reasons belong to the Delivery-owned canonical record/history. Proof metadata and its private object reference are separate Delivery-owned data. Assignment metadata remains separate from both.
- Gateway routing and edge checks do not replace Delivery's independent authorization and business-rule enforcement.
- Customer, Merchant, Dispatcher, and Administrator permissions are outside the Courier actor's authority even when their views refer to the same parcel.

## Status, event, and webhook boundary

An accepted Courier transition is a synchronous REST command routed through Gateway to Delivery Operations. Delivery commits the parcel state/version, append-only history, and outbox record atomically before returning success. A forbidden, stale, or invalid request changes none of those records and produces no successful status-change event.

Outbox relay and Kafka publication are **PLANNED asynchronous** behavior. Delivery remains correct while publication is delayed and may publish duplicates under at-least-once delivery. Consumers therefore use event IDs and aggregate versions for idempotency and stale-event handling.

Communications, not the Courier SPA or Delivery's command transaction, owns notification jobs, Merchant webhook attempts, signatures, durable retry state, and terminal delivery failures. A downstream notification or webhook failure does not roll back an already committed parcel transition. Merchant subscription, recipient, and permitted-event policy requires the Merchant/Communications contract review.

## Proof metadata and private object policy

Delivery Operations owns proof metadata, the opaque private object reference, and authorization for storing and retrieving proof. Binary proof is **PLANNED** in private object storage. Domain models, responses, events, logs, and diagrams must not expose a public object URL or storage credential.

The Platform-owned private proof storage adapter is a later bounded cross-module slice. The Courier pair retains the Courier capture experience, status/evidence rules, proof metadata, and authorization acceptance criteria. Failure reasons and general notes are not proof objects and are not assignment attributes.

No review participant may silently decide the following open rules:

| Open question | Consequence/options to evaluate | Decision owner and signoff |
|---|---|---|
| At which stages may a Courier request `FAILED`, including before pickup? | Disallow before pickup; allow selected stages; or introduce a separate cancellation/attempt model. Each option changes lifecycle guards and reporting. | Courier + Dispatcher pairs; HarithKeshan signoff required. |
| Is proof required for `DELIVERED`, permitted or required for `FAILED`, or optional by policy? | Affects transition acceptance, recovery, and accessibility when capture fails. | Courier + Dispatcher pairs; business/lecturer confirmation required. |
| Which evidence is accepted? | Recipient name, note, photo, signature, or another approved type; define allowed combinations without treating a note or failure reason as binary proof. | Courier + Dispatcher pairs; Platform consulted for storage constraints. |
| What are the upload limits and safety rules? | File kinds, maximum size/count, malware/content validation, replacement, and retry behavior. | Courier + Platform proof-adapter owner; security review required. |
| Who may retrieve proof, and for how long? | Customer/Merchant/Courier/staff access, retention, deletion, audit, and redaction rules affect privacy and storage cost. | Courier + Dispatcher/Merchant stakeholders; security/privacy signoff required. |
| When is a failure reason mandatory? | Apply only under an approved stage/outcome rule; do not make every note or proof submission a failure reason. | Courier + Dispatcher pairs; HarithKeshan signoff required. |

## Figure review findings and correction checklist

The editable XML, rendered SVG XML, labels, connector endpoints, and sequence-line coordinates were reviewed as matched pairs. Each SVG contains every Draw.io cell ID from its source. A Draw.io renderer is unavailable in the review environment, so no diagram pair was edited and no pixel-level regenerated rendering is claimed. Apply these corrections together to each `.drawio` source and matching `.svg` in a later diagram-capable review.

### Figure 03 - Courier use cases

| Element/current wording | Finding | Required correction |
|---|---|---|
| `View today's assigned deliveries` | Time filtering is narrower than the ownership rule and does not explicitly say "own." | Use `View own assigned deliveries`; describe day/filter selection in narrative only if approved. |
| No explicit inspect-details use case | The assignment requires relevant assigned-delivery details while preserving least privilege. | Add `Inspect assigned delivery details` linked only to Courier, subject to active-assignment authorization. |
| `Upload authorized proof photo` | It prematurely selects photo as the proof kind and can imply the binary is publicly uploaded. | Use policy-neutral wording such as `Capture policy-approved delivery proof`; keep private storage and exact evidence types in the open decisions. |
| `Mark failed delivery with reason` and note `failure reason is mandatory` | Mandatory evidence and allowed early-failure stages are not approved. | Use `Request FAILED outcome under approved policy`; replace the note with an explicit open-policy qualifier. |
| Ownership note mentions updates only | Read access must also be limited to the assigned Courier. | State that only the currently assigned Courier may view protected delivery details or request changes. |

The figure does not show Dispatcher assignment as a Courier lifecycle action, which is correct. Its proposed-design footer correctly prevents treating these use cases as implemented software.

### Figure 09 - status and event activity

| Element/current wording | Finding | Required correction |
|---|---|---|
| `Proof / recipient note or failure reason` | It visually conflates separate data and can imply each item is always required. | Separate optional note, policy-dependent failure reason, and policy-dependent proof metadata. |
| `required evidence` | The policy is still open. | Qualify it as `evidence required by an approved stage policy`. |
| Denial box says only `No state change` | The absence of successful history/outbox/event work should be explicit. | Add `No state/history/outbox success record; no success event`. |
| Notification/Webhook consumer boxes | Ownership is inferable but not named directly in each box. | Prefix both with `Communications` or add a Communications boundary label. |
| Asynchronous side-effects heading | The global footer says proposed, but report readers may miss that Kafka/webhooks are not implemented. | Label the async section `PLANNED` while preserving the existing after-commit arrow. |

The current source correctly shows Delivery-owned validation, expected-version handling, atomic state/history/outbox commit, after-commit publication, and no Courier-to-Kafka or Courier-to-Communications database write.

### Figure 12 - Courier status and webhook sequence

| Element/current wording | Finding | Required correction |
|---|---|---|
| Combined `Delivery via Gateway` lifeline | It obscures the edge-versus-owner authorization boundary. | Prefer separate Gateway and Delivery Operations lifelines. If geometry must remain stable, add an explicit note: Gateway routes/edge-checks; Delivery independently checks assignment and transition. |
| Status request includes `Note / proof or failure reason` | Separate concepts and open requirements are collapsed. | Label note as optional and proof/failure evidence as policy-dependent; do not select final evidence types. |
| Only success path is drawn | Rejection behavior is important but exact endpoint/status contracts are not approved. | Add a concise alternate note: missing/forbidden/stale/invalid requests return an appropriate error and create no state, history, outbox, or success event. |
| Communications-to-provider interaction | It does not identify the separately reviewed subscription/recipient policy. | Add a note that Merchant subscription, endpoint, recipient, and permitted-event rules require Merchant/Communications approval. |

The existing sequence correctly returns Delivery success before Kafka/provider work, keeps a pending outbox through Kafka failure, records Communications jobs before external attempts, and treats provider failure as retryable without rolling back parcel state.

## Planned security and test review matrix

No Courier/Delivery endpoint exists yet, so every row below is a **PLANNED design scenario, not an executed HTTP test**.

| Scenario | Expected domain outcome | Status |
|---|---|---|
| Courier reads an actively assigned delivery | Authorized projection only; unrelated sensitive fields remain excluded. | PLANNED / not executable |
| Courier requests an allowed transition with matching version | Delivery commits state, append-only history, and outbox atomically; response does not await providers. | PLANNED / not executable |
| Courier records a note | Delivery records the authorized note separately from proof and assignment metadata. | PLANNED / not executable |
| Courier submits accepted proof metadata | Delivery stores validated metadata and an opaque private object reference under approved policy. | PLANNED / policy open |
| Missing JWT | Reject at edge/owner service; no Delivery mutation or success event. | PLANNED / Courier route absent |
| Authenticated wrong role | Reject; no Delivery mutation or success event. | PLANNED / Courier route absent |
| Different Courier's assignment | Privacy-preserving denial; no details, mutation, proof access, or success event. | PLANNED / not executable |
| Stale expected version/concurrent update | Conflict; caller refreshes canonical state; no second successful transition event. | PLANNED / not executable |
| Invalid transition | Validation failure; no state/history/outbox success record. | PLANNED / transition rules open |
| Missing failure reason where an approved rule requires it | Validation failure only after that rule is approved; do not assume a universal requirement. | PLANNED / policy open |
| Duplicate/retried event | Consumer deduplicates by consumer/event ID and rejects stale aggregate versions; no duplicate durable job under the approved key. | PLANNED / not executable |
| Forbidden proof retrieval | Deny without revealing object location; record appropriate audit evidence. | PLANNED / access policy open |
| Public proof-object URL appears in output | Security/privacy failure; API/event/log must expose only an authorized opaque reference or controlled retrieval flow. | PLANNED / not executable |
| Webhook provider fails through retry exhaustion | Parcel transition remains committed; Communications records retry attempts and terminal failed-job/review state. | PLANNED / not executable |

## Questions requiring reviewer acknowledgement

**Thuvarahan (`@thuvarahan-t`):** Does the proposed boundary correctly leave Merchant webhook subscription, permitted-event, endpoint/recipient resolution, signature, and retry policy with the Merchant/Communications contract, while Delivery publishes only the approved non-sensitive parcel event after commit?

**HarithKeshan (`@Harithkeshan`):** Does the review correctly keep Dispatcher assignment separate from parcel lifecycle status, and which failure stages, failure-reason rule, and proof requirements should be approved before Figure 17 or an API contract becomes normative?

Human acknowledgement is pending; this document does not record either reviewer as approved.

## Report-ready Courier text

**Courier narrative:** SwiftDrop's target Courier workflow exposes only deliveries actively assigned to the authenticated Courier. A Courier may inspect permitted delivery details and request a policy-allowed status change with an expected version, optional note, and any evidence required by an approved stage policy. Delivery Operations independently validates role, active assignment, concurrency, transition, and evidence rules, then atomically records parcel state, append-only history, and an outbox event. Kafka publication, notifications, signed Merchant webhooks, private proof-object storage, and SSE are planned capabilities rather than November 1 Courier implementation evidence. Assignment remains a Dispatcher action, not a parcel status transition, and exact early-failure and proof requirements remain open for human approval.

**Figure 03 caption:** Proposed Courier use cases, limited to the authenticated Courier's active assignments; exact failure and proof evidence policies remain open.

**Figure 09 caption:** Proposed Delivery-owned validation and atomic status/history/outbox commit followed by planned asynchronous Kafka, Communications, and tracking side effects; rejected commands emit no successful change event.

**Figure 12 caption:** Proposed Courier command path through Gateway to Delivery Operations, with independent assignment authorization, transactional persistence, eventual outbox publication, and Communications-owned webhook delivery that cannot roll back committed parcel state.
