# Technical Handoff: Strict $0 Hosting Decision and Downstream Release Gate

**Originating Issue:** [Refs #172 (N10-ZC01)](https://github.com/Suthankan1/swiftdrop/issues/172) — Freeze strict $0 hosting decision and subscription-specific Azure allowlist
**Canonical ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)
**Machine-Readable Contract:** [`platform/security/azure-allowlist-policy.yaml`](../../platform/security/azure-allowlist-policy.yaml)
**Author / Lead:** Suthankan1 (@Suthankan1 — Platform / DevOps / Security Lead)
**Required Reviewers:** Harithkeshan (@Harithkeshan), Vibhushan (@Vibhushan-2003)
**Target Integration Branch:** `test`
**Milestone:** November 10, 2026 — Full SwiftDrop Release
**Status:** **AUTHOR RECOMMENDATIONS & ARCHITECTURAL CONSENSUS RECORDED — PENDING PEER SIGNOFF**

---

## 1. Executive Summary

This handoff record translates the binding architectural policy established in **ADR-0011** into concrete execution boundaries, dependency contracts, and validation checklists for all downstream platform, infrastructure, and deployment tasks.

### Core Guarantees:
1. **$0 Hosting Target Frozen:** All eight SwiftDrop application and identity workloads, five isolated PostgreSQL databases, Kafka KRaft, MinIO S3, and Mailpit SMTP are assigned to deploy locally via **k3d / K3s on existing Mac hardware (`arm64`)**.
2. **Initial Azure Allowlist is EMPTY (`[]`):** No Azure cloud resource is authorized for provisioning.
3. **Azure Students Promotional Credits Disqualified:** The $100 promotional credit balance is not accepted as non-billable hosting.
4. **Static Web Apps Free Disqualified:** SWA Free is blocked due to unverified subscription overage hard stops and architectural failure when accessing local API Gateway and Keycloak endpoints from public browsers.
5. **No Cloud Provisioning Permitted:** Zero `terraform apply`, zero cloud deployment, zero billable resource creation.

---

## 2. Downstream Task Handoff & Consumption Matrix

### 2.1 Direct Cost & Infrastructure Task Chain (Issues #173 – #178)

