# SwiftDrop — Enterprise Application Development (EAD)
## Academic Architecture & Design Report: November 1 Milestone Baseline

---

### Document Metadata
- **Project Title:** SwiftDrop — Enterprise Parcel Delivery & Logistics Platform
- **Course / Module:** Enterprise Application Development (EAD)
- **Document Version:** v1.3-draft (November 1 Milestone Submission Master Draft)
- **Date:** October 22, 2026
- **Repository:** [`https://github.com/Suthankan1/swiftdrop`](https://github.com/Suthankan1/swiftdrop)
- **Target Git Branch:** `test`
- **Academic Report Lead:** HarithKeshan ([@Harithkeshan](https://github.com/Harithkeshan), Issue #19)
- **Architecture & Security Sign-Off:** Suthankan1 ([@Suthankan1](https://github.com/Suthankan1), Issue #1, #2, #3, #36, #37)
- **Target Milestone:** November 1, 2026 (Design Report + Demonstrable Initial Working Logins)
- **Academic Group Notice:** Nine-member team ([Group-size exception pending written lecturer confirmation](#24-limitations-and-unresolved-questions))

---

## Table of Contents
1. [Title Page & Project Metadata](#document-metadata)
2. [Executive Summary / Abstract](#2-executive-summary--abstract)
3. [Project Introduction](#3-project-introduction)
4. [Problem Statement](#4-problem-statement)
5. [Project Objectives](#5-project-objectives)
6. [Functional Requirements](#6-functional-requirements)
7. [Non-Functional Requirements](#7-non-functional-requirements)
8. [System Stakeholders & User Roles](#8-system-stakeholders--user-roles)
9. [Use-Case Analysis](#9-use-case-analysis)
10. [System Context](#10-system-context)
11. [Logical System Architecture](#11-logical-system-architecture)
12. [Technology Selection & Justification](#12-technology-selection--justification)
13. [Service Responsibilities & Microservice Boundaries](#13-service-responsibilities--microservice-boundaries)
14. [Multi-Protocol Design: REST, GraphQL, gRPC, Kafka & SSE](#14-multi-protocol-design-rest-graphql-grpc-kafka--sse)
15. [Authentication & Authorization Design](#15-authentication--authorization-design)
16. [Activity Diagrams & Operational Workflows](#16-activity-diagrams--operational-workflows)
17. [Sequence Diagrams](#17-sequence-diagrams)
18. [Logical Database Design](#18-logical-database-design)
19. [Parcel Lifecycle & State Transition Rules](#19-parcel-lifecycle--state-transition-rules)
20. [Security, Threat Modeling & Reliability Rationale](#20-security-threat-modeling--reliability-rationale)
21. [Current Implementation Status](#21-current-implementation-status)
22. [Testing & Verification Evidence](#22-testing--verification-evidence)
23. [Demonstration & Clean-Clone Reproduction Procedure](#23-demonstration--clean-clone-reproduction-procedure)
24. [Limitations, Open Decisions & Unresolved Questions](#24-limitations-open-decisions--unresolved-questions)
25. [Team Contribution Matrix](#25-team-contribution-matrix)
26. [Review & Approval Matrix](#26-review--approval-matrix)
27. [Conclusion & Future Work](#27-conclusion--future-work)
28. [References & Appendices](#28-references--appendices)

---

## 2. Executive Summary / Abstract

**SwiftDrop** is a multi-tenant enterprise logistics platform designed to orchestrate end-to-end parcel transportation, merchant e-commerce fulfillment, automated dispatching, and courier delivery management. This document constitutes the comprehensive academic architectural design report for the **November 1, 2026 academic milestone**.

In strict accordance with academic integrity and project discipline, this report explicitly separates the **target production microservice architecture (v1.3)** from the **demonstrably implemented November 1 vertical slice**:
- **Target Specification (Planned):** An event-driven, polyglot protocol architecture spanning four business microservices (*Accounts & Partners, Pricing & Zones, Delivery Operations, Communications*), an edge Spring Cloud Gateway, a dedicated read-only GraphQL Query API, transactional outbox Kafka event publishing, internal synchronous gRPC Remote Procedure Calls, Server-Sent Events (SSE) live parcel tracking, and per-service PostgreSQL database ownership.
- **Implemented Working Baseline (Verified):** A demonstrable, end-to-end authenticated vertical slice comprising a Single Page Application (React 19, TypeScript, Vite) with in-memory token management; Keycloak 26.8.1 Identity Provider configured with RFC 7636 Authorization Code Flow + S256 PKCE, native email verification, TOTP MFA, and live Google OIDC brokerage; Spring Cloud Gateway enforcing edge token validation and header sanitization; and Accounts & Partners Service (Java 21, Spring Boot 4.1.1) operating as an OAuth2 Resource Server backed by PostgreSQL 16 with Flyway migrations.
- **Empirical Security Verification:** An automated security harness verifying 40 distinct security scenarios (36 passed, 0 failed, 4 blocked by planned business endpoints), independent clean-clone setup reproduction on fresh hardware (L10 PASS), and real user-completed Google OIDC login (L03 PASS).

---

## 3. Project Introduction

Modern e-commerce logistics demands fault-tolerant coordination across disparate actors operating in high-concurrency environments. Traditional monolithic courier systems frequently suffer from cascading failures during flash-sale spikes, tight coupling between operational parcel updates and customer notification engines, and catastrophic single-point-of-failure vulnerabilities in centralized database schemas.

SwiftDrop resolves these architectural anti-patterns through a modern, cloud-native microservices topology. Developed under the Enterprise Application Development (EAD) curriculum, SwiftDrop establishes a scalable platform capable of serving individual retail shippers, high-volume corporate merchants, field couriers executing physical deliveries, dispatch coordinators balancing zone workloads, and platform administrators governing business rules.

---

## 4. Problem Statement

Contemporary last-mile logistics operations face severe technological and architectural challenges:
1. **Coupled Read/Write Contention:** Real-time customer tracking queries overwhelm transactional databases recording rapid courier status updates.
2. **Distributed Data Integrity without 2PC:** Dual-write vulnerabilities between relational databases and message brokers risk silent message loss or distributed lock contention.
3. **Tenant & Identity Boundaries:** Inadequate edge perimeter security allows spoofed identity headers (`X-User-Id`), Insecure Direct Object References (IDOR), and cross-tenant merchant data leakage.
4. **Fragile Asynchronous Integrations:** Unreliable third-party notification providers (SMS/Email/Webhooks) block core parcel transition transactions if invoked synchronously.

SwiftDrop addresses these challenges by establishing strict service boundaries, bounded database ownership, asynchronous event-driven decoupling, and dual-layer defense-in-depth security.

---

## 5. Project Objectives

1. **Architectural Boundary Enforcement:** Partition business responsibilities across four independent business services without shared database schemas or shared entity libraries.
2. **Protocol Optimization:** Allocate communication protocols according to workload characteristics: REST for commands, read-only GraphQL for composite dashboards, gRPC for typed internal RPCs, Kafka for domain events, and SSE for tracking.
3. **Defense-in-Depth Identity:** Implement standards-compliant OAuth2/OIDC authentication using Keycloak, with S256 PKCE in the browser, edge gateway sanitization, and independent resource-server JWT signature and audience verification.
4. **Academic Transparency:** Maintain total fidelity between documented designs and actual running code, cleanly distinguishing verified login implementations from planned future components.

---

## 6. Functional Requirements

### 6.1 Customer Requirements
- **FR-C01:** Self-registration via native email/password with email verification or Google OAuth2 single sign-on.
- **FR-C02:** Multi-factor authentication via RFC 6238 time-based one-time passwords (TOTP).
- **FR-C03:** Instant delivery fee quotation based on origin, destination, parcel weight, and delivery speed tier.
- **FR-C04:** Single-parcel booking with sender/recipient addresses, declared item value, and special delivery instructions.
- **FR-C05:** Real-time parcel status tracking via public tracking number without authentication.
- **FR-C06:** Historical delivery log access for authenticated customer accounts.

### 6.2 Merchant Requirements
- **FR-M01:** Dedicated merchant registration requiring business name and company registration number.
- **FR-M02:** Decoupled administrative approval lifecycle (`PENDING` -> `APPROVED` / `REJECTED` / `SUSPENDED`).
- **FR-M03:** Bulk parcel booking via REST API using 256-bit tenant-scoped API keys.
- **FR-M04:** Real-time operational order status inspection.
- **FR-M05:** Automated webhook subscription management for status change notifications.

### 6.3 Courier Requirements
- **FR-CR01:** Secure mobile-responsive workspace accessible only to provisioned `COURIER` identities.
- **FR-CR02:** Active assigned delivery task queue inspection.
- **FR-CR03:** Status transition execution (`OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED_ATTEMPT`).
- **FR-CR04:** Proof-of-delivery (PoD) capture including recipient signature and delivery confirmation notes.
- **FR-CR05:** Delivery exception recording with standardized failure reason codes.

### 6.4 Dispatcher Requirements
- **FR-D01:** Global unassigned parcel queue monitoring across geographic delivery zones.
- **FR-D02:** Courier workload monitoring (active deliveries per courier).
- **FR-D03:** Manual and automated courier parcel assignment.
- **FR-D04:** Delivery exception reassignment and route rescheduling.

### 6.5 Administrator Requirements
- **FR-A01:** Platform-wide merchant application review and approval/rejection.
- **FR-A02:** Controlled staff identity provisioning and role assignment (`COURIER`, `DISPATCHER`, `ADMIN`).
- **FR-A03:** Geographic pricing zone creation and pricing tier configuration.
- **FR-A04:** System-wide operational audit log and delivery metrics inspection.

---

## 7. Non-Functional Requirements

- **NFR-SEC-01 (Stateless Edge Security):** Zero token persistence in browser `localStorage` or `sessionStorage`; tokens held in JavaScript closure memory only.
- **NFR-SEC-02 (Anti-Spoofing):** API Gateway strictly strips caller-supplied identity headers (`X-User-Id`, `X-Keycloak-Sub`, `X-Roles`, `X-User-Email`).
- **NFR-SEC-03 (Resource Server Autonomy):** Downstream business services independently verify JWT signatures against Keycloak JWKS, validating issuer, audience (`swiftdrop-api`), and expiration.
- **NFR-PERF-01 (API Latency):** Gateway routing latency < 15ms; core service read response time < 50ms at 95th percentile.
- **NFR-REL-01 (At-Least-Once Delivery):** Zero parcel state change loss via Transactional Outbox pattern; message broker downtime does not abort business database transactions.
- **NFR-REL-02 (Consumer Idempotency):** All asynchronous Kafka event consumers enforce deduplication using unique event IDs.
- **NFR-MAINT-01 (Database Isolation):** Zero cross-service database access; services communicate across network boundaries exclusively via approved APIs or events.

---

## 8. System Stakeholders & User Roles

| Role Name | Realm Role Claim | Self-Registration | Target Landing | Primary Permissions |
|---|---|---|---|---|
| **CUSTOMER** | `CUSTOMER` | Allowed (Email / Google) | `/customer` | Book parcels, view own profile, read own order history. |
| **MERCHANT** | `MERCHANT` | Allowed (Onboarding) | `/merchant` | Bulk booking, API key management, webhook setup (requires `APPROVED` status). |
| **COURIER** | `COURIER` | **Forbidden** (Admin Only) | `/courier` | View assigned parcels, update transit states, submit proof of delivery. |
| **DISPATCHER** | `DISPATCHER` | **Forbidden** (Admin Only) | `/dispatcher` | View unassigned queue, assign parcels to couriers, monitor zone workload. |
| **ADMIN** | `ADMIN` | **Forbidden** (Admin Only) | `/admin` | Approve merchants, provision staff profiles, manage pricing zones, system audit. |

---

## 9. Use-Case Analysis

The functional scope is formally partitioned across four primary role groupings, illustrated in Figures 01 through 04:

### 9.1 Customer Use Cases (Figure 01)
- **Diagram Reference:** [Customer Use Cases (`docs/diagrams/rendered/01_customer_use_cases.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/01_customer_use_cases.svg)  
- **Editable Source:** [`docs/diagrams/editable/01_customer_use_cases.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/01_customer_use_cases.drawio)  
- **Reviewer:** Customer Pair (Gajanthan & Sinthuha) — **Reviewed**  
- **Description:** Depicts public quote calculation, anonymous tracking, and authenticated customer journeys including profile inspection, booking confirmation, and delivery cancellation prior to courier assignment.

### 9.2 Merchant Use Cases (Figure 02)
- **Diagram Reference:** [Merchant Use Cases (`docs/diagrams/rendered/02_merchant_use_cases.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/02_merchant_use_cases.svg)  
- **Editable Source:** [`docs/diagrams/editable/02_merchant_use_cases.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/02_merchant_use_cases.drawio)  
- **Reviewer:** Merchant Pair (Eric & Thuvarahan) — **Reviewed**  
- **Description:** Highlights merchant registration, pending approval state rendering, bulk CSV/JSON parcel creation, and webhook delivery subscription management.

### 9.3 Courier Use Cases (Figure 03)
- **Diagram Reference:** [Courier Use Cases (`docs/diagrams/rendered/03_courier_use_cases.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/03_courier_use_cases.svg)  
- **Editable Source:** [`docs/diagrams/editable/03_courier_use_cases.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/03_courier_use_cases.drawio)  
- **Reviewer:** Courier Pair (Abhinaya & Vibhushan) — **Reviewed**  
- **Description:** Focuses on authenticated courier task intake, delivery acceptance, proof-of-delivery upload, and exception logging with standardized failure codes.

### 9.4 Dispatcher and Administrator Use Cases (Figure 04)
- **Diagram Reference:** [Dispatcher & Admin Use Cases (`docs/diagrams/rendered/04_dispatcher_admin_use_cases.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/04_dispatcher_admin_use_cases.svg)  
- **Editable Source:** [`docs/diagrams/editable/04_dispatcher_admin_use_cases.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/04_dispatcher_admin_use_cases.drawio)  
- **Reviewer:** Dispatcher/Admin Pair (Pushmitha & HarithKeshan) — **Reviewed (PR #83)**  
- **Description:** Details unassigned parcel queue filtering, manual courier workload assignment, merchant approval workflows, and pricing zone administration.

---

## 10. System Context

- **Diagram Reference:** [System Context Diagram (`docs/diagrams/rendered/05_system_context.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/05_system_context.svg)  
- **Editable Source:** [`docs/diagrams/editable/05_system_context.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/05_system_context.drawio)  
- **Reviewer:** Suthankan1 (Platform) & Entire Team — **Reviewed**  
- **Caption:** *Figure 05: SwiftDrop High-Level System Context within the Enterprise Ecosystem.*

Figure 05 defines the boundary of the SwiftDrop platform. External entities include:
1. **Human Actors:** Customers, Merchants, Couriers, Dispatchers, and Administrators accessing the system via web and mobile browsers.
2. **Identity Providers:** Keycloak 26.8.1 serving as the authoritative OpenID Connect realm broker, federating corporate Google Accounts.
3. **External Merchant Systems:** E-commerce servers invoking bulk order APIs and receiving signed HTTPS webhook notifications.
4. **Third-Party Telemetry & Messaging:** SMTP mail relays (Mailpit in development) and external SMS gateways.

---

## 11. Logical System Architecture

- **Diagram Reference:** [Logical Container Architecture (`docs/diagrams/rendered/06_logical_architecture.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/06_logical_architecture.svg)  
- **Editable Source:** [`docs/diagrams/editable/06_logical_architecture.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/06_logical_architecture.drawio)  
- **Reviewer:** Suthankan1 (Platform Lead) — **Signed Off (`PLATFORM_SIGNOFF_NOV01.md`)**  
- **Caption:** *Figure 06: Target Logical Microservices Container Architecture (v1.3 Baseline).*

Figure 06 represents the target production architecture organized into five distinct tiers:
1. **Presentation Tier:** React 19 SPA running in user browser contexts, maintaining tokens in closure memory.
2. **API Edge Tier:** Spring Cloud Gateway on port 8080 performing edge token inspection, CORS policy enforcement, and route distribution.
3. **Business Logic Microservices:**
   - **Accounts & Partners Service (:8081):** User profiles, staff operational records, and merchant business entities.
   - **Pricing & Zones Service (:8082):** Geographic zones, tiered pricing matrices, and quote issuance.
   - **Delivery Operations Service (:8083):** Parcel state machine, assignments, proof-of-delivery, and transactional outbox.
   - **Communications Service (:8084):** Email/SMS dispatching, merchant webhook signing, and exponential retry engine.
4. **Read Composite Tier:** Read-only GraphQL Query API (:8085) aggregating multi-service dashboard data via internal gRPC RPCs.
5. **Event & Persistence Tier:** Independent PostgreSQL databases for each service, and an Apache Kafka KRaft cluster for async domain events.

---

## 12. Technology Selection & Justification

| Technology | Architectural Purpose | Technical Justification & Tradeoffs |
|---|---|---|
| **React 19 + TypeScript + Vite** | Single Page Application (SPA) UI | Component reusability, strict compile-time type safety, lightweight build bundles, rapid HMR during development. |
| **Spring Boot 4.1.1 (Java 21 LTS)** | Core Business Services | Enterprise-grade transaction management, mature data access (JPA/Hibernate), Virtual Threads for high-throughput I/O, native OAuth2 Resource Server integration. |
| **Spring Cloud Gateway (Oakwood 2025.1.3)** | API Edge Router | Non-blocking reactive Netty core, centralized edge routing, declarative CORS policies, and edge header sanitization. |
| **Keycloak 26.8.1 (Quarkus Engine)** | OIDC / OAuth2 Identity Provider | Standards-compliant OpenID Connect provider; out-of-the-box S256 PKCE support, native email verification, TOTP MFA, and seamless Google identity brokerage. Eliminates custom authentication vulnerabilities. |
| **PostgreSQL 16** | Transactional Relational Storage | Robust ACID guarantees, JSONB semi-structured support, row-level concurrency, transactional DDL, and mature Flyway migration tooling. |
| **Apache Kafka 3.7+ (KRaft)** | Asynchronous Event Backbone | High-throughput distributed log; KRaft eliminates ZooKeeper operational complexity; consumer groups enable independent horizontal scaling; durable log retention enables replaying events. |
| **gRPC / Protocol Buffers (v3)** | Internal Synchronous RPC | Compact binary serialization, HTTP/2 multiplexing, strongly-typed contract stubs, and strict request deadline propagation. |
| **GraphQL (Read-Only Query Engine)** | Composite Dashboard Read API | Single-roundtrip data retrieval for complex dispatcher and admin screens; eliminates over-fetching and under-fetching; restricted to read-only queries to prevent state-mutation bypasses. |
| **Server-Sent Events (SSE)** | Unidirectional Live Status Push | Native browser HTTP streaming, automatic reconnection handling, and firewall friendliness. Far simpler and more reliable than full-duplex WebSockets for one-way status telemetry. |
| **Docker Compose** | Local Developer Environment | Deterministic, reproducible local container orchestration for Keycloak, PostgreSQL, Mailpit, and core services. |

---

## 13. Service Responsibilities & Microservice Boundaries

```text
+-----------------------+-----------------------+-----------------------+-----------------------+
|  Accounts & Partners  |    Pricing & Zones    |  Delivery Operations  |    Communications     |
|     Service (:8081)   |     Service (:8082)   |     Service (:8083)   |     Service (:8084)   |
+-----------------------+-----------------------+-----------------------+-----------------------+
| - Customer profiles   | - Delivery zones      | - Parcel state machine| - Customer email / SMS|
| - Merchant approval   | - Tiered pricing rules| - Courier assignments | - Webhook signing     |
| - Staff read models   | - Quote validation    | - Proof of delivery   | - Retry backoff / DLT |
| - Provisioning checks | - Distance matrices   | - Transactional outbox| - Template rendering  |
+-----------------------+-----------------------+-----------------------+-----------------------+
| DB: accounts_db       | DB: pricing_db        | DB: delivery_db       | DB: communications_db |
+-----------------------+-----------------------+-----------------------+-----------------------+
```

### Boundary Invariants:
1. **Zero Database Sharing:** No service may connect directly to another service's database. Cross-service SQL joins are strictly forbidden.
2. **Zero Shared Entity JARs:** Microservices maintain independent domain models. DTO contracts are negotiated via explicit OpenAPI, Protobuf, or JSON schemas.
3. **Delivery Service Primacy:** Only Delivery Operations Service may transition parcel states. Pricing, Accounts, and Communications react to events published by Delivery.

---

## 14. Multi-Protocol Design: REST, GraphQL, gRPC, Kafka & SSE

```text
+---------------------+-------------------+---------------------------------------------------------+
| Communication Path  | Protocol          | Architectural Justification & Reliability Bounds        |
+---------------------+-------------------+---------------------------------------------------------+
| Browser -> Keycloak | OIDC Code + PKCE  | Industry standard for public SPAs. Eliminates client    |
|                     | (brokered Google) | secrets. Decouples identity from business logic.        |
+---------------------+-------------------+---------------------------------------------------------+
| Browser -> Gateway  | REST / HTTP       | Stateless commands and simple reads. Universal browser  |
| -> Core Services    | (JSON)            | support, standard HTTP caching, and edge rate-limiting. |
+---------------------+-------------------+---------------------------------------------------------+
| External Merchant   | REST / HTTPS      | Familiar REST API standard. Authenticated via 256-bit   |
| -> Gateway -> Core  | (JSON)            | scoped API keys, bound strictly to merchant tenant.     |
+---------------------+-------------------+---------------------------------------------------------+
| Browser -> Gateway  | GraphQL over HTTP | Aggregates multi-service dashboard screens in one       |
| -> GraphQL Query API| (Read-Only)       | roundtrip. Prevents over-fetching; strictly read-only.  |
+---------------------+-------------------+---------------------------------------------------------+
| GraphQL API -> Core | gRPC / Protobuf   | High-performance internal binary RPC. Strongly typed    |
| Microservices       | (Internal)        | contracts, multiplexed HTTP/2, sub-millisecond latency. |
+---------------------+-------------------+---------------------------------------------------------+
| Delivery -> Accounts| gRPC / Protobuf   | Synchronous consistency checks (courier eligibility and |
| / Pricing           | (Internal)        | quote validation). Enforces deadlines and fail-closed.  |
+---------------------+-------------------+---------------------------------------------------------+
| Delivery Outbox     | Kafka KRaft       | Transactional Outbox pattern guarantees at-least-once   |
| -> Kafka Event Bus  | (Pub/Sub)         | publishing without 2PC distributed transactions.        |
+---------------------+-------------------+---------------------------------------------------------+
| Kafka -> Communic.  | Kafka Consumer    | Decouples long-running email/SMS and external webhook   |
| & Tracking Relay    | Groups            | deliveries. Independent scaling and consumer idempotency|
+---------------------+-------------------+---------------------------------------------------------+
| Delivery -> Browser | Server-Sent Events| Lightweight, one-way server-to-client push for tracking.|
| via Gateway         | (SSE)             | Replaces expensive polling; client refreshes via REST.  |
+---------------------+-------------------+---------------------------------------------------------+
| Communications      | HTTPS Webhooks    | Asynchronous notification of merchant endpoints with    |
| -> Merchant Systems | (HMAC-SHA256)     | cryptographic HMAC signatures and bounded retry backoff.|
+---------------------+-------------------+---------------------------------------------------------+
```

---

## 15. Authentication & Authorization Design

- **Diagram Reference:** [Google Login via Keycloak Sequence (`docs/diagrams/rendered/13_google_oidc_sequence.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/13_google_oidc_sequence.svg)  
- **Editable Source:** [`docs/diagrams/editable/13_google_oidc_sequence.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/13_google_oidc_sequence.drawio)  
- **Reviewer:** Suthankan1 (Platform Lead) & Gajanthan (Customer FE) — **Reviewed**  
- **Caption:** *Figure 13: Brokered Google OpenID Connect Authentication Sequence with S256 PKCE.*

### Architectural Mechanics:
1. **Authorization Code Flow + S256 PKCE:** The React SPA dynamically generates a cryptographic random code verifier and computes its SHA-256 code challenge (`BASE64URL(SHA256(verifier))`). The verifier is held strictly in browser memory.
2. **Google Identity Brokerage:** When a user selects "Continue with Google", Keycloak initiates an upstream OIDC handshake with Google Accounts (`accounts.google.com`). Upon successful Google consent, Keycloak imports or links the user identity, strictly assigning the default `CUSTOMER` composite role.
3. **Token Issuance:** Keycloak issues a signed RS256 JWT access token containing standard claims (`sub`, `iss`, `aud="swiftdrop-api"`, `exp`, `nbf`) and the approved realm role array (`realm_access.roles`).
4. **Dual-Layer Resource Protection:**
   - **Gateway Edge:** Validates token signature and expiration, stripping untrusted caller headers.
   - **Accounts Service:** Operates as an independent Spring Security OAuth2 Resource Server. The `NimbusJwtDecoder` validates signatures against Keycloak's public JWKS (`/protocol/openid-connect/certs`). `KeycloakRoleExtractor` extracts roles and maps them to Spring Security `GrantedAuthority` records (`ROLE_CUSTOMER`, `ROLE_COURIER`, etc.).
5. **Privileged Staff Protection:** Staff roles (`COURIER`, `DISPATCHER`, `ADMIN`) cannot be acquired through public registration or client tampering; they require explicit administrative provisioning.

---

## 16. Activity Diagrams & Operational Workflows

### 16.1 Customer Quote and Booking Activity (Figure 07)
- **Diagram Reference:** [Quote & Booking Activity (`docs/diagrams/rendered/07_quote_booking_activity.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/07_quote_booking_activity.svg)  
- **Editable Source:** [`docs/diagrams/editable/07_quote_booking_activity.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/07_quote_booking_activity.drawio)  
- **Reviewer:** Customer & Dispatcher Pairs — **Reviewed**  
- **Caption:** *Figure 07: Customer Parcel Quote Generation and Booking Workflow.*

Illustrates address validation, zone resolution, rate calculation, customer quote acceptance, payment authorization hold, and unassigned parcel record creation.

### 16.2 Dispatcher Courier Assignment Activity (Figure 08)
- **Diagram Reference:** [Courier Assignment Activity (`docs/diagrams/rendered/08_assignment_activity.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/08_assignment_activity.svg)  
- **Editable Source:** [`docs/diagrams/editable/08_assignment_activity.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/08_assignment_activity.drawio)  
- **Reviewer:** Dispatcher & Courier Pairs — **Reviewed (PR #83)**  
- **Caption:** *Figure 08: Dispatcher Workload Inspection and Courier Assignment Workflow.*

Details the unassigned parcel queue polling, courier workload and capacity threshold validation, optimistic locking on parcel assignment records, and courier notification triggering.

### 16.3 Courier Status, Kafka and Notifications Activity (Figure 09)
- **Diagram Reference:** [Status & Event Notification Activity (`docs/diagrams/rendered/09_status_event_activity.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/09_status_event_activity.svg)  
- **Editable Source:** [`docs/diagrams/editable/09_status_event_activity.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/09_status_event_activity.drawio)  
- **Reviewer:** Courier, Merchant & Platform Teams — **Reviewed**  
- **Caption:** *Figure 09: Courier State Update, Transactional Outbox Publishing, and Multi-Channel Notification Pipeline.*

Covers courier pickup confirmation, delivery attempt execution, transactional outbox record creation, Kafka publication, SSE tracking broadcast, and Communications webhook dispatch.

---

## 17. Sequence Diagrams

### 17.1 Customer Delivery Booking Sequence (Figure 10)
- **Diagram Reference:** [Customer Booking Sequence (`docs/diagrams/rendered/10_customer_delivery_sequence.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/10_customer_delivery_sequence.svg)  
- **Editable Source:** [`docs/diagrams/editable/10_customer_delivery_sequence.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/10_customer_delivery_sequence.drawio)  
- **Reviewer:** Customer Pair — **Reviewed**  
- **Caption:** *Figure 10: End-to-End Customer Booking Sequence across Gateway, Pricing, and Delivery.*

### 17.2 Merchant Bulk Order Sequence (Figure 11)
- **Diagram Reference:** [Merchant Order Sequence (`docs/diagrams/rendered/11_merchant_order_sequence.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/11_merchant_order_sequence.svg)  
- **Editable Source:** [`docs/diagrams/editable/11_merchant_order_sequence.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/11_merchant_order_sequence.drawio)  
- **Reviewer:** Merchant Pair — **Reviewed**  
- **Caption:** *Figure 11: Merchant B2B Bulk Order Ingestion Sequence with Scoped API Key Validation.*

### 17.3 Courier Status and Webhook Sequence (Figure 12)
- **Diagram Reference:** [Courier Status Sequence (`docs/diagrams/rendered/12_courier_status_sequence.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/12_courier_status_sequence.svg)  
- **Editable Source:** [`docs/diagrams/editable/12_courier_status_sequence.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/12_courier_status_sequence.drawio)  
- **Reviewer:** Courier & Merchant Pairs — **Reviewed**  
- **Caption:** *Figure 12: Delivery Execution, Proof Upload, Kafka Event Fanout, and Merchant Webhook Execution.*

---

## 18. Logical Database Design

SwiftDrop strictly enforces per-service database ownership. Each microservice manages its schema via additive Flyway migrations without foreign keys spanning database boundaries:

### 18.1 Delivery Operations Logical ERD (Figure 14)
- **Diagram Reference:** [Delivery ERD (`docs/diagrams/rendered/14_delivery_erd.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/14_delivery_erd.svg)  
- **Editable Source:** [`docs/diagrams/editable/14_delivery_erd.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/14_delivery_erd.drawio)  
- **Reviewer:** Delivery & Courier Teams (Vibhushan & Abhinaya, PR #77) — **Reviewed**  
- **Caption:** *Figure 14: Delivery Operations Schema (`delivery_db`) including Parcels, Assignments, Proofs, and Outbox.*
- **Core Entities:** `parcels`, `delivery_assignments`, `proof_of_deliveries`, `parcel_events`, `transactional_outbox`.

### 18.2 Accounts & Pricing Logical ERD (Figure 15)
- **Diagram Reference:** [Accounts & Pricing ERD (`docs/diagrams/rendered/15_accounts_pricing_erd.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/15_accounts_pricing_erd.svg)  
- **Editable Source:** [`docs/diagrams/editable/15_accounts_pricing_erd.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/15_accounts_pricing_erd.drawio)  
- **Reviewer:** Sinthuha, Gajanthan, HarithKeshan — **Reviewed**  
- **Caption:** *Figure 15: Logical Schema for Accounts (`accounts_db`) and Pricing (`pricing_db`).*
- **Accounts Entities (Implemented in `accounts_db`):**
  - `account_profiles`: Customer identity bindings, verified emails, phone numbers, immutable trigger.
  - `merchants`: Merchant tenant profiles, business registration numbers, approval status (`PENDING`, `APPROVED`, `SUSPENDED`, `REJECTED`).
  - `staff_profiles`: Staff operational profiles (`COURIER`, `DISPATCHER`, `ADMIN`), staff IDs, assigned zones, status (`ACTIVE`, `SUSPENDED`, `INACTIVE`).
- **Pricing Entities (Planned in `pricing_db`):** `pricing_zones`, `pricing_tiers`, `rate_sheets`, `quote_records`.

### 18.3 Communications Logical ERD (Figure 16)
- **Diagram Reference:** [Communications ERD (`docs/diagrams/rendered/16_communications_erd.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/16_communications_erd.svg)  
- **Editable Source:** [`docs/diagrams/editable/16_communications_erd.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/16_communications_erd.drawio)  
- **Reviewer:** Thuvarahan & Suthankan1 (PR #79) — **Reviewed**  
- **Caption:** *Figure 16: Communications Schema (`communications_db`) including Webhook Subscriptions, Delivery Logs, and Dead-Letter Records.*
- **Core Entities:** `webhook_subscriptions`, `webhook_delivery_attempts`, `notification_logs`, `idempotent_event_consumers`.

---

## 19. Parcel Lifecycle & State Transition Rules

- **Diagram Reference:** [Parcel State Machine (`docs/diagrams/rendered/17_parcel_state_machine.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/17_parcel_state_machine.svg)  
- **Editable Source:** [`docs/diagrams/editable/17_parcel_state_machine.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/17_parcel_state_machine.drawio)  
- **Reviewer:** Courier & Dispatcher Pairs — **Reviewed**  
- **Caption:** *Figure 17: Canonical Parcel Lifecycle Finite State Machine.*

```text
  [CREATED] --------> [ASSIGNED] --------> [PICKED_UP] --------> [IN_TRANSIT]
      |                   |                                            |
      v                   v                                            v
  [CANCELLED]         [CANCELLED]                            [OUT_FOR_DELIVERY]
                                                                  |       |
                                                                  v       v
                                                            [DELIVERED] [FAILED_ATTEMPT]
                                                                          |
                                                                          v
                                                                    [RETURNED_TO_HUB]
```

### Transition Governance:
- Transitions are exclusively owned and persisted by **Delivery Operations Service**.
- Optimistic locking (`version` column) prevents concurrent courier assignment race conditions.
- Terminal states: `DELIVERED`, `CANCELLED`, and `RETURNED_TO_HUB`.
- Every successful state transition persists an event row in `transactional_outbox` within the same database transaction.

---

## 20. Security, Threat Modeling & Reliability Rationale

- **Diagram Reference:** [Kafka Reliability & SSE Topology (`docs/diagrams/rendered/18_kafka_sse_topology.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/18_kafka_sse_topology.svg)  
- **Editable Source:** [`docs/diagrams/editable/18_kafka_sse_topology.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/18_kafka_sse_topology.drawio)  
- **Reviewer:** Platform & Security Teams — **Reviewed**  
- **Caption:** *Figure 18: Reliable Transactional Outbox Publishing, Kafka KRaft Topology, and SSE Fanout.*

### Threat Mitigations:
1. **STRIDE Threat Analysis:**
   - *Spoofing:* Mitigated by OIDC S256 PKCE, asymmetric RS256 token signatures, and edge stripping of internal headers.
   - *Tampering:* PostgreSQL engine triggers (`account_identity_immutable`, `merchant_identity_immutable`, `staff_identity_immutable`) prevent updating subject or tenant bindings.
   - *Repudiation:* Audit logging across Keycloak, Gateway access logs, and PostgreSQL event tables.
   - *Information Disclosure:* RFC 7807 ProblemDetails sanitize internal server errors (zero stack traces or SQL errors returned to clients).
   - *Denial of Service:* Edge rate-limiting and connection throttling configured at Spring Cloud Gateway.
   - *Elevation of Privilege:* Dual-layer RBAC. Client-supplied roles are ignored; roles are evaluated solely from verified Keycloak claims and matched against business profiles.
2. **Reliability Mechanics:**
   - **Transactional Outbox:** Eliminates distributed transactions (2PC). Events are written to PostgreSQL `transactional_outbox` in the same commit as business entities, then polled and published to Kafka.
   - **Consumer Idempotency:** Consumers track processed event IDs in `idempotent_event_consumers` tables to ensure exact-once processing semantics over at-least-once Kafka transport.

---

## 21. Current Implementation Status

### Honest Scope Separation Table:

| Architectural Component | Planned Target Specification | November 1 Implemented Baseline | Implementation Status | Evidence / Test Reference |
|---|---|---|---|---|
| **Frontend Shell** | React 19 SPA for all 5 roles | React 19 + Vite SPA, `/login`, `/customer`, `/merchant`, `/courier`, `/dispatcher`, `/admin` | **IMPLEMENTED** | 12 test files, 143 tests passed (`pnpm test`) |
| **Authentication Broker** | Keycloak 26.8.1 with Google OIDC & TOTP | Keycloak 26.8.1, `swiftdrop` realm, PKCE, Google broker, TOTP MFA, Mailpit | **IMPLEMENTED** | L01, L02, L03, L04, L10 verified |
| **Edge API Gateway** | Spring Cloud Gateway routing all services | Gateway on :8080, edge JWT validation, CORS, header stripping, `/users/me` route | **IMPLEMENTED** | 19 automated tests passed |
| **Accounts Service** | Full profiles, merchant & staff data | Accounts on :8081, PostgreSQL 16, Flyway V1-V3, customer/merchant/staff reads | **IMPLEMENTED** | 105 automated tests passed (`mvn test`) |
| **Delivery Operations** | Full parcel state machine & assignments | Domain models & ERD designed (Figure 14) | **PLANNED** | Awaits post-Nov 1 implementation |
| **Pricing & Zones** | Tiered pricing calculation engine | Domain models & ERD designed (Figure 15) | **PLANNED** | Awaits post-Nov 1 implementation |
| **Communications** | Webhook engine with retry & SMS | Webhook models & ERD designed (Figure 16) | **PLANNED** | Awaits post-Nov 1 implementation |
| **GraphQL Query API** | Composite read-only dashboard API | Architectural contract specified (`GRAPHQL.md`) | **PLANNED** | Awaits post-Nov 1 implementation |
| **gRPC Service Bus** | Internal synchronous RPC contracts | Proto specifications draft (`GRPC.md`) | **PLANNED** | Awaits post-Nov 1 implementation |
| **Kafka Event Bus** | Transactional outbox event streaming | Topology specified (Figure 18, `KAFKA.md`) | **PLANNED** | Awaits post-Nov 1 implementation |
| **Kubernetes (Bonus)** | Helm charts, ingress, cluster autoscaling | Bonus deployment model designed (Figure 19) | **PLANNED (Bonus)** | Architectural design only |

---

## 22. Testing & Verification Evidence

### 22.1 Acceptance Criteria Matrix (L01 – L12)

| ID | Description | Primary Owner | Status | Verified Evidence & Commit SHA |
|---|---|---|---|---|
| **L01** | Start Keycloak + Gateway + Accounts login stack | Suthankan1 | **PASS** | Live stack on 5173, 8080, 8081, 8090, 5438, 8025 verified; readiness 13/13 PASS. |
| **L02** | Customer sign-up & login, profile by Keycloak `sub` | Suthankan1 / Sinthuha | **PASS** | PKCE exchange; Accounts JIT creates profile row bound to `keycloak_sub`. Passwords in Keycloak only. |
| **L03** | Real Google login via Keycloak OIDC broker | Suthankan1 / Gajanthan | **PASS** | Verified live on 2026-10-20 (commit `60faff2` / `bf875a0`). Google test user logged in; profile loaded via Gateway HTTP 200. |
| **L04** | Logout & session teardown | Suthankan1 | **PASS** | RP-initiated logout clears Keycloak SSO and in-memory tokens; returns to `/`. |
| **L05** | Five-role entry views & denial guards | All Role Pairs | **PASS** | Protected routes for Customer, Merchant, Courier, Dispatcher, and Admin tested and verified in frontend. |
| **L06** | JWT rejection (missing, expired, untrusted) | Suthankan1 / Vibhushan | **PASS** | Independent Security QA by Vibhushan (`b974af0`, PR #76); 27 tests across Gateway & Accounts return HTTP 401. |
| **L07** | Role authorization denial | Suthankan1 / Vibhushan | **PASS** | Non-approved roles receive 403 Forbidden RFC 7807 ProblemDetail; public signup confined to CUSTOMER. |
| **L08** | Customer A vs B profile & resource isolation | Sinthuha / Vibhushan | **PASS** | Cross-customer lookup blocked; spoofed headers stripped by Gateway and ignored by Accounts. |
| **L09** | Cancellation / provider down handling | Suthankan1 | **PASS** | OAuth cancellation (`access_denied`) and provider downtime fail safely without establishing session. |
| **L10** | Independent clean-clone reproduction | Eric Praveen | **PASS** | Independent reproduction on macOS Darwin arm64 (PR #85, commit `b0e09ff`); 13/13 preflight, 4/4 demo PASS. |
| **L11** | Secrets hygiene in source control | Suthankan1 | **PASS** | `.gitignore` strictly protects `.env`; zero secrets, credentials, or private keys committed in Git history. |
| **L12** | Report figures & integration review | All Nine Members | **PASS (This PR)** | Master report assembled; all 20 figures integrated with review register. |

### 22.2 Security E2E Test Suite Results (40 Scenarios)
- **Total Scenarios Evaluated:** 40
- **Passed:** 36 (100% of implemented attack surfaces fail-closed)
- **Failed:** 0
- **Blocked:** 4 (Downstream unmapped business endpoints: `L07-ROLE-04`, `L07-ROLE-05`, `L08-OWN-07`, `L08-OWN-08`). In accordance with repository rules, fake mock endpoints were not created; Gateway safely default-denies unmapped routes with HTTP 404/401.

---

## 23. Demonstration & Clean-Clone Reproduction Procedure

An external evaluator can deterministically reproduce the running November 1 login baseline following this exact sequence:

### Prerequisites:
- macOS, Linux, or Windows with WSL2 / PowerShell
- Docker Engine 24.0+ & Docker Compose v2.20+
- Java 21 LTS (OpenJDK / Eclipse Temurin)
- Node.js 20+ and `pnpm` 9+

### Execution Commands:
```bash
# 1. Clone repository and inspect branch
git clone https://github.com/Suthankan1/swiftdrop.git
cd swiftdrop
git switch test

# 2. Start local infrastructure containers (PostgreSQL :5438, Keycloak :8090, Mailpit :8025)
./platform/local/start-local.sh

# 3. Verify infrastructure readiness (Validates all 13 health checks)
./platform/local/check-readiness.sh

# 4. Start backend services in separate terminal windows
cd services/accounts-service && ./mvnw spring-boot:run   # Listens on port 8081
cd services/api-gateway && ./mvnw spring-boot:run        # Listens on port 8080

# 5. Start frontend SPA
cd frontend && pnpm install && pnpm dev                  # Listens on port 5173

# 6. Execute automated demonstration smoke test
python3 tests/security-e2e/demo_smoke.py

# 7. Execute complete 40-scenario security test harness
./tests/security-e2e/run-security-e2e.sh
```

### Observable Results:
1. Navigating to `http://localhost:5173/login` displays the branded SwiftDrop login portal.
2. Registering a customer triggers an activation email visible in Mailpit (`http://localhost:8025`). Clicking the link verifies the email.
3. Logging in redirects to `/customer`, retrieving the user profile through Gateway (:8080) from Accounts (:8081).
4. Direct unauthenticated request `curl -i http://localhost:8080/api/v1/users/me` returns HTTP 401 Unauthorized ProblemDetail.

---

## 24. Limitations, Open Decisions & Unresolved Questions

### 24.1 Lecturer & Institutional Confirmations (OPEN)
- **Nine-Member Team Size Exception:** The printed assignment specification references 10 members. SwiftDrop has operated with 9 named members throughout the semester. Formal written sign-off from the lecturer remains **PENDING CONFIRMATION**.
- **Submission Destination:** LMS portal requirements (PDF upload vs. GitHub repository tagging) remain **PENDING CONFIRMATION**.

### 24.2 Open Design Decisions
- **Decision DA-01 (Assignment Capacity & Concurrency):** Rules governing courier concurrent assignment limits and optimistic locking conflict retry loops remain under pair review.
- **Decision DA-02 (Admin Operational Scopes):** Boundary between automated system dispatching and emergency administrative overrides.
- **Decision CR-01 (Early Delivery Failure):** Protocol for parcel cancellations occurring while courier is in transit to pickup location.
- **Decision MR-01 (Webhook Retry Backoff):** Bounded exponential retry schedule (e.g., 5 attempts over 24 hours) prior to moving webhook payloads to dead-letter storage.

---

## 25. Team Contribution Matrix

*All estimates represent initial planning allocations, distinctly separated from verified commit and PR evidence:*

| Member Name | Role & Assigned Area | Assigned Issues | Primary Delivered Work | Verified PR Links & Commit Evidence | Peer Review Contributions | Status |
|---|---|---|---|---|---|---|
| **Suthankan1** | Platform, Security & Architecture Lead | #1, #2, #3, #36, #37, #44, #47, #50, #53, #54, #55, #64 | Full authentication stack, Keycloak OIDC, Gateway edge, Accounts security, TOTP MFA, Google broker, security harness (40 tests), platform signoff. Temporary coverage for #4, #5, #6. | PR #34 (`4ad722a`), PR #35 (`c993204`), PR #38 (`c20bb5e`), PR #39 (`56341b4`), PR #41 (`b680ce7`), PR #42 (`7ebd42a`), PR #43 (`09bd6be`), PR #45 (`c6b31bb`), PR #46 (`ab65336`), PR #48 (`b72c005`), PR #49 (`7ecfc33`), PR #50 (`3d05454`), PR #53 (`882c65e`), PR #54 (`d2f9b08`), PR #55 (`bf875a0`), PR #64 (`0b01f0a`) | Reviewed all member PRs | **Complete & Verified** |
| **Gajanthan** | Customer Frontend Lead | #4, #6, #7 | Customer journey review, SPA shell coordination, OIDC sequence & Accounts ERD review evidence mapping. | PR #82 (`e6fd107` / `e7bd967`) | Reviewed PR #35, #42, #54, #80 | **Complete & Verified** |
| **Sinthuha** | Customer Backend & Accounts Domain Steward | #5 | Accounts profile persistence model review, PostgreSQL schema validation, customer profile ownership rules. | Co-author / domain steward for Accounts schema; PR #43 & #45 sign-off | Reviewed PR #35, #38, #39, #40, #43, #45, #78, #86 | **Complete & Verified** |
| **Eric** | Merchant Frontend & Clean-Clone QA Lead | #9, #17 | Merchant role protected landing & approval-state UI; independent clean-clone reproduction (L10) on macOS hardware. | PR #80 (`466b1fb`), PR #85 (`1d4a951` / `b0e09ff`) | Reviewed PR #39, #53, #78 | **Complete & Verified** |
| **Thuvarahan** | Merchant Backend Lead | #8, #10 | Merchant approval-state provider, tenant authorization provider, Communications service review (Fig 16). | PR #78 (`f5cf48c` / `bb61427`), PR #79 (`4c1fba4`) | Reviewed PR #43, #45, #80 | **Complete & Verified** |
| **Abhinaya** | Courier Frontend Lead | #11, #15 | Courier use cases and proof review (Figs 03, 09, 12); guarded Courier role landing page shell. | PR #74 (`b974af0`), PR #75 (`6d6d7c2`) | Reviewed PR #84, #86 | **Complete & Verified** |
| **Vibhushan** | Courier Backend & Security QA Lead | #12, #18 | Delivery Operations ERD & lifecycle review (Fig 14, 17); independent security QA report verifying L06, L07, L08. | PR #76 (`826d6bd`), PR #77 (`060aea1`) | Reviewed PR #39, #45, #50, #53, #78, #83 | **Complete & Verified** |
| **Pushmitha** | Dispatcher/Admin Frontend Lead | #13, #16 | Dispatcher/Admin use-case & assignment review (Figs 04, 08); protected Dispatcher and Admin landing views. | PR #83 (`692a35e` / `4915ea7`), PR #84 (`9b8a647` / `fca90f6`) | Reviewed PR #40, #86 | **Complete & Verified** |
| **HarithKeshan** | Dispatcher/Admin Backend & Report Lead | #14, #19 | Staff profile read models (`staff_profiles` V3 migration), controlled provisioning contract; November 1 academic report assembly. | PR #86 (`91510c0` / `69243ba`), PR #87 (This PR) | Reviewed PR #34, #35, #53, #78, #83, #84 | **Complete & Verified** |

---

## 26. Review & Approval Matrix

| Report Section / Deliverable | Author(s) | Assigned Reviewer | Status | Evidence Document / PR |
|---|---|---|---|---|
| **Platform Architecture & Protocol Justification** | Suthankan1 | Team / HarithKeshan | **APPROVED** | `docs/team/PLATFORM_SIGNOFF_NOV01.md` |
| **Figure 06 Container Architecture** | Suthankan1 | All Service Owners | **APPROVED** | [`06_logical_architecture.svg`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/06_logical_architecture.svg) |
| **Customer Journey & Figures 01, 07, 10** | Gajanthan & Sinthuha | Pushmitha / Suthankan1 | **APPROVED** | `docs/report/customer_journey_review.md` |
| **Merchant Approval & Figures 02, 11, 16** | Eric & Thuvarahan | Suthankan1 / Sinthuha | **APPROVED** | PR #78, PR #79, PR #80 |
| **Courier Review & Figures 03, 09, 12** | Abhinaya & Vibhushan | HarithKeshan / Thuvarahan | **APPROVED** | `docs/milestones/NOV13_COURIER_REVIEW.md` (PR #74) |
| **Delivery ERD & Lifecycle (Figures 14, 17)** | Vibhushan | HarithKeshan / Suthankan1 | **APPROVED** | PR #77 |
| **Dispatcher/Admin Review (Figures 04, 08)** | Pushmitha & HarithKeshan | Suthankan1 / Vibhushan | **APPROVED** | `docs/reviews/DISPATCHER_ADMIN_REVIEW.md` (PR #83) |
| **Staff Profile Read Models (#14)** | HarithKeshan | Sinthuha / Suthankan1 | **APPROVED** | PR #86 (merged commit `91510c0`) |
| **Independent Security QA (L06–L08)** | Vibhushan | HarithKeshan / Suthankan1 | **APPROVED** | `ISSUE_18_INDEPENDENT_SECURITY_QA_REPORT.md` (PR #76) |
| **Independent Clean-Clone Setup (L10)** | Eric Praveen | Suthankan1 / Vibhushan | **APPROVED** | PR #85 (merged commit `1d4a951`) |
| **Academic Report Master Draft (#19)** | HarithKeshan | Suthankan1 & All Members | **REVIEW REQUESTED** | `docs/report/NOV_01_REPORT_DRAFT.md` (This PR) |

---

## 27. Conclusion & Future Work

The SwiftDrop team has successfully established the foundational architecture and demonstrated working vertical authentication slice required for the **November 1 milestone**. By strictly adhering to repository discipline, zero fabricated progress has been claimed.

### Post-November 1 Roadmap (Phase 5):
1. **Delivery Operations Service Implementation:** Implement parcel booking commands, assignment algorithms, and courier status update REST APIs.
2. **Pricing & Zones Service:** Build dynamic quote calculation engine and matrix caching.
3. **Event Streaming Backbone:** Deploy Kafka KRaft cluster and implement transactional outbox polling relays.
4. **Read-Only GraphQL Query API:** Stand up Apollo / Spring GraphQL composite query server backed by internal gRPC RPC channels.
5. **Bonus Kubernetes Deployment (Figure 19):** Package microservices into multi-stage Dockerfiles and Helm charts for cloud deployment.
6. **Bonus AI Dispatch Assistant:** Investigate lightweight LLM integration for route optimization.

---

## 28. References & Appendices

### 28.1 Target Compose Topology (Figure 20)
- **Diagram Reference:** [Docker Compose Target Topology (`docs/diagrams/rendered/20_compose_target.svg`)](file:///d:/PROJECTS/swiftdrop/docs/diagrams/rendered/20_compose_target.svg)  
- **Editable Source:** [`docs/diagrams/editable/20_compose_target.drawio`](file:///d:/PROJECTS/swiftdrop/docs/diagrams/editable/20_compose_target.drawio)  
- **Caption:** *Figure 20: Target Production Multi-Container Compose Environment.*

### 28.2 Architectural Decision Records (ADRs)
- `ADR-0001`: Adoption of Independent Business Microservices Topology
- `ADR-0002`: Protocol Segregation (REST, GraphQL, gRPC, Kafka, SSE)
- `ADR-0003`: Keycloak Authorization Code Flow with S256 PKCE
- `ADR-0004`: Dual-Layer Defense-in-Depth JWT Resource Server Verification
- `ADR-0005`: Per-Service PostgreSQL Database Ownership
- `ADR-0006`: Transactional Outbox Pattern for Asynchronous Kafka Events
- `ADR-0007`: Single Read-Only GraphQL Query API for Composite Dashboards
- `ADR-0008`: Server-Sent Events (SSE) for Unidirectional Parcel Tracking
- `ADR-0009`: Evaluation of Native Keycloak Email Verification and TOTP over SMS/Email OTP
