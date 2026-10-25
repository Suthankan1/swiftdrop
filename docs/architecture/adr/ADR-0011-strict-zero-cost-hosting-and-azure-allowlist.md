# ADR-0011: Strict $0 Hosting Decision and Subscription-Specific Azure Resource Allowlist

**Status:** PROPOSED — LOCAL-FIRST / AZURE ALLOWLIST EMPTY (Awaiting Independent Peer Signoff)
**Baseline:** SwiftDrop Architecture v1.3
**Date:** 2026-10-25
**Milestone:** November 10, 2026 — Full SwiftDrop Release
**Decision Owner:** Suthankan1 (@Suthankan1 — Platform / Cloud Architect / DevOps Lead)
**Required Independent Reviewers:** Harithkeshan (@Harithkeshan), Vibhushan (@Vibhushan-2003)
**Related Issues:** Refs [#172](https://github.com/Suthankan1/swiftdrop/issues/172), [#88](https://github.com/Suthankan1/swiftdrop/issues/88), [#173](https://github.com/Suthankan1/swiftdrop/issues/173), [#174](https://github.com/Suthankan1/swiftdrop/issues/174), [#175](https://github.com/Suthankan1/swiftdrop/issues/175), [#176](https://github.com/Suthankan1/swiftdrop/issues/176), [#177](https://github.com/Suthankan1/swiftdrop/issues/177), [#178](https://github.com/Suthankan1/swiftdrop/issues/178), [#128](https://github.com/Suthankan1/swiftdrop/issues/128), [#149](https://github.com/Suthankan1/swiftdrop/issues/149), [#154](https://github.com/Suthankan1/swiftdrop/issues/154), [#150](https://github.com/Suthankan1/swiftdrop/issues/150), [#160](https://github.com/Suthankan1/swiftdrop/issues/160), [#167](https://github.com/Suthankan1/swiftdrop/issues/167), [#170](https://github.com/Suthankan1/swiftdrop/issues/170)

---

## 1. Executive Summary & Context

SwiftDrop is a 9-member university group software project targeting a demonstrable, reliable production-grade release on **November 10, 2026**. Under Architecture Baseline v1.3, the platform comprises eight application/identity workloads (Single-Page React SPA, Spring Cloud API Gateway, Accounts & Partners, Pricing & Zones, Delivery Operations, Communications, GraphQL Query API, and Keycloak Identity Provider) backed by five isolated PostgreSQL databases, Kafka KRaft, and private S3 object storage.

A critical gap in existing deployment tasks ([#149](https://github.com/Suthankan1/swiftdrop/issues/149), [#154](https://github.com/Suthankan1/swiftdrop/issues/154)) was the lack of an authoritative, enforceable hosting decision and non-billable cloud policy. Teams frequently assume cloud services marketed as "Free" or offset by student promotional credits can be deployed safely without financial risk.

This Architecture Decision Record establishes a **strict $0 cost-safety gate** and freezes the **authoritative hosting topology** for the November 10 release:
1. **Hosting Target:** All eight SwiftDrop application and identity workloads, along with all stateful dependencies, are frozen to deploy on **existing local hardware using k3d / K3s**.
2. **Azure Allowlist:** The subscription-specific Azure resource allowlist is initialized to **EMPTY (`[]`)** and cloud deployment is **NOT AUTHORIZED (`deployment_authorized: false`)**.
3. **Azure Students Promotional Credit:** Promotional credits (including any $100 balance) are **strictly disqualified** as a $0 hosting justification.
4. **Azure Static Web Apps (SWA) Free Candidate:** SWA Free is evaluated strictly for the built static SPA (`frontend/dist/`), but is **BLOCKED / NOT APPROVED** due to unverified subscription-specific hard-stop guarantees and architectural browser reachability failures against local-only backend services.
5. **No Provisioning Authorized:** This ADR constitutes a binding documentation and cost-safety policy gate. It does not authorize `terraform apply`, cloud resource provisioning, cluster spin-up, or spending promotional credits.

---

## 2. Strict $0 Cost Policy & Principles

### 2.1 The Exact Definition of Strict $0
For the SwiftDrop November 10 release, **Strict $0** means:
> **Zero unauthorized billable cloud/platform resource consumption and zero consumption of Azure Students promotional credits.**

- **Real-World Resource Distinction:** The policy acknowledges that local electricity, pre-existing laptop/desktop hardware, home/university broadband Internet connectivity, and physical device maintenance incur baseline real-world costs. However, these are pre-existing sunk costs of the team members. Strict $0 strictly prohibits **new, metered, recurring, or variable cloud provider charges**.
- **Fail-Closed Default:** Any resource whose complete, end-to-end pricing, regional scope, dependencies, or quota overage behavior cannot be proven with 100% mathematical certainty to cost $0.00 USD must fail closed and be **REJECTED** or **BLOCKED**.

### 2.2 Non-Negotiable Invariants & Cost Rules
The following binding invariants govern all infrastructure, deployment, and automation tasks:
1. **Promotional Credit Disqualification:** Azure for Students promotional credit (e.g. $100 balance) is temporary funny money, not proof of non-billable hosting. Consuming credits burns the student entitlement and introduces catastrophic cliff-edge billing or service termination when depleted.
2. **Calculator Estimates are Insufficient:** A Microsoft Azure Pricing Calculator estimate showing "$0.00" is a marketing projection, not a binding contractual guarantee or evidence of subscription entitlement.
3. **"Free" Marketing Labels are Insufficient:** A public marketing webpage labeling a tier as "Free" does not make the service approved. Many "Free" tiers require billable underlying infrastructure, enable chargeable defaults, or convert silently to Pay-As-You-Go upon limit exhaustion.
4. **Trial & Promotional Offsets Prohibited:** 12-month free trials, temporary sign-up promotions, and promotional offsets do not constitute permanent $0 eligibility.
5. **Unknown Price Fails Closed:** Any SKU, meter, API call, or transaction whose exact price is unverified must be treated as billable.
6. **Unverified Dependencies Invalidate Approval:** An apparently free primary resource (e.g. Azure Functions consumption tier) is completely invalidated if any required dependency (e.g. Azure General-Purpose Storage Account, Application Insights) incurs storage or transaction costs.
7. **Quota vs. Hard-Stop Distinction:** A published quota (e.g. 100 GB/month bandwidth) is meaningless unless backed by an enforceable, proven **quota hard-stop** that drops traffic rather than silently billing overages.
8. **Budget Alerts are Not Cost Enforcers:** Azure Cost Management budget alerts and notification emails are reactive reporting mechanisms; they do NOT halt running virtual machines, drop incoming network traffic, or prevent overage charges.
9. **Absence of Invoices is Not Proof:** The absence of past billing charges or notifications is not evidence that a resource is non-billable.
10. **Metered Consumption Prohibited:** Any resource charging per GB of egress, per 10,000 I/O transactions, per vCPU-hour, or per IP-hour is strictly prohibited.
11. **Future Upgrades Require Fresh Approval:** Any modification of SKU, tier, configuration flags, or regional deployment automatically invalidates prior approvals and mandates a new ADR and independent peer review.

---

## 3. Actual Azure Subscription Evidence Assessment

### 3.1 Subscription Session & Verification Status
- **Subscription Offer / Type:** Azure for Students (Offer ID: `MS-AZR-0170P` / Academic Direct).
- **Subscription Identifier:** `SUBSCRIPTION-SPECIFIC ELIGIBILITY — UNVERIFIED` (Sanitized).
- **Authenticated Session State:** The local environment currently lacks an authenticated, active read-only Azure CLI (`az`) session or service principal credentials.
- **Fail-Closed Determination:** In strict adherence to repository security and governance discipline, **no subscription-specific claims are inferred from public marketing pages**. Because live read-only verification of subscription quota limits, spending-limit state, and SKU availability cannot be executed at this time, all candidate Azure services remain:
  ```
  SUBSCRIPTION-SPECIFIC ELIGIBILITY — UNVERIFIED
  AZURE ALLOWLIST — EMPTY ([])
  DEPLOYMENT AUTHORIZATION — FALSE
  ```

### 3.2 Azure for Students Official Plan Boundaries
Based on official Microsoft documentation retrieved 2026-10-25:
- **Reference:** Microsoft Learn — About Azure for Students ([learn.microsoft.com/en-us/azure/education-hub/about-azure-for-students](https://learn.microsoft.com/en-us/azure/education-hub/about-azure-for-students))
- **Reference:** Microsoft Learn — Azure for Students FAQ ([learn.microsoft.com/en-us/azure/education-hub/faq](https://learn.microsoft.com/en-us/azure/education-hub/faq))
- **Reference:** Microsoft Learn — Azure Spending Limit ([learn.microsoft.com/en-us/azure/cost-management-billing/manage/spending-limit](https://learn.microsoft.com/en-us/azure/cost-management-billing/manage/spending-limit))
- **Core Findings:**
  1. Azure for Students grants $100 in promotional credit valid for 12 months, plus limited access to select free services.
  2. The spending limit is enabled by default with a $0 overage cap. However, once the $100 credit is exhausted or the 12-month period elapses, the subscription is **immediately disabled / suspended**. All running virtual machines are abruptly deallocated, databases become unreachable, and DNS/IP routing ceases.
  3. If a student removes the spending limit to avoid service shutdown, the subscription transitions to standard Pay-As-You-Go rates, exposing the student to direct credit card liability.
  4. Relying on promotional credit to operate SwiftDrop during the November 10 demonstration represents an unacceptable operational and financial risk.

---

## 4. Resource-by-Resource Azure Decision Matrix

Every candidate Azure cloud resource evaluated for SwiftDrop is recorded below with official Microsoft citations, exact SKUs, meter risks, dependency traps, and formal policy classifications:

| Resource / Category | Intended SwiftDrop Scope | Exact SKU / Tier | Billable Meter & Pricing | Dependencies & Hidden Extras | Quota & Overage Behavior | Official Reference & UTC Timestamp | Verification & Approval | Policy Status | Rationale & Failure Condition |
|---|---|---|---|---|---|---|---|---|---|
| **Azure Virtual Machines** | Backend microservices, Keycloak, or DB hosting | `B1s`, `B2s`, `D2s_v5` | Compute: ~$0.0104 to $0.096/hr. Incurs hourly compute charges. | Requires OS Managed Disk, Public IP/NAT, and egress networking. | Credit-offset only in student offer; halts or bills when credit depleted. | [learn.microsoft.com/en-us/azure/virtual-machines/sizes](https://learn.microsoft.com/en-us/azure/virtual-machines/sizes) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE / CREDIT-OFFSET ONLY** | Direct compute charge; burns promotional credits; dependencies incur recurring disk/IP costs. |
| **Managed Disks & Snapshots** | Persistent volume storage for DBs/Kafka | `Standard_LRS`, `Premium_SSD` (P4/P6) | Storage: ~$1.54 to $5.28/month per disk, plus read/write operations ($0.0014/10k IOPS). | Persists and bills even when associated VM is stopped/deallocated. | No free tier outside initial trial; unallocated disks accumulate charges. | [azure.microsoft.com/en-us/pricing/details/managed-disks/](https://azure.microsoft.com/en-us/pricing/details/managed-disks/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Fixed recurring storage fee per month. Zero tolerance for unattached billable storage. |
| **Public IP Addresses** | External ingress routing for Gateway / SPA | Standard IPv4 Public IP | $0.005/IP/hour (~$3.60/month per allocated IP). | Required for any external VM, Load Balancer, or App Gateway. | Microsoft eliminated free Basic Public IPs (July 2023 / Sept 2025 retirement). All IPv4 is metered. | [azure.microsoft.com/en-us/pricing/details/ip-addresses/](https://azure.microsoft.com/en-us/pricing/details/ip-addresses/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Mandatory hourly charge per IPv4 address regardless of traffic. |
| **NAT Gateway** | Outbound internet egress for private subnet | Standard NAT Gateway | $0.045/hour (~$32.40/month) + $0.045 per GB data processed. | Requires at least one Public IP prefix or IP address. | No free tier exists. Purely commercial enterprise service. | [azure.microsoft.com/en-us/pricing/details/azure-nat-gateway/](https://azure.microsoft.com/en-us/pricing/details/azure-nat-gateway/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Prohibitive hourly cost (~$32/mo) before any packet is transmitted. |
| **Azure Load Balancer** | Layer 4 routing to cluster nodes | Standard Load Balancer | $0.025/hour for first 5 rules (~$18/month) + $0.005/GB data processed. | Requires Standard Public IP and backend pool VMs. | Basic Load Balancer retired; Standard LB is billable from provisioning. | [azure.microsoft.com/en-us/pricing/details/load-balancer/](https://azure.microsoft.com/en-us/pricing/details/load-balancer/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Recurring rule charges + data processing meters violate strict $0. |
| **Azure Kubernetes Service (AKS) Nodes** | Managed K8s orchestration | Managed AKS Free cluster control plane (`Free` tier) | Cluster control plane is $0.00/hr, BUT worker nodes (VMs) are billed at full VM rates. | Requires VM agent pool (min 2-3 nodes), managed disks, Load Balancer, Public IPs. | Free control plane SLA does not cover worker compute or storage. | [azure.microsoft.com/en-us/pricing/details/kubernetes-service/](https://azure.microsoft.com/en-us/pricing/details/kubernetes-service/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Classic cloud marketing trap: control plane is $0, but running worker nodes costs >$70/month. |
| **Azure Storage Accounts** | Blob storage for delivery proofs / artifacts | Standard General-Purpose v2 (Hot/Cool) | Capacity ($0.0184/GB/mo), Write ops ($0.05/10k), Read ops ($0.004/10k), Data egress. | Enabled by default with georedundancy (RA-GRS) unless overridden to LRS. | 5 GB free for 12 months under student offer, but operations and egress can meter charges. | [azure.microsoft.com/en-us/pricing/details/storage/blobs/](https://azure.microsoft.com/en-us/pricing/details/storage/blobs/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE / DEPENDENCY COST UNKNOWN** | Transaction meters, data movement, and post-trial capacity meters fail strict $0 gate. MinIO local S3 replaces this. |
| **Azure Storage for Terraform State** | Remote backend state storage (`azurerm`) | Standard General-Purpose v2 | Blob lease locks, container storage, state read/write API operations. | Storage account creation, resource group, container, network ACLs. | Billable operations and storage fees outside verified zero-cost boundary. | [learn.microsoft.com/en-us/azure/developer/terraform/store-state-in-azure-storage](https://learn.microsoft.com/en-us/azure/developer/terraform/store-state-in-azure-storage) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE / CREDIT-OFFSET ONLY** | Issue #174 will define local / plan-only state boundaries without cloud storage dependencies. |
| **Azure Container Registry (ACR)** | Private Docker image registry | Basic SKU | ~$0.167/day (~$5.00/month) base fee, plus storage over 10 GB ($0.10/GB/day). | Daily flat rate billed immediately upon provisioning. | No permanent free tier exists. Consumes credit balance directly. | [azure.microsoft.com/en-us/pricing/details/container-registry/](https://azure.microsoft.com/en-us/pricing/details/container-registry/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Fixed daily charging model makes ACR completely ineligible. Local Docker/k3d image caching used instead. |
| **Azure Database for PostgreSQL** | Managed logical databases for services | Flexible Server (`Burstable B1ms`) | Compute: ~$0.034/hr (~$25/mo), Storage: ~$0.115/GB/mo, Backup storage: metered. | Managed VNet integration, compute hours, storage allocation. | 12-month free trial offers 750 hrs B1ms + 32 GB, but overage/expiry bills automatically. | [azure.microsoft.com/en-us/pricing/details/postgresql/flexible-server/](https://azure.microsoft.com/en-us/pricing/details/postgresql/flexible-server/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | High compute base cost. Violates per-service DB ownership unless 5 instances deployed (~$125/mo). Local PG used. |
| **Azure Functions** | Serverless backend execution | Consumption Plan (`Y1`) | 1 million executions + 400,000 GB-s free/month. | **MANDATORY DEPENDENCY:** Requires general-purpose Azure Storage Account for function code, state, and keys. | Storage dependency is billable; Application Insights default enabled. | [azure.microsoft.com/en-us/pricing/details/functions/](https://azure.microsoft.com/en-us/pricing/details/functions/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — NOT IN PERMITTED SCOPE / DEPENDENCY RISK** | SWA static-only scope explicitly excludes backend functions; mandatory storage account creates billable meter. |
| **Application Insights** | Telemetry and tracing | Workspace-based | Ingestion: $2.30/GB above 5 GB/month; retention: $0.10/GB/month beyond 90 days. | Log Analytics workspace, diagnostic settings, high-frequency logging. | Data ingestion easily exceeds free tier under noisy trace logging. | [azure.microsoft.com/en-us/pricing/details/monitor/](https://azure.microsoft.com/en-us/pricing/details/monitor/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Variable telemetry ingestion creates uncontrolled financial risk. Local logging/metrics preferred. |
| **Log Analytics / Azure Monitor** | Centralized audit and container logs | Per GB Ingestion | Ingestion: $2.30/GB; Archive: $0.02/GB/month. | Connected VMs, Kubernetes container insights, diagnostic pipelines. | High ingestion rates from Kafka or Keycloak logs incur rapid charges. | [azure.microsoft.com/en-us/pricing/details/monitor/](https://azure.microsoft.com/en-us/pricing/details/monitor/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Uncapped log volume ingestion violates strict $0 cost gate. |
| **Paid Alerts & Notification Channels** | Infrastructure alerting | Action Groups (SMS / Voice / Webhook) | SMS: ~$0.08/msg; Voice: ~$0.10/call; Webhooks: free up to 100k, then metered. | Azure Monitor alert rules ($0.10/rule/month). | Alert rules and telephony actions incur direct per-unit fees. | [azure.microsoft.com/en-us/pricing/details/monitor/](https://azure.microsoft.com/en-us/pricing/details/monitor/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Alert rule fees and SMS charges violate strict $0. Local Slack/terminal alerts used. |
| **Azure Key Vault** | Secrets and certificate storage | Standard Tier | Key ops ($0.03/10k), Secret ops ($0.03/10k), Automated certificate renewals ($3.00/cert). | Private endpoint or public IP firewall rules. | No free tier exists; all API transactions are metered. | [azure.microsoft.com/en-us/pricing/details/key-vault/](https://azure.microsoft.com/en-us/pricing/details/key-vault/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Transaction-based billing for secret reads violates $0 gate. Local Kubernetes secrets / `.env` used. |
| **Azure Front Door / Paid CDN** | Edge acceleration & WAF | Standard / Premium | Base fee: $35.00/month (Standard) + $0.01/GB data egress + rule engine fees. | Custom domain TLS, backend routing pools. | High fixed monthly minimum; enterprise commercial offering only. | [azure.microsoft.com/en-us/pricing/details/frontdoor/](https://azure.microsoft.com/en-us/pricing/details/frontdoor/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | $35/month minimum base charge is strictly forbidden. |
| **Purchased DNS Domains & Zones** | Public domain name routing | Azure DNS | Hosted zone: $0.50/zone/month; Queries: $0.40 per million queries. | Domain registration fees ($12-$30/yr). | Fixed monthly cost per hosted zone + per-query transaction meters. | [azure.microsoft.com/en-us/pricing/details/dns/](https://azure.microsoft.com/en-us/pricing/details/dns/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | No budget exists for purchased domains or hosted zones. Default local hostnames (`localhost`) used. |
| **Paid External Certificates** | TLS encryption | App Service Certificate | Standard: $69.99/year; Wildcard: $299.99/year. | Key Vault storage, App Service binding. | Upfront annual commercial certificate purchase. | [azure.microsoft.com/en-us/pricing/details/app-service/](https://azure.microsoft.com/en-us/pricing/details/app-service/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Commercial SSL purchases strictly forbidden. Let's Encrypt or local CA evaluated in #176. |
| **Metered Egress Networking** | Cross-region or Internet outbound | Internet Egress | First 100 GB/month free globally; then $0.087/GB. | Outbound traffic from any VM, DB, or storage service. | Overage automatically bills per GB transferred. | [azure.microsoft.com/en-us/pricing/details/bandwidth/](https://azure.microsoft.com/en-us/pricing/details/bandwidth/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **REJECTED — BILLABLE** | Metered data transfer outside local loopback is rejected. |
| **Azure Static Web Apps (SWA)** | Static compiled SPA (`frontend/dist/`) | Free Plan | Storage: 0.5 GB/app included ($0.00); Bandwidth: 100 GB/sub/mo ($0.00). | Excludes Functions/DB; requires public Gateway & Keycloak for live app. | Throttles/fails when bandwidth exceeded; upgrade to Standard is $9/app/mo. | [learn.microsoft.com/en-us/azure/static-web-apps/plans](https://learn.microsoft.com/en-us/azure/static-web-apps/plans) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **BLOCKED — SUBSCRIPTION EVIDENCE MISSING & PUBLIC ACCESS UNVERIFIED** | Sole potential candidate. Excluded from allowlist because subscription session is unverified and local backend cannot be reached by public browser. |
| **Cloud K3s Virtual Machines** | Single-VM lightweight cloud K3s | `Standard_B2s` (Linux) | Compute: ~$0.0416/hr (~$30/mo) + Managed Disk + Public IP. | Custom K3s install script, cloud-init, security groups. | Consumes promotional credit; bills card if spending limit removed. | [azure.microsoft.com/en-us/pricing/details/virtual-machines/linux/](https://azure.microsoft.com/en-us/pricing/details/virtual-machines/linux/) (2026-10-25T10:19:00Z) | Suthankan1 / PENDING | **DEFERRED — FUTURE AUTHORIZATION REQUIRED** | Gated strictly by Issue #178 as an optional future bonus. NOT authorized for November 10 release. |

---

## 5. Detailed Evaluation of Azure Static Web Apps Free Candidate

Azure Static Web Apps (SWA) Free was investigated as the sole named cloud candidate in Issue #172 for hosting the compiled Single-Page Application (`frontend/dist/`).

### 5.1 Permitted Scope & Invariants
- **Permitted Workload:** Compiled React 19 + TypeScript + Vite production bundle (`frontend/dist/`) containing purely static HTML, CSS, JavaScript, and asset files.
- **Strict Exclusions:**
  - Zero Azure Functions (managed or BYO).
  - Zero Azure SQL, Cosmos DB, or Database connections.
  - Zero Application Insights integration.
  - Zero enterprise edge / Azure Front Door integration.
  - Zero Azure Private Endpoints (requires Standard SKU).
  - Zero automatic or manual upgrade to Standard Plan ($9/app/month).
  - Zero backend microservice or Keycloak placement on SWA.

### 5.2 Plan Specifications & Technical Limits
- **Storage:** 0.5 GB per app (SwiftDrop `frontend/dist/` is ~1.5 MB; well within quota).
- **Max App Size:** 100 MB max deployment size.
- **Monthly Bandwidth:** 100 GB per subscription per month.
- **Custom Domains:** 2 custom domains per app with free managed TLS certificates.
- **Included Hostname:** Default generated hostname (`https://<random-name>.azurestaticapps.net`) with free Microsoft-managed SSL.
- **Staging / Preview Environments:** Up to 3 preview environments.

### 5.3 Failure Conditions & Blocker Analysis
SWA Free fails the strict $0 approval gate for two critical reasons:

1. **Unverified Subscription Overage Hard-Stop:**
   While Microsoft documentation states that bandwidth overages on Free plans result in traffic throttling or HTTP 403 / service unavailability, the behavior of an Azure for Students subscription when associated with optional defaults (such as default diagnostic logging or telemetry) without an active, verified spending-limit configuration cannot be audited without an authenticated session. Fail-closed governance dictates that unverified overage behavior blocks approval.
2. **The Architectural Browser Reachability Gap:**
   Hosting the static SPA on public SWA while backend microservices and Keycloak reside on a developer's local machine is **architecturally unworkable** for a shared release:
   - **Localhost Endpoint Trap:** The SPA is configured with `VITE_API_GATEWAY_URL=http://localhost:8080` and `VITE_KEYCLOAK_URL=http://localhost:8090`. When an external peer or examiner loads `https://<app>.azurestaticapps.net`, their browser attempts to connect to `http://localhost:8080` and `http://localhost:8090` **on their own personal computer**, where no SwiftDrop services are running.
   - **Mixed Content Security Rejection:** Modern browsers enforce strict Mixed Content policies. When a page is loaded over HTTPS (`https://...azurestaticapps.net`), the browser automatically blocks unencrypted HTTP requests (`http://localhost:8080/api/...`) as insecure active mixed content.
   - **Keycloak OIDC Redirect & CORS:** Keycloak's client configuration requires exact match of authorized redirect URIs and Web Origins. A publicly hosted SPA domain calling a local Keycloak instance causes instant OIDC redirect mismatch (`invalid_redirect_uri`) and CORS preflight rejection.

### 5.4 Candidate Determination
```
CANDIDATE: Azure Static Web Apps Free
EVALUATION: Static SPA Only (frontend/dist/)
STATUS: BLOCKED — SUBSCRIPTION EVIDENCE MISSING & PUBLIC ACCESS UNVERIFIED
ALLOWLIST PLACEMENT: EXCLUDED (Allowlist remains EMPTY)
```
The static frontend will be hosted locally alongside the API Gateway and Keycloak unless and until Issue [#176](https://github.com/Suthankan1/swiftdrop/issues/176) establishes a fully verified, non-billable public HTTPS routing topology.

---

## 6. Frozen Local Release Topology (November 10 Target)

Because no Azure cloud resource fulfills all strict $0 evidence and reachability requirements, the **November 10 SwiftDrop Release Topology is frozen to run entirely on existing local hardware using k3d / K3s**.

### 6.1 Workload Placement Matrix (All 8 Workloads)

| # | Workload Name | Component Type | Runtime Technology | Target Local Host / Orchestrator | Port / Binding | Internal Communication |
|---|---|---|---|---|---|---|
| 1 | **React Frontend SPA** | User Interface | React 19, TypeScript, Vite static bundle | Local static server / k3d Ingress / Vite preview | `:5173` (or `:80` via local ingress) | Browser calls Gateway `:8080` & Keycloak `:8090` |
| 2 | **API Gateway** | Edge Routing & Security | Spring Cloud Gateway (Java 21) | Local k3d/K3s Pod (`swiftdrop-gateway`) | `:8080` (NodePort / HostPort) | Routes external REST & `/graphql` to backend |
| 3 | **Accounts & Partners** | Business Service | Spring Boot 4.1.1 (Java 21) | Local k3d/K3s Pod (`accounts-service`) | `:8081` (ClusterIP / internal) | Serves REST profile/merchant APIs & gRPC `:9090` |
| 4 | **Pricing & Zones** | Business Service | Spring Boot 4.1.1 (Java 21) | Local k3d/K3s Pod (`pricing-service`) | `:8082` (ClusterIP / internal) | Serves REST quotes & internal gRPC quote validation |
| 5 | **Delivery Operations** | Business Service | Spring Boot 4.1.1 (Java 21) | Local k3d/K3s Pod (`delivery-service`) | `:8083` (ClusterIP / internal) | Manages parcel lifecycle, outbox, SSE tracking |
| 6 | **Communications** | Business Service | Spring Boot 4.1.1 (Java 21) | Local k3d/K3s Pod (`communications-service`) | `:8084` (ClusterIP / internal) | Consumes Kafka events, executes webhook jobs |
| 7 | **GraphQL Query API** | Composite Edge Read | Spring Boot GraphQL (Java 21) | Local k3d/K3s Pod (`graphql-api`) | `:8085` (ClusterIP / internal) | Read-only aggregator calling backend via gRPC |
| 8 | **Keycloak Identity Provider** | IAM & OIDC Broker | Keycloak 26.8.1 (Quarkus) | Local k3d/K3s Pod (`swiftdrop-keycloak`) | `:8090` (NodePort / HostPort) | Issues JWTs; federates Google login; TOTP MFA |

### 6.2 Stateful & Infrastructure Dependencies

| Dependency | Purpose | Target Deployment | Data Isolation & Boundary |
|---|---|---|---|
| **Accounts PostgreSQL** | User profiles, merchant API keys, audit logs | Local k3d/K3s StatefulSet / Container | Logical DB `accounts_db`. Sole owner: Accounts & Partners. |
| **Pricing PostgreSQL** | Zones, versioned rate cards, snapshotted quotes | Local k3d/K3s StatefulSet / Container | Logical DB `pricing_db`. Sole owner: Pricing & Zones. |
| **Delivery PostgreSQL** | Parcels, assignments, history, transactional outbox | Local k3d/K3s StatefulSet / Container | Logical DB `delivery_db`. Sole owner: Delivery Operations. |
| **Communications PostgreSQL** | Durable inbox, notification logs, webhook jobs | Local k3d/K3s StatefulSet / Container | Logical DB `communications_db`. Sole owner: Communications. |
| **Keycloak PostgreSQL** | Realm metadata, users, credentials, role grants | Local k3d/K3s StatefulSet / Container | Logical DB `keycloak_db`. Sole owner: Keycloak IdP. |
| **Apache Kafka (KRaft)** | Async domain event publication (`parcel.lifecycle.v1`) | Local k3d/K3s StatefulSet (`swiftdrop-kafka`) | Persistent local volume; strict KRaft mode (no Zookeeper). |
| **MinIO Object Storage** | Private encrypted delivery proof-of-delivery storage | Local k3d/K3s StatefulSet (`swiftdrop-minio`) | S3-compatible private buckets; zero public object URLs. |
| **Mailpit SMTP Server** | Local non-relaying SMTP capture & FreeMarker email UI | Local k3d/K3s Pod (`swiftdrop-mailpit`) | `:1025` SMTP capture, `:8025` Web UI. Zero external relay. |

**Architectural Boundary Invariant:** Under no circumstances shall services share a database schema or perform cross-service SQL queries. Internal gRPC (`:9090`), PostgreSQL (`:5432`), Kafka (`:9092`), and MinIO (`:9000`) ports remain **strictly internal to the k3d cluster network**.

---

## 7. Local Host Capacity Ownership & Hardware Baseline

### 7.1 Capacity Owner
- **Primary Host Operator:** Suthankan1 (@Suthankan1 — Platform Lead)
- **Operational Responsibility:** Host machine resource provisioning, k3d cluster lifecycle, container runtime stability, and local backup custody.

### 7.2 Measured Host Hardware Baseline
The primary local demonstration environment has been inspected and measured:
- **Host Operating System:** macOS (Darwin Kernel Version 27.0.0; `arm64`)
- **Host Hardware Model:** Apple Silicon (MacBook Pro, 10 CPU cores)
- **Installed Physical RAM:** 16 GiB (17,179,869,184 bytes)
- **Storage Subsystem:** Apple APFS on NVMe SSD
- **Primary Disk Capacity:** 460 GiB total; **93 GiB available / unallocated** on `/System/Volumes/Data` (79% capacity utilized).
- **Installed Container Runtime:** Docker Engine version 29.9.2 (build a82e528)
- **Installed Kubernetes Tooling:** `kubectl` Client version v1.36.1 (Kustomize v5.8.1)
- **k3d Binary:** Command not yet present; scheduled for bootstrap under Issue [#175](https://github.com/Suthankan1/swiftdrop/issues/175).

### 7.3 Memory Footprint & Resource Budget
The full SwiftDrop stack memory budget is estimated and allocated as follows:

| Workload Group | Components | Max Heap / Memory Limit | Estimated Working Set |
|---|---|---|---|
| **JVM Business Services** | Accounts, Pricing, Delivery, Communications (4 apps) | 512 MiB heap each (`-Xmx512m`) | ~2,048 MiB |
| **Edge Gateways** | API Gateway + GraphQL Query API (2 apps) | 512 MiB heap each (`-Xmx512m`) | ~1,024 MiB |
| **Keycloak IdP** | Quarkus JVM Runtime | 1,024 MiB heap (`-Xmx1024m`) | ~1,024 MiB |
| **Relational Databases** | 5 PostgreSQL instances (or 1 multi-tenant daemon) | Shared buffer pool 512 MiB | ~768 MiB |
| **Message Broker** | Kafka KRaft broker | 512 MiB heap (`-Xmx512m`) | ~640 MiB |
| **Object Storage & Mail** | MinIO S3 + Mailpit SMTP | Lightweight Go runtimes | ~256 MiB |
| **Frontend UI** | Static web server / Vite preview | Node / Nginx lightweight process | ~128 MiB |
| **k3s / k3d Control Plane** | Kube-apiserver, etcd/SQLite, containerd | Integrated agent | ~768 MiB |
| **Total Estimated Footprint** | Complete SwiftDrop Enterprise Stack | **~6,656 MiB (~6.5 GiB)** | Peak ~7.5 GiB |

**Capacity Verdict:** The full stack requires ~6.5 to 7.5 GiB of active memory, fitting comfortably within the host's **16 GiB physical RAM**, leaving 8.5 to 9.5 GiB for the macOS kernel, developer tools, and browser sessions.

**Measurements Pending:** Actual runtime memory consumption under synthetic load, startup latency, container CPU throttling, and restart recovery times are **PENDING MEASUREMENT** by Issue [#175](https://github.com/Suthankan1/swiftdrop/issues/175).

---

## 8. Local Demo vs. Internet-Hosted Release Boundary

SwiftDrop explicitly formalizes the boundary between a local demonstration and an Internet-hosted production system:

1. **LOCAL DEMO RELEASE (Current Frozen Decision):**
   - The platform executes entirely on approved, pre-existing local hardware.
   - User interaction occurs on the host machine or across an isolated local area network (LAN) / WiFi subnet.
   - External Internet users cannot reach the system without manual operator intervention.
   - This topology is **fully funded, 100% reproducible, completely immune to cloud billing, and officially approved for the November 10 release**.
2. **INTERNET-HOSTED RELEASE (Gated Future Scope):**
   - Workloads are reachable from arbitrary public Internet locations via public DNS hostnames, trusted public CA certificates (Let's Encrypt), and public IP routing.
   - This requires solving the reachability, certificate management, and edge routing challenges without billable cloud infrastructure.
   - **Current Status:**
     ```
     PUBLIC INTERNET ACCESS — NOT VERIFIED / LOCAL-ONLY RELEASE
     ```
   - Responsibility for investigating non-billable public access mechanisms (e.g. Cloudflare Tunnels, ngrok free tiers, tailscale) belongs strictly to Issue [#176](https://github.com/Suthankan1/swiftdrop/issues/176).
   - Under no circumstances shall any team member purchase domain names, spin up cloud VPN gateways, or allocate billable Azure public IPs to simulate public hosting.

---

## 9. Downstream Policy Contract for Issue #173 (Terraform Deny-by-Default)

Issue [#173](https://github.com/Suthankan1/swiftdrop/issues/173) is tasked with implementing plan-only Terraform foundations and automated cost-policy tests. This ADR establishes the unambiguous, machine-readable policy contract that Issue #173 must enforce:

### 9.1 Binding Allowlist State
```yaml
# SwiftDrop Machine-Readable Policy Contract (Refs ADR-0011 / Issue #172)
version: "1.0"
policy_mode: "deny-by-default"
milestone: "2026-11-10"
effective_date: "2026-10-25"
cost_ceiling_usd: 0.00

azure:
  deployment_authorized: false
  allowed_resources: []
  allowed_skus: {}
  allowed_regions: []
  promotional_credit_permitted: false
  billing_overage_permitted: false

local:
  target: "k3d-k3s"
  authorized: true
  primary_host_operator: "Suthankan1"
```

### 9.2 Enforcement Invariant for Issue #173
Because `azure.deployment_authorized` is `false` and `azure.allowed_resources` is `[]`:
- **Every Terraform plan containing ANY Azure resource (`azurerm_*`) MUST BE REJECTED** by policy checks.
- Any attempt to add resources to the allowlist without a superseding, independently approved ADR and live subscription evidence must trigger automated test failure.
- Issue #173 shall test plan-only behavior and verify that invalid, billable, or unapproved resources fail closed.

---

## 10. Downstream Handoff Index & Milestone Alignment

This decision record provides the foundational cost and hosting gate for all subsequent platform and release tasks:

| Issue | Title | Handoff Boundary & Consumption of ADR-0011 |
|---|---|---|
| **[#173](https://github.com/Suthankan1/swiftdrop/issues/173)** | **Plan-Only Terraform & Policy Tests** | Consumes empty allowlist (`[]`) and `deployment_authorized: false`. Implements deny-by-default unit/policy tests. |
| **[#174](https://github.com/Suthankan1/swiftdrop/issues/174)** | **Terraform State & OIDC Boundary** | Enforces zero-cloud-storage state boundary (local/plan-only backend) and least-privilege GitHub Actions OIDC restrictions. |
| **[#175](https://github.com/Suthankan1/swiftdrop/issues/175)** | **Measured Local k3d/K3s Cluster** | Consumes frozen local topology. Bootstraps k3d on Darwin arm64, measures actual memory/CPU limits, validates container startup. |
| **[#176](https://github.com/Suthankan1/swiftdrop/issues/176)** | **$0 HTTPS / Public Access Feasibility** | Investigates non-billable public reachability options (Cloudflare Zero Trust / loopback). Retains local-only fallback if unproven. |
| **[#177](https://github.com/Suthankan1/swiftdrop/issues/177)** | **$0 Operator Handover & Evidence** | Aggregates all $0 evidence, runbooks, and operator commands for peer reproduction prior to November 10. |
| **[#178](https://github.com/Suthankan1/swiftdrop/issues/178)** | **Optional Future Azure K3s Gate** | **NON-PREREQUISITE.** Gated behind separate academic/spending authorization. Remains **STRICTLY NOT AUTHORIZED** for Nov 10. |
| **[#128](https://github.com/Suthankan1/swiftdrop/issues/128)** | **Secure Versioned Images** | Packages applications into OCI images for local k3d container import rather than billable ACR publication. |
| **[#149](https://github.com/Suthankan1/swiftdrop/issues/149)** | **Helm & K8s Security Boundaries** | Packages Helm charts configured specifically for local k3d ingress, NodePort routing, and local network policies. |
| **[#154](https://github.com/Suthankan1/swiftdrop/issues/154)** | **Persistent K8s Workloads** | Maps persistent claims to k3s `local-path` provisioner on host SSD rather than billable Azure Managed Disks. |
| **[#150](https://github.com/Suthankan1/swiftdrop/issues/150)** | **Production Identity & SMTP Readiness** | Enforces Mailpit container routing for local demo; gates external production SMTP behind strict $0 free relay criteria. |
| **[#160](https://github.com/Suthankan1/swiftdrop/issues/160)** | **Protected Monitoring & Alerts** | Deploys lightweight local Prometheus/Grafana or terminal health checks without billable Log Analytics / Monitor. |
| **[#167](https://github.com/Suthankan1/swiftdrop/issues/167)** | **Promotion, Rollback & Recovery Drill** | Executes database backup, cold restore, and rollback drills against local PostgreSQL volumes. |
| **[#170](https://github.com/Suthankan1/swiftdrop/issues/170)** | **Final Reviewed Release Promotion** | Verifies that final `test -> main` release complies with the frozen local topology and strict $0 mandate. |

---

## 11. Alternatives Evaluated & Rejected

| Alternative Topology | Proposed Architecture | Financial Cost | Operational / Technical Risk | Verdict |
|---|---|---|---|---|
| **Option A: Full Azure Cloud Hosting (AKS / VMs)** | AKS cluster + Flexible PostgreSQL + Azure Storage + Load Balancers | **>$150.00 / month** | Exhausts $100 student credit within 20 days. Results in service suspension or massive personal credit card liability. | **REJECTED — STRICTLY FORBIDDEN** |
| **Option B: Hybrid Cloud (SWA SPA + Local Backend)** | Azure Static Web Apps Free for SPA; API Gateway & Keycloak on local machine | **$0.00 direct base** | Browser Mixed Content errors; localhost resolution failure for external examiners; CORS/OIDC redirect failure. | **REJECTED — ARCHITECTURALLY BROKEN** |
| **Option C: Single Cloud K3s VM** | One `Standard_B2s` VM running K3s + all services in Azure | **~$35.00 / month** | Consumes student credit; memory pressure (4GB RAM on B2s vs 7.5GB needed); requires spending authorization [#178]. | **DEFERRED — NOT AUTHORIZED FOR NOV 10** |
| **Option D: Local k3d/K3s on Existing Hardware (Selected)** | All 8 workloads + 5 PostgreSQL DBs + Kafka + MinIO on Mac hardware | **$0.00 cloud cost** | Requires local operator custody and machine uptime; internet access limited to local network or tunnel. | **APPROVED — DEFAULT NOVEMBER 10 TARGET** |

---

## 12. Security, Consequences, & Verification Protocol

### 12.1 Positive Consequences
- **Absolute Financial Immunity:** Zero possibility of surprise credit card charges, exhausted student promotional credits, or billing disputes.
- **Complete Environment Determinism:** No dependencies on Azure regional outages, cloud quota throttles, or network latency.
- **High Resource Headroom:** Local 10-core / 16 GiB hardware provides superior CPU and memory headroom compared to low-tier cloud burstable VMs.
- **Fail-Closed Governance:** Establishes a gold-standard FinOps discipline for the engineering team.

### 12.2 Trade-Offs & Known Risks
- **Single-Machine Failure Risk:** The entire demonstration relies on Suthankan1's MacBook Pro. If the host suffers hardware damage or power loss, the platform goes down. Mitigation: Comprehensive cold backup scripts ([#167](https://github.com/Suthankan1/swiftdrop/issues/167)) and clean-clone runbooks allowing any team member to spin up the stack on their machine.
- **Public Internet Reachability Limitation:** External users cannot simply open a public URL without an approved tunnel solution. Mitigation: Documented as an honest limitation; investigated in [#176](https://github.com/Suthankan1/swiftdrop/issues/176).

### 12.3 Independent Review Requirements
Prior to merging this decision into `test`, independent signoff is required from:
- **@Harithkeshan** (Platform & Staff/Admin Lead): Review local topology capacity assumptions, downstream task dependencies, and operational feasibility.
- **@Vibhushan-2003** (Independent Security & Quality Lead): Review cost-proof methodology, fail-closed allowlist invariants, and identity reachability security.