| Target Issue | Task Title | Primary Owner | Mandatory Invariant Consumed from ADR-0011 / Issue #172 |
|---|---|---|---|
| **[#173](https://github.com/Suthankan1/swiftdrop/issues/173)** | **Plan-only Terraform foundation and deny-by-default cost/security policy tests** | @Suthankan1 | **Input:** [`platform/security/azure-allowlist-policy.yaml`](../../platform/security/azure-allowlist-policy.yaml).<br>**Enforcement:** Terraform policy tests must assert that because `azure.allowed_resources` is empty and `deployment_authorized` is `false`, any plan generating `azurerm_*` resources MUST fail validation. No fake SKUs or bypass flags are permitted. |
| **[#174](https://github.com/Suthankan1/swiftdrop/issues/174)** | **Safe Terraform state and least-privilege GitHub Actions OIDC boundary** | @Suthankan1 | **Enforcement:** Prohibit creating billable Azure Storage accounts for remote Terraform state. Use local/ephemeral plan-only state backends or strictly non-billable state approaches. Enforce least-privilege OIDC claims with zero persistent secrets committed. |
| **[#175](https://github.com/Suthankan1/swiftdrop/issues/175)** | **Bootstrap measured local k3d/K3s cluster for existing Helm deployment tasks** | @Suthankan1 | **Execution:** Bootstrap local k3d cluster on the verified Darwin `arm64` host (10 cores, 16 GiB RAM, 93 GiB available disk). Measure and record actual memory/CPU consumption for all 8 application pods and 5 DBs. Complete the pending capacity verification noted in ADR-0011 §7.3. |
| **[#176](https://github.com/Suthankan1/swiftdrop/issues/176)** | **Prove $0 HTTPS/public-access feasibility with local hosting fallback** | @Suthankan1 | **Investigation:** Investigate whether zero-cost public tunneling (e.g. Cloudflare Zero Trust / Tunnel free tier) can safely bridge the SPA, Gateway, and Keycloak with trusted HTTPS and valid OIDC redirect URIs. Retain the verified local-only release topology if zero-cost public access cannot be proven. |
| **[#177](https://github.com/Suthankan1/swiftdrop/issues/177)** | **Publish $0 operator handover and evidence index for November 10 release** | @Suthankan1 | **Documentation:** Compile end-to-end operator reproduction commands, local startup scripts (`platform/local/`), readiness verification checks, and disaster recovery steps for peer handoff. |
| **[#178](https://github.com/Suthankan1/swiftdrop/issues/178)** | **Gate Azure cloud K3s behind separate spending authorization** | @Suthankan1 | **Scope Clarification:** Gated strictly as an **OPTIONAL FUTURE BONUS**. It is NOT a prerequisite for the November 10 release. Formally documented in [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](../deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md). Remains strictly **NOT AUTHORIZED** until separate academic/financial approval is granted. |

---

### 2.2 Alignment with Existing Release & Deployment Tasks

| Target Issue | Task Title | Primary Owner | Mandatory Invariant Consumed from ADR-0011 / Issue #172 |
|---|---|---|---|
| **[#128](https://github.com/Suthankan1/swiftdrop/issues/128)** | **Build secure versioned images for all apps and identity runtime** | @Suthankan1 | Images are built locally via Docker/Kaniko and imported directly into local k3d images (`k3d image import`) or GitHub Container Registry (ghcr.io) if non-billable. Prohibit provisioning Azure Container Registry (ACR). |
| **[#149](https://github.com/Suthankan1/swiftdrop/issues/149)** | **Create Helm foundations, ingress TLS and Kubernetes security boundaries** | @Suthankan1 | Helm charts and Ingress manifests target local k3d Traefik/Nginx ingress. TLS utilizes local mkcert or self-signed certificates. Network policies isolate internal gRPC and PostgreSQL ports within the cluster. |
| **[#154](https://github.com/Suthankan1/swiftdrop/issues/154)** | **Deploy persistent Kubernetes data and readiness-scaled application workloads** | @Suthankan1 | Persistent Volume Claims (PVCs) bind to k3s `local-path` storage class on the host NVMe SSD. Prohibit Azure Managed Disks or Azure Files. |
| **[#150](https://github.com/Suthankan1/swiftdrop/issues/150)** | **Configure production identity, SMTP, headers and secret rotation readiness** | @Suthankan1 | Keycloak SMTP configuration routes to local Mailpit (`mailpit:1025`) for demo isolation; external production SMTP relay is gated strictly behind verified free relay criteria (SendGrid 100/day tier / university relay). Zero secrets committed. |
| **[#160](https://github.com/Suthankan1/swiftdrop/issues/160)** | **Deploy protected monitoring dashboards and actionable release alerts** | @Suthankan1 | Monitoring deploys local lightweight Prometheus / Grafana or CLI health checks (`check-readiness.sh`). Prohibit Azure Monitor, Log Analytics workspaces, or billable alert action groups. |
| **[#167](https://github.com/Suthankan1/swiftdrop/issues/167)** | **Implement gated staging promotion, rollback and database recovery drill** | @Suthankan1 | Backup and recovery drills execute against local PostgreSQL volume snapshots and pg_dump archives on local storage. Prohibit cloud backup services. |
| **[#170](https://github.com/Suthankan1/swiftdrop/issues/170)** | **Execute reviewed test-to-main release and production promotion** | @Suthankan1 | Final release gate verifies strict adherence to the $0 local hosting decision prior to promoting `test` to `main`. |

---

## 3. Detailed Verification & Policy Invariants

Before any pull request in the downstream chain is merged, reviewers must verify:
- [ ] No `terraform apply` or `terraform destroy` commands are executed against live cloud environments.
- [ ] No Azure resources (`azurerm_*`) are present in active infrastructure definitions.
- [ ] The Azure allowlist remains empty (`allowed_resources: []`).
- [ ] Local k3d / K3s manifests maintain strict data ownership boundaries (5 distinct DB schemas/instances).
- [ ] No cloud secrets, access tokens, or sensitive subscription IDs are stored in git.
- [ ] External public access limitations are documented honestly rather than masked with unsupported cloud hosting claims.
