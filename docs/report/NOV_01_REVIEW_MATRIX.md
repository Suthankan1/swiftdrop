# SwiftDrop — November 1 Academic Report Review & Approval Matrix

**Document Reference:** `docs/report/NOV_01_REVIEW_MATRIX.md`  
**Target Milestone:** November 1, 2026 (Initial Login Vertical Slice & System Architecture Design)  
**Report Assembly Owner:** HarithKeshan ([@Harithkeshan](https://github.com/Harithkeshan)) — Issue #19  
**Architecture & Security Lead:** Suthankan1 ([@Suthankan1](https://github.com/Suthankan1))  
**Repository:** [Suthankan1/swiftdrop](https://github.com/Suthankan1/swiftdrop)  
**Base Branch Target:** `test`  
**Date of Assembly:** October 22, 2026  

---

## 1. Executive Summary & Review Discipline

In strict accordance with repository governance ([`AGENTS.md`](file:///d:/PROJECTS/swiftdrop/AGENTS.md) and [`docs/project/STATUS.md`](file:///d:/PROJECTS/swiftdrop/docs/project/STATUS.md)), this document maintains an academically honest, verifiable review register for the SwiftDrop November 1 report package.

### Review Status Definitions
- **APPROVED:** Formal peer signoff documented in a merged PR, signed-off markdown review file, or verified commit artifact.
- **REVIEW REQUESTED:** Complete pull request or draft submitted to designated reviewers; awaiting peer confirmation.
- **CHANGES REQUESTED:** Reviewer requested revisions before signoff.
- **BLOCKED / PENDING DEPENDENCY:** Cannot be signed off until an upstream business component or lecturer decision is formalized.
- **PENDING LECTURER CONFIRMATION:** Academic requirement subject to formal instructor/module coordinator clarification.

---

## 2. Report Sections Review Register (28 Sections)

| Section # | Section Title | Primary Author | Designated Reviewers | Current Status | Supporting Evidence & Link | Reviewer Notes & Constraints |
|:---:|:---|:---|:---|:---:|:---|:---|
| 01 | Title Page & Identification | HarithKeshan | Team (All 9) | REVIEW REQUESTED | [`docs/report/NOV_01_REPORT_DRAFT.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REPORT_DRAFT.md#1-title-page-information) | Pending official module code & submission portal verification. |
| 02 | Executive Summary / Abstract | HarithKeshan | Suthankan1, Eric | REVIEW REQUESTED | [`docs/report/NOV_01_REPORT_DRAFT.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REPORT_DRAFT.md#2-executive-summary--abstract) | Emphasizes strict distinction: login slice implemented vs enterprise architecture designed. |
| 03 | Project Introduction & Context | Suthankan1, HarithKeshan | Gajanthan, Thuvarahan | APPROVED | [`docs/project/PROJECT_CONTEXT.md`](file:///d:/PROJECTS/swiftdrop/docs/project/PROJECT_CONTEXT.md) | Clarifies SwiftDrop branding migration from earlier working title Devarom. |
| 04 | Problem Statement & Industry Context | Suthankan1, Gajanthan | Sinthuha, Abhinaya | APPROVED | PR #40, PR #82 | Multi-actor parcel logistics with unified courier & merchant workflows. |
| 05 | Project Objectives & Milestones | Suthankan1, HarithKeshan | Eric, Pushmitha | APPROVED | [`docs/milestones/NOV_01.md`](file:///d:/PROJECTS/swiftdrop/docs/milestones/NOV_01.md) | Focus on November 1 initial login slice and complete v1.3 architecture narrative. |
| 06 | Functional Requirements (FR-01 - FR-07) | Suthankan1, Feature Pairs | Gajanthan, Eric, Abhinaya, Pushmitha | APPROVED | [`docs/requirements/ASSIGNMENT_SCOPE.md`](file:///d:/PROJECTS/swiftdrop/docs/requirements/ASSIGNMENT_SCOPE.md) | Traced against assignment requirements traceability matrix. |
| 07 | Non-Functional Requirements (NFR-01 - NFR-06) | Suthankan1, Vibhushan | Suthankan1, Vibhushan | APPROVED | [`docs/architecture/BASELINE.md`](file:///d:/PROJECTS/swiftdrop/docs/architecture/BASELINE.md) | Strict security, sub-second SLAs, 99.9% uptime, zero shared DBs. |
| 08 | Stakeholders & Actor Analysis | Pushmitha, HarithKeshan | Eric, Abhinaya | REVIEW REQUESTED | PR #83, PR #84 | Analyzes Customer, Merchant, Courier, Dispatcher, and Platform Admin. |
| 09 | Use-Case Analysis (Figures 01–04) | Actor Pairs (Gajanthan, Eric, Abhinaya, Pushmitha) | Suthankan1, HarithKeshan | APPROVED | PR #74, PR #80, PR #82, PR #83 | Fully aligned with 4 distinct actor use case diagrams. |
| 10 | System Context (Figure 05) | Suthankan1 | Team (All 9) | APPROVED | [`docs/diagrams/05-system-context.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/05-system-context.svg) | External boundaries: Keycloak, Google OIDC, Mailpit/SES, Private S3, Payment Gateway. |
| 11 | Logical Architecture (Figure 06) | Suthankan1 | Team Architects | APPROVED | [`docs/architecture/BASELINE.md`](file:///d:/PROJECTS/swiftdrop/docs/architecture/BASELINE.md), PR #34 | 4 business services, 1 gateway, 1 read-only GraphQL API, 1 SPA. |
| 12 | Technology Selection & Justification | Suthankan1, HarithKeshan | Eric, Thuvarahan | APPROVED | [`docs/report/NOV_01_REPORT_DRAFT.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REPORT_DRAFT.md#12-technology-selection-and-justification) | Rigorous trade-off analysis: Spring Boot, React, PostgreSQL, Kafka, Keycloak. |
| 13 | Service Responsibilities & Boundaries | Suthankan1, Sinthuha | Thuvarahan, Vibhushan | APPROVED | [`docs/architecture/DATA_OWNERSHIP.md`](file:///d:/PROJECTS/swiftdrop/docs/architecture/DATA_OWNERSHIP.md) | Strict data ownership; zero shared tables or cross-service SQL joins. |
| 14 | Protocol Integration Strategy (REST/GraphQL/gRPC/Kafka/SSE) | Suthankan1 | Thuvarahan, Abhinaya | APPROVED | [`docs/architecture/BASELINE.md`](file:///d:/PROJECTS/swiftdrop/docs/architecture/BASELINE.md) | Commands/REST, aggregate read-only/GraphQL, internal sync/gRPC, async/Kafka, browser live/SSE. |
| 15 | Authentication & Authorization (Figure 13) | Suthankan1 | Vibhushan, HarithKeshan | APPROVED | [`docs/contracts/LOGIN_CONTRACT.md`](file:///d:/PROJECTS/swiftdrop/docs/contracts/LOGIN_CONTRACT.md), PR #35, PR #45 | PKCE OIDC, Google brokerage, in-memory tokens, stateless JWT validation. |
| 16 | Activity Diagrams (Figures 07–09) | Gajanthan, Pushmitha, Thuvarahan | Suthankan1, Eric | APPROVED | PR #40, PR #83, PR #79 | Quote/booking, courier dispatch assignment, and status/event ingestion workflows. |
| 17 | Sequence Diagrams (Figures 10–13) | Gajanthan, Eric, Abhinaya, Suthankan1 | Vibhushan, Thuvarahan | APPROVED | PR #74, PR #78, PR #82, PR #55 | Customer delivery, merchant bulk creation, courier update/webhook, and OIDC login. |
| 18 | Logical Database Design (Figures 14–16) | Sinthuha, Vibhushan, Thuvarahan, HarithKeshan | Suthankan1 | APPROVED | PR #43, PR #77, PR #79, PR #86 | Normalized schemas for Accounts/Pricing, Delivery Operations, Communications. |
| 19 | Parcel Lifecycle & State Machine (Figure 17) | Vibhushan, Abhinaya | Pushmitha, Suthankan1 | APPROVED | PR #77, PR #74 | 10 deterministic states, terminal states, proof validation, cancellation guards. |
| 20 | Security & Reliability Rationale | Suthankan1, Vibhushan | HarithKeshan, Eric | APPROVED | PR #50, PR #52, PR #76 | STRIDE threat model, OWASP Top 10 mitigation, transactional outbox pattern. |
| 21 | Current Implementation Status | Suthankan1, HarithKeshan | Team (All 9) | APPROVED | [`docs/project/STATUS.md`](file:///d:/PROJECTS/swiftdrop/docs/project/STATUS.md), PR #86 | Honest delineation: Login vertical slice implemented, business logic planned. |
| 22 | Testing & Verification Evidence | Vibhushan, Eric, Suthankan1 | HarithKeshan, Sinthuha | APPROVED | PR #50, PR #51, PR #55, PR #76, PR #85 | 36 PASS / 0 FAIL / 4 BLOCKED security suite; clean-clone verification PASS. |
| 23 | Demonstration & Reproduction Runbook | Suthankan1, Eric | HarithKeshan, Gajanthan | APPROVED | [`docs/milestones/NOV_01_READINESS_TRACKER.md`](file:///d:/PROJECTS/swiftdrop/docs/milestones/NOV_01_READINESS_TRACKER.md), PR #85 | Clean-clone script verified independently by Eric on Windows. |
| 24 | Limitations & Open Decisions | HarithKeshan, Suthankan1 | Pushmitha, Thuvarahan | REVIEW REQUESTED | [`docs/reviews/NOV_13_DISPATCHER_ADMIN_REVIEW.md`](file:///d:/PROJECTS/swiftdrop/docs/reviews/NOV_13_DISPATCHER_ADMIN_REVIEW.md) | Captures open design decisions DA-01–DA-04, CR-01, MR-01. |
| 25 | Team Contribution Matrix | HarithKeshan | Team (All 9) | REVIEW REQUESTED | [`docs/team/CONTRIBUTION_LOG.csv`](file:///d:/PROJECTS/swiftdrop/docs/team/CONTRIBUTION_LOG.csv) | Reconciles PR #74–#86 commit evidence across all 9 members. |
| 26 | Review & Approval Register | HarithKeshan | Suthankan1 | REVIEW REQUESTED | [`docs/report/NOV_01_REVIEW_MATRIX.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REVIEW_MATRIX.md) | This document; subject to team peer signoff. |
| 27 | Conclusion & Future Work | HarithKeshan | Suthankan1 | REVIEW REQUESTED | [`docs/report/NOV_01_REPORT_DRAFT.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REPORT_DRAFT.md#27-conclusion-and-future-roadmap) | Phase 2 roadmap: Kafka/Debezium outbox, gRPC contracts, GraphQL, K8s. |
| 28 | References & Appendices | HarithKeshan | Suthankan1 | REVIEW REQUESTED | [`docs/report/NOV_01_REPORT_DRAFT.md`](file:///d:/PROJECTS/swiftdrop/docs/report/NOV_01_REPORT_DRAFT.md#28-references-and-appendices) | RFC 6749, RFC 7636, RFC 6238, Spring Cloud Gateway, Keycloak 26 docs. |

---

## 3. Architecture Diagrams Review Register (All 20 Diagrams)

All 20 system diagrams are hosted under `docs/diagrams/` with SVG exports and Draw.io editable XML source files.

| Figure # | Diagram Name | SVG Asset Path | Source File (`.drawio`) | Primary Lead / Pair | Architecture Signoff | Review Status | Notes & Scope Boundaries |
|:---:|:---|:---|:---|:---:|:---:|:---:|:---|
| **01** | Customer Use Cases | [`01-customer-use-cases.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/01-customer-use-cases.svg) | `01-customer-use-cases.drawio` | Gajanthan & Sinthuha | Suthankan1 | APPROVED | Covers quote, booking, tracking, history, cancellation. |
| **02** | Merchant Use Cases | [`02-merchant-use-cases.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/02-merchant-use-cases.svg) | `02-merchant-use-cases.drawio` | Eric & Thuvarahan | Suthankan1 | APPROVED | Covers bulk shipment, API keys, webhook management, billing. |
| **03** | Courier Use Cases | [`03-courier-use-cases.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/03-courier-use-cases.svg) | `03-courier-use-cases.drawio` | Abhinaya & Vibhushan | Suthankan1 | APPROVED | Covers shift management, accept/reject, proof submission (OTP/photo). |
| **04** | Dispatcher & Admin Use Cases | [`04-dispatcher-admin-use-cases.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/04-dispatcher-admin-use-cases.svg) | `04-dispatcher-admin-use-cases.drawio` | Pushmitha & HarithKeshan | Suthankan1 | APPROVED | Covers manual assignment, zone routing, pricing overrides, audit logs. |
| **05** | System Context | [`05-system-context.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/05-system-context.svg) | `05-system-context.drawio` | Suthankan1 | Team Lead | APPROVED | Boundary context; does not claim internal runtime deployment. |
| **06** | Logical System Architecture | [`06-logical-architecture.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/06-logical-architecture.svg) | `06-logical-architecture.drawio` | Suthankan1 | Platform Lead | APPROVED | Approved v1.3 baseline; separates login slice from future services. |
| **07** | Quote & Booking Activity | [`07-quote-booking-activity.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/07-quote-booking-activity.svg) | `07-quote-booking-activity.drawio` | Gajanthan & Sinthuha | Suthankan1 | APPROVED | Workflow from weight/dimension input to payment hold & parcel creation. |
| **08** | Courier Assignment Activity | [`08-courier-assignment-activity.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/08-courier-assignment-activity.svg) | `08-courier-assignment-activity.drawio` | Pushmitha & HarithKeshan | Suthankan1 | APPROVED | Automated matching, timeout fallback, manual dispatcher reassignment. |
| **09** | Status Ingestion & Event Activity | [`09-status-event-activity.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/09-status-event-activity.svg) | `09-status-event-activity.drawio` | Thuvarahan & Suthankan1 | Suthankan1 | APPROVED | Outbox write, Kafka publishing, SSE browser push, webhook queue. |
| **10** | Customer Delivery Booking Sequence | [`10-customer-delivery-sequence.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/10-customer-delivery-sequence.svg) | `10-customer-delivery-sequence.drawio` | Gajanthan & Sinthuha | Suthankan1 | APPROVED | Step-by-step API interactions between SPA, Gateway, Pricing, Delivery. |
| **11** | Merchant Bulk Order Sequence | [`11-merchant-order-sequence.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/11-merchant-order-sequence.svg) | `11-merchant-order-sequence.drawio` | Eric & Thuvarahan | Suthankan1 | APPROVED | Asynchronous CSV/JSON upload, validation, batch parcel generation. |
| **12** | Courier Update & Webhook Sequence | [`12-courier-status-webhook-sequence.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/12-courier-status-webhook-sequence.svg) | `12-courier-status-webhook-sequence.drawio` | Abhinaya & Thuvarahan | Suthankan1 | APPROVED | Courier mobile status submission, outbox capture, merchant webhook delivery. |
| **13** | Keycloak Google OIDC Sequence | [`13-keycloak-google-oidc-sequence.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/13-keycloak-google-oidc-sequence.svg) | `13-keycloak-google-oidc-sequence.drawio` | Suthankan1 | Vibhushan | APPROVED | PKCE flow, Google brokerage, token exchange, profile provisioning (PR #55). |
| **14** | Delivery Operations ERD | [`14-delivery-operations-erd.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/14-delivery-operations-erd.svg) | `14-delivery-operations-erd.drawio` | Vibhushan & Abhinaya | Suthankan1 | APPROVED | `parcels`, `courier_assignments`, `tracking_events`, `transactional_outbox`. |
| **15** | Accounts & Pricing Logical ERD | [`15-accounts-pricing-erd.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/15-accounts-pricing-erd.svg) | `15-accounts-pricing-erd.drawio` | Sinthuha & Suthankan1 | Sinthuha / Suthankan1 | APPROVED | Segregated schemas: Accounts (`user_profiles`, `staff_profiles`), Pricing (`pricing_zones`, `rate_sheets`). |
| **16** | Communications Logical ERD | [`16-communications-erd.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/16-communications-erd.svg) | `16-communications-erd.drawio` | Thuvarahan | Suthankan1 | APPROVED | `notifications`, `webhook_subscriptions`, `webhook_deliveries`, `message_templates`. |
| **17** | Parcel Lifecycle State Machine | [`17-parcel-lifecycle.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/17-parcel-lifecycle.svg) | `17-parcel-lifecycle.drawio` | Vibhushan & Abhinaya | Pushmitha / Suthankan1 | APPROVED | 10 valid states, transition rules, courier/dispatcher authorization guards. |
| **18** | Kafka & SSE Reliability Topology | [`18-kafka-sse-reliability-topology.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/18-kafka-sse-reliability-topology.svg) | `18-kafka-sse-reliability-topology.drawio` | Suthankan1 | Platform Lead | APPROVED | Dual-write prevention, Debezium/Transactional Outbox, SSE cluster proxying. |
| **19** | Kubernetes Production Target | [`19-kubernetes-deployment-target.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/19-kubernetes-deployment-target.svg) | `19-kubernetes-deployment-target.drawio` | Suthankan1 | Platform Lead | APPROVED | Bonus Target Architecture; strictly marked NOT deployed for Nov 1. |
| **20** | Target Docker Compose Runtime | [`20-docker-compose-target.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/20-docker-compose-target.svg) | `20-docker-compose-target.drawio` | Suthankan1 | Eric | APPROVED | Full enterprise multi-service Compose target; contrasted with implemented login stack. |

---

## 4. Open Design Decisions & Policy Register

The following business and architecture rules were analyzed during November 1 preparation. Where consensus has not yet been codified into a frozen ADR, the decision is designated as **PROPOSED** or **PENDING DEEP REVIEW**.

| Decision ID | Area | Title / Question | Proposed Rule | Current Status | Impacted Services / Roles | Notes & Open Questions |
|:---:|:---|:---|:---|:---:|:---|:---|
| **DA-01** | Dispatcher | Manual Courier Assignment Override Policy | Dispatchers can reassign parcels in `ASSIGNED` or `PICKED_UP` state only upon recording an auditable reason code. | PROPOSED | Delivery Operations, Dispatcher UI | Reviewed in PR #83; requires concurrency check against courier simultaneous scan. |
| **DA-02** | Dispatcher | Reassignment Concurrency Guard | Optimistic locking via `version` column in `parcels` table. If courier accepts while dispatcher reassigns, first committer wins with HTTP 409 conflict returned to loser. | PROPOSED | Delivery Operations | Defined in PR #83 review; must be implemented in Delivery domain entity. |
| **DA-03** | Admin | Operational Scope & Partitioning | Admins operate on system-wide configuration, rate sheets, user states, and global audit logs; Admins do NOT modify operational parcel status directly. | PROPOSED | Accounts, Delivery, Admin UI | Segregates administrative governance from courier physical execution. |
| **DA-04** | Dispatcher | Auto-Assignment Timeout Window | Unaccepted courier assignments expire after 180 seconds and return to dispatcher queue. | PROPOSED | Delivery Operations, Courier Mobile | Timeout configuration to be externalized into Spring Cloud Config. |
| **CR-01** | Courier | Proof-of-Delivery Verification | High-value parcels (> $200) require recipient OTP verification; standard parcels require geofenced photo signature. | PROPOSED | Delivery Operations, Private Storage | S3 pre-signed upload URL vs direct multipart streaming via Gateway. |
| **MR-01** | Merchant | Tenant Boundary & Onboarding Approval | Merchant API credentials remain dormant until an Admin verifies business registration documents in Accounts Service. | PROPOSED | Accounts & Partners, Merchant UI | Addressed in PR #78 & PR #80; pending business document upload pipeline. |
| **MR-02** | Merchant | Webhook Retry Exponential Backoff | Communications Service retries failed webhook endpoints with jitter up to 5 attempts before marking delivery dead-lettered. | PROPOSED | Communications Service | Detailed in Figure 16 ERD and PR #79 review. |

---

## 5. Independent Reproduction & Peer Security Signoffs

| Review Category | Responsible Contributor | Testing Scope & Methodology | Result / Status | Evidence Artifact |
|:---|:---|:---|:---:|:---|
| **Independent Clean-Clone Setup** | Eric Praveen ([@EricPraveen](https://github.com/EricPraveen)) | Fresh repository clone on Windows 11 host; execution of `clean-clone-setup.ps1`; Docker infrastructure initialization; Accounts & Gateway boot; React frontend build; real browser registration and profile retrieval. | **PASS** | [`docs/reviews/NOV_17_INDEPENDENT_CLEAN_CLONE_REPRODUCTION.md`](file:///d:/PROJECTS/swiftdrop/docs/reviews/NOV_17_INDEPENDENT_CLEAN_CLONE_REPRODUCTION.md), PR #85 |
| **Independent Security & Role Audit** | Vibhushan ([@Vibhushan-2003](https://github.com/Vibhushan-2003)) | Automated E2E Security Suite execution against Gateway (:8080) and Accounts Service (:8081); verification of JWT token signature validation, role-based path protections, cross-user profile isolation, and missing auth rejection. | **36 PASS / 0 FAIL / 4 BLOCKED** | [`docs/reviews/NOV_18_INDEPENDENT_SECURITY_QA_REPORT.md`](file:///d:/PROJECTS/swiftdrop/docs/reviews/NOV_18_INDEPENDENT_SECURITY_QA_REPORT.md), PR #76 |
| **Platform Architecture Signoff** | Suthankan1 ([@Suthankan1](https://github.com/Suthankan1)) | v1.3 Architecture baseline compliance; protocol integration mapping (REST/GraphQL/gRPC/Kafka/SSE); boundary encapsulation audit; Keycloak OIDC broker token isolation check. | **APPROVED** | [`docs/team/PLATFORM_SIGNOFF_NOV01.md`](file:///d:/PROJECTS/swiftdrop/docs/team/PLATFORM_SIGNOFF_NOV01.md) |
| **Staff Profile Read Model & V3 Migration** | Suthankan1 & Team | Review of Issue #14 implementation: V3 Flyway migration, `StaffProfileRepository`, `StaffProfileController`, and RBAC role endpoint authorization (`COURIER`, `DISPATCHER`, `ADMIN`). | **APPROVED** | PR #86 (merged commit `91510c0`) |

---

## 6. Academic Lecturer Confirmations Register

| Item | Requirement / Question | Documented Status | Action Owner | Resolution / Contingency Strategy |
|:---:|:---|:---:|:---:|:---|
| **L-01** | Team Group Size Authorization | **PENDING LECTURER CONFIRMATION** | Suthankan1 / HarithKeshan | Official assignment brief mentions 10-person teams; SwiftDrop operates with 9 members. Platform/Security lead has absorbed infrastructure and cross-cutting slices. Written waiver request submitted; pending formal reply. |
| **L-02** | Final Submission Format | **PENDING LECTURER CONFIRMATION** | HarithKeshan | Draft assembled in reviewable GitHub-flavored Markdown master (`docs/report/NOV_01_REPORT_DRAFT.md`). If institutional portal requires PDF/DOCX, it will be rendered externally without committing bulky binary artifacts into git. |
| **L-03** | Submission Portal & Deadline | **PENDING LECTURER CONFIRMATION** | Suthankan1 | Milestone target is November 1, 2026, 23:59 local time. Official LMS link and submission dropbox instructions to be reconfirmed with module coordinator. |
| **L-04** | Live Demonstration Protocol | **PENDING LECTURER CONFIRMATION** | Suthankan1 / Eric | Submission handoff includes comprehensive 11-step local reproduction runbook. Team is prepared to conduct live demonstration or submit unedited screen capture walk-through. |

---

## 7. Approval Signatures

By signing below, designated reviewers affirm that the cited evidence has been independently examined, the technical claims conform to the actual repository state on branch `test`, and no fabricated test results or approvals are presented.

- **Report Coordinator:** `HarithKeshan` — *Signed 2026-10-22* (PR #87 / Issue #19)
- **Platform & Security Lead:** `Suthankan1` — *Signed 2026-10-22* (Issue #1, #2, #3, #18, #36, #37, #44, #47)
- **Independent QA Auditor:** `Vibhushan-2003` — *Signed 2026-10-22* (PR #76, PR #77)
- **Independent Reproduction Auditor:** `EricPraveen` — *Signed 2026-10-22* (PR #80, PR #85)
