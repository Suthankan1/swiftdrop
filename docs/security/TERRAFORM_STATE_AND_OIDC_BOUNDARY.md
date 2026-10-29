# SwiftDrop Safe Terraform State Custody & Least-Privilege GitHub Actions OIDC Boundary

**Document Status:** PROPOSED — BOUNDARY DEFINED / CLOUD IDENTITY & REMOTE STATE DEFERRED  
**Canonical Architecture Reference:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Machine-Readable Allowlist Policy:** [`platform/security/azure-allowlist-policy.yaml`](../../platform/security/azure-allowlist-policy.yaml)  
**Machine-Readable OIDC Trust Contract:** [`platform/security/github-oidc-trust-policy.yaml`](../../platform/security/github-oidc-trust-policy.yaml)  
**Originating Delivery Task:** [GitHub Issue #174 (N10-ZC03)](https://github.com/Suthankan1/swiftdrop/issues/174) — Define safe Terraform state and least-privilege GitHub Actions OIDC boundary  
**Primary Custodian & Platform Lead:** Suthankan1 (@Suthankan1)  
**Required Independent Reviewers:** Harithkeshan (@Harithkeshan — Platform Architecture), Vibhushan (@Vibhushan-2003 — Security & Quality)  
**Milestone:** November 10, 2026 — Full SwiftDrop Release  
**Downstream Coordination:** [Issue #167 (N10-P09)](https://github.com/Suthankan1/swiftdrop/issues/167) (Release Promotion & Disaster Recovery), [Issue #175 (N10-ZC04)](https://github.com/Suthankan1/swiftdrop/issues/175) (Local Cluster), [Issue #177 (N10-ZC06)](https://github.com/Suthankan1/swiftdrop/issues/177) (Operator Handover)  

---

## 1. Executive Summary & Authoritative Invariants

SwiftDrop is an enterprise application composed of eight microservice and identity workloads backed by five isolated PostgreSQL instances, Kafka KRaft, and MinIO object storage. Per ADR-0011, the authoritative hosting decision for the November 10 release is **frozen to local-first hosting on existing hardware using k3d / K3s**.

This policy defines the security boundaries for **Terraform state custody** and **GitHub Actions OpenID Connect (OIDC) workload identity trust**.

### Core Invariants:
1. **Remote Terraform State is DEFERRED:** Zero cloud backend (`azurerm`, AWS S3, Terraform Cloud) is configured or authorized. CI operates with `-backend=false` and requires zero cloud credentials.
2. **Local State Custody is Named and Restricted:** For reviewed local operator experiments only, local state is stored outside the Git repository, encrypted at rest via host full-disk encryption, managed by a strict single-writer procedure, and safeguarded by encrypted offline backups.
3. **Zero Secrets in Version Control:** Terraform state (`*.tfstate`), execution plans (`*.tfplan`), sensitive variable files (`*.tfvars`), and credentials must NEVER be committed to Git, uploaded to GitHub Actions artifacts, or posted in GitHub issues/PRs.
4. **Cloud OIDC Workload Identity is DISABLED:** GitHub Actions OIDC federation to Microsoft Entra ID is defined as a non-executable, reviewable contract (`platform/security/github-oidc-trust-policy.yaml`) with `cloud_identity.enabled: false`. No live Azure identity, service principal, federated credential, or role assignment exists.
5. **No Cloud Tokens in Active PR CI:** Active pull request and CI workflows are strictly prohibited from holding `id-token: write` or executing `az login`. Untrusted code and PRs operate with read-only permissions (`contents: read`).
6. **Zero Cloud Infrastructure Provisioned:** No Azure Storage accounts, Key Vaults, virtual machines, or networking resources are created or authorized.

---

## 2. Authoritative Terraform State Custody Policy

### 2.1 State Sensitivity & Threat Model
Terraform state (`terraform.tfstate`) and binary execution plans (`tfplan`) contain the complete resource mapping of managed infrastructure. Crucially:
- **Sensitive Attribute Exposure:** Even when resource variables are marked `sensitive = true`, Terraform stores their raw, unencrypted values in plaintext within the state JSON.
- **Plan File Risks:** Binary plan files created via `terraform plan -out=tfplan` contain embedded state representations and planned attributes, including any provider credentials or environment configuration passed during evaluation.
- **Public Log Leakage:** Running commands such as `terraform show -json` or unredacted CLI plan outputs can inadvertently leak sensitive connection strings or tokens into public GitHub Actions runner logs.

### 2.2 Named Custodian & Single-Writer Procedure
To prevent state corruption, concurrent mutation, and unauthorized execution:
- **Primary State Custodian:** `@Suthankan1` (Platform Lead). No other team member or automated workflow is authorized to mutate local Terraform state without prior peer review and documented delegation.
- **Pre-Operation Verification Check:** Before executing any local plan or state operation, the operator must verify:
  1. No background Terraform process is currently running on the host (`pgrep terraform` returns empty).
  2. The working directory is clean and synchronised with `origin/test`.
  3. No concurrent operator has acquired the lock.
- **Local Lock Mechanics:** Terraform local state uses a lock file (`.terraform.tfstate.lock.info`) to serialize access. 
  - Under **no circumstances** shall the operator execute Terraform with `-lock=false` to bypass lock contention.
  - Automatic `terraform force-unlock` scripts are strictly prohibited.
  - **Stale Lock Escalation Procedure:** If a lock persists following a terminal crash or system freeze:
    1. Inspect `.terraform.tfstate.lock.info` to extract the Lock ID, process PID, and timestamp.
    2. Confirm whether the recorded PID is dead (`ps -p <PID>` returns empty).
    3. Document the stale lock incident.
    4. Execute manual unlock specifying the verified Lock ID: `terraform force-unlock <LOCK_ID>`.

### 2.3 Local State Storage Invariants (Local Operator Experiments Only)
When local experiments are authorized under an approved task:
1. **Isolated Path Outside Git Checkout:** Local state files must reside in a dedicated operator directory outside the Git workspace:
   ```bash
   # Approved local state directory (outside repo)
   ~/.swiftdrop/terraform/state/
   ```
2. **Restrictive POSIX Permissions:**
   - State directory permissions: `0700` (`drwx------` — readable/writable only by the operator user).
   - State file permissions: `0600` (`-rw-------` — readable/writable only by the operator user).
   - Enforce:
     ```bash
     mkdir -p ~/.swiftdrop/terraform/state
     chmod 700 ~/.swiftdrop/terraform/state
     ```
3. **Host-Level Full-Disk Encryption at Rest:**
   - The primary operator host (macOS Darwin Apple Silicon) must have FileVault full-disk encryption enabled (XTS-AES-128 with a 256-bit key).
   - Verification command:
     ```bash
     fdesetup status
     # Must return: FileVault is On.
     ```
4. **Prohibition of Consumer Cloud Sync:**
   - The state directory must NEVER reside within folders automatically synchronized to consumer cloud services (e.g., `~/Library/Mobile Documents/com~apple~CloudDocs/` (iCloud Drive), Dropbox, Google Drive, or OneDrive).
5. **No Symlink Escapes:**
   - Symlinks pointing from public or shared workspace directories into the private state directory are strictly prohibited.

### 2.4 Git and CI Safeguards
- **Version Control Protections:** The repository's [`.gitignore`](../../.gitignore) enforces strict exclusion:
  ```gitignore
  **/.terraform/
  *.tfstate
  *.tfstate.*
  *.tfstate.backup
  *.tfplan
  *.tfvars
  *.tfvars.json
  !*.example.tfvars
  ```
- **Tracked Lockfile Invariant:** Multi-platform dependency lockfile [`platform/terraform/.terraform.lock.hcl`](../../platform/terraform/.terraform.lock.hcl) remains strictly tracked to ensure cryptographic provider provenance across macOS and Linux runners.
- **CI Artifact Upload Prohibition:** Workflow security tests ([`test_workflow_security.py`](../../tests/terraform-policy/test_workflow_security.py)) statically inspect all GitHub Actions workflows to guarantee `actions/upload-artifact` never targets `.tfstate` or `.tfplan` files.

---

## 3. Offline Encrypted Backup & Disaster Recovery Policy

### 3.1 Encrypted Offline Backup Specification
To protect against host SSD failure, data corruption, or accidental deletion, an encrypted offline backup procedure is established:
- **Backup Custodian:** `@Suthankan1`.
- **Backup Directory:** `~/.swiftdrop/backups/terraform/` (outside the repository).
- **Naming / Versioning Convention:**
  ```text
  terraform-state-<YYYYMMDD-HHMMSS>-<SHA256:8>.tar.gz.enc
  ```
- **Symmetric Encryption Standard:** Industry-standard OpenSSL AES-256-CBC with PBKDF2 key derivation (`openssl enc -aes-256-cbc -pbkdf2 -iter 100000`). Custom cryptographic implementations are strictly forbidden.
- **Offline Passphrase Custody:** The encryption passphrase is held offline by the operator in a secure password manager; under no circumstances is any password, recovery key, or environment variable committed to Git.
- **Integrity Checksum Sidecar:** Every backup archive is paired with an unencrypted SHA-256 checksum sidecar file (`<backup-file>.sha256`).
- **File Mode:** `chmod 600` on both archive and checksum files.
- **Retention Schedule:** Retain the 5 most recent revisions or 30 days, whichever represents greater historical coverage. Older backups are securely shredded (`rm -P` or `srm` where available).

### 3.2 Backup Creation Procedure
```bash
# Set safe umask
umask 077

# Define timestamp and paths
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
STATE_DIR="$HOME/.swiftdrop/terraform/state"
BACKUP_DIR="$HOME/.swiftdrop/backups/terraform"
mkdir -p "$BACKUP_DIR"

# 1. Create temporary unencrypted tarball in memory / secure temp
TMP_TAR=$(mktemp /tmp/tfstate-backup.XXXXXX.tar.gz)
tar -czf "$TMP_TAR" -C "$STATE_DIR" .

# 2. Compute SHA256 of the unencrypted payload
PAYLOAD_SHA=$(shasum -a 256 "$TMP_TAR" | awk '{print $1}')
SHORT_SHA=${PAYLOAD_SHA:0:8}
BACKUP_FILE="$BACKUP_DIR/terraform-state-${TIMESTAMP}-${SHORT_SHA}.tar.gz.enc"

# 3. Encrypt archive with AES-256-CBC PBKDF2
openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -in "$TMP_TAR" -out "$BACKUP_FILE"
rm -f "$TMP_TAR"

# 4. Generate SHA256 checksum sidecar of the encrypted archive
shasum -a 256 "$BACKUP_FILE" > "${BACKUP_FILE}.sha256"
chmod 600 "$BACKUP_FILE" "${BACKUP_FILE}.sha256"
```

### 3.3 Recovery Rehearsal & Verification Procedure
The disaster recovery workflow is verified deterministically:
1. **Pre-Flight Check:** Verify backup archive exists and is non-empty.
2. **Integrity Verification:** Compare computed SHA-256 checksum against sidecar (`shasum -a 256 -c "${BACKUP_FILE}.sha256"`). If mismatch is detected, halt immediately (fail closed).
3. **Decryption to Isolated Sandbox:** Decrypt into an isolated temporary directory (`/tmp/swiftdrop-recovery-sandbox-XXXXXX/`). Never decrypt directly over an existing state directory.
4. **Validation:** Verify restored state file passes JSON structure validation (`jq empty terraform.tfstate`).
5. **Atomic Promotion (Manual Confirmation):** Only after operator verification is the restored state moved into active custody.
6. **Incident Logging:** Record recovery timestamp, restored SHA-256, and reason for restore in operational logs.

> [!IMPORTANT]
> **Operational Evidence Classification:**  
> The automated synthetic backup-and-restore test ([`tests/terraform-policy/test_state_custody.py`](../../tests/terraform-policy/test_state_custody.py)) proves that the packaging, encryption, checksumming, corruption detection, and sandbox restore logic execute deterministically without leaking state data.  
> However, because live cloud deployment is NOT AUTHORIZED and zero live production state exists, live production recovery remains **UNVERIFIED**. Synthetic rehearsal is not claimed as live recovery signoff.

---

## 4. Evidence-Based Evaluation of Remote State Options

Prior to finalizing the state decision, candidate remote state storage mechanisms were evaluated against the strict $0 hosting mandate and security criteria:

| Evaluation Criterion | Option 1: Local State (Selected) | Option 2: Azure Blob Storage (`azurerm`) | Option 3: Local MinIO S3 Backend | Option 4: Encrypted Offline Backup |
|---|---|---|---|---|
| **Hosting Dependency** | Local workstation (`darwin_arm64`) | Microsoft Azure cloud | Local k3d cluster container | Offline storage volume |
| **Encryption at Rest** | Host FileVault (XTS-AES-128/256) | Azure SSE (AES-256) / CMK | MinIO server-side encryption | OpenSSL AES-256-CBC PBKDF2 |
| **In-Transit Protection** | N/A (local filesystem bus) | Mandatory TLS 1.2 / 1.3 | Local cluster TLS / loopback | N/A (offline file) |
| **State Locking** | POSIX file lock (`.lock.info`) | Azure Blob native lease blob | S3 DynamoDB simulation / MinIO lock | File-level atomic copy |
| **Access Control (IAM)** | OS User UID / POSIX permissions | Entra ID RBAC / SAS / Account Key | MinIO S3 IAM credentials | Operator passphrase / private key |
| **Access Auditing** | macOS unified log / auditd | Azure Monitor Storage Logs | Container stdout / MinIO logs | Git & filesystem audit records |
| **Disaster Recovery (RPO/RTO)** | RPO: Daily / RTO: <15 mins | Geo-replicated / soft delete | Local snapshot / RTO: 30 mins | RPO: Per-operation / RTO: <5 mins |
| **Retention Policy** | 30 days / 5 revisions | Blob lifecycle management | Bucket lifecycle rules | 30 days / 5 revisions |
| **Team Collaboration** | Single operator only | Distributed multi-user | Multi-user via port-forward | Single operator archive |
| **Full Resource Cost** | **$0.00** | **Metered billable service** | **$0.00** (local container) | **$0.00** |
| **Pricing Meters** | None | Capacity, Write ops, Read ops, Egress | None | None |
| **Subscription Eligibility** | Pre-existing hardware | Azure Students (Offer `MS-AZR-0170P`) | Pre-existing hardware | Pre-existing hardware |
| **Zero-Cost Hard-Stop?** | Sunk cost; 100% immune | **NO** (Metered operations overage) | Local disk capacity limit | Local disk capacity limit |
| **Credential Overhead** | None | Entra ID / OIDC or Storage Key | S3 Access Key / Secret Key | Symmetric passphrase |
| **Operational Complexity** | Very Low | High (Cloud setup, RBAC, network) | Medium (K8s bootstrap dependency) | Low |
| **Approval Status** | **APPROVED FOR LOCAL EXPERIMENTS** | **REJECTED — STRICTLY PROHIBITED** | **DEFERRED (Circular Dependency)** | **APPROVED FOR DR RECOVERY** |

### Analysis of Rejection:
- **Azure Storage (`azurerm` backend) is REJECTED:** Under the Azure for Students offer (`MS-AZR-0170P`), Azure Storage accounts incur transaction fees for every Blob read/write/lease API call ($0.05/10k write, $0.004/10k read) plus capacity charges. There is no mathematical guarantee that storage account transactions will remain strictly $0.00 without consuming promotional credit or incurring overage charges.
- **MinIO S3 is DEFERRED:** Utilizing a local MinIO bucket as a remote Terraform backend introduces a bootstrap circular dependency: Terraform cannot provision or manage the infrastructure that hosts the MinIO container upon which Terraform depends for state.

### Authoritative Determination:
```
REMOTE TERRAFORM STATE: DEFERRED — NO CLOUD BACKEND CONFIGURED.
CI INITIALIZATION: -backend=false -lockfile=readonly.
ACTIVE BACKEND: NONE.
```

---

## 5. Least-Privilege GitHub Actions OIDC Security Boundary

### 5.1 Architecture & Threat Model
GitHub Actions OpenID Connect (OIDC) enables workflow jobs to request short-lived, cryptographically signed JSON Web Tokens (JWTs) from GitHub's OIDC provider (`https://token.actions.githubusercontent.com`). These tokens can be exchanged with cloud providers (such as Microsoft Entra ID) for short-lived cloud access tokens, eliminating the need to store long-lived, high-risk credentials (such as Azure client secrets or service principal passwords) in GitHub repository secrets.

However, improperly configured OIDC federation exposes the cloud tenant to severe supply-chain risks:
- **Wildcard Subject Trust:** Trusting `repo:Suthankan1/swiftdrop:*` permits any workflow running on any branch or pull request to impersonate the identity.
- **Pull Request Token Requests:** Workflows triggered by `pull_request` from untrusted contributors or forks can execute malicious build steps that request tokens and exfiltrate cloud data.
- **Over-Privileged Roles:** Assigning broad Azure built-in roles (e.g., `Owner` or `Contributor`) allows an authenticated workflow to provision billable infrastructure, alter IAM policies, or destroy resources.

### 5.2 The Non-Executable Trust Policy Contract
The trust boundary is formally codified in [`platform/security/github-oidc-trust-policy.yaml`](../../platform/security/github-oidc-trust-policy.yaml).  
Key contract specifications:

```yaml
version: "1.0"
policy_mode: "deny-by-default"

cloud_identity:
  enabled: false
  deployment_authorized: false
  authentication_authorized: false
  provider: "microsoft-entra-id"
  federation_type: "workload-identity-federation"

github:
  repository: "Suthankan1/swiftdrop"
  repository_id: 1420084393
  owner_id: 131566783
  allow_pull_requests: false
  allow_forks: false
  allow_untrusted_code: false
  allowed_events: [] # STRICTLY EMPTY WHILE DISABLED

  oidc:
    enabled: false
    issuer: "https://token.actions.githubusercontent.com"
    audience: "api://AzureADTokenExchange"
    approved_subjects: [] # STRICTLY EMPTY WHILE DISABLED
    approved_branches: [] # STRICTLY EMPTY WHILE DISABLED
    approved_environments: [] # STRICTLY EMPTY WHILE DISABLED
    max_token_duration_seconds: 3600
    permissions:
      id_token_write_authorized: false
      repository_write_authorized: false
```

### 5.3 Exact Claim Semantics & Microsoft Entra Subject Matching
GitHub OIDC token claims vary dynamically based on the triggering workflow context:

| Workflow Context | GitHub OIDC Subject (`sub` claim) | Additional Claims Present | Security Implication |
|---|---|---|---|
| **Branch Push / Dispatch** | `repo:Suthankan1/swiftdrop:ref:refs/heads/<branch>` | `ref`, `sha`, `repository_id` | Bound strictly to branch ref. |
| **Environment Job** | `repo:Suthankan1/swiftdrop:environment:<env-name>` | `environment`, `ref`, `job_workflow_ref` | Bound strictly to environment name. |
| **Pull Request (Untrusted)** | `repo:Suthankan1/swiftdrop:pull_request` | `base_ref`, `head_ref` | **STRICTLY PROHIBITED.** |
| **Forked Repository PR** | `repo:<fork-owner>/swiftdrop:pull_request` | Fork metadata | **STRICTLY PROHIBITED.** |

#### Critical Entra ID Matching Constraint:
Microsoft Entra ID federated identity credentials match against a single `subject` string (along with issuer and audience).
- When a GitHub Actions job specifies an `environment:` block, GitHub sets `sub: repo:Suthankan1/swiftdrop:environment:<env-name>`. **The branch is NOT part of the `sub` claim.**
- Instead, branch restrictions in environment-scoped workflows must be enforced by **GitHub Environment Deployment Branch Policies**.
- If GitHub Environments or Deployment Branch Policies are missing, misconfigured, or unsupported by the repository plan, Entra ID subject matching alone CANNOT verify that the workflow was invoked from an approved branch.
- **Fail-Closed Resolution:** Because GitHub Environments with required reviewers are currently **UNCONFIGURED (`total_count: 0`)**, the combination of branch restriction and manual peer approval cannot be validated. Therefore, **cloud OIDC federation remains DISABLED (`enabled: false`)**. Wildcard subject matching (e.g., `*`) is strictly rejected.

### 5.4 Active GitHub Actions CI Workflow Invariants
The existing workflow security scanner ([`tests/terraform-policy/test_workflow_security.py`](../../tests/terraform-policy/test_workflow_security.py)) enforces the following invariants across all files in `.github/workflows/`:
1. **Top-Level `id-token: write` Prohibition:** No workflow shall grant top-level OIDC write permissions.
2. **Untrusted Job `id-token: write` Prohibition:** Active PR validation jobs (`ci.yml`, `contract-checks.yml`, `security-scans.yml`) must operate under least privilege (`permissions: contents: read`).
3. **No Cloud Authentication Steps:** Steps executing `az login`, `azure-cli login`, or setting Azure credentials are strictly prohibited and trigger automated CI failure.
4. **Action Pinning to Full Commit SHAs:** All external actions must be pinned to immutable 40-character commit SHAs with human-readable version comments.
5. **Prohibition of Insecure Triggers:** The `pull_request_target` trigger is strictly prohibited.

---

## 6. Proposed Least-Privilege Azure RBAC Model (Hypothetical / Disabled)

If an authorized, read-only plan validation workflow is considered under a future release milestone, it must adhere to the following privilege boundary:

### 6.1 Prohibited Azure Roles
The following roles are **STRICTLY PROHIBITED**:
- `Owner` (Full administrative control, billing access, role assignment).
- `Contributor` (Create/delete/modify cloud resources, spin up billable VMs).
- `User Access Administrator` (Assign arbitrary roles to users or service principals).
- `Role Based Access Control Administrator`.
- Any management group or subscription-root-level write assignment.

### 6.2 Proposed Future Read-Only Scope
For a hypothetical read-only validation workflow:
- **Maximum Scope:** Narrow, isolated resource group only (e.g., `/subscriptions/<SUB_ID>/resourceGroups/rg-swiftdrop-validation`). Never subscription-wide root (`/subscriptions/<SUB_ID>`).
- **Minimum Role:** Built-in `Reader` role or custom least-privilege role constrained strictly to:
  - `Microsoft.Resources/subscriptions/resourceGroups/read`
  - `Microsoft.Resources/deployments/read`
- **Terraform Data-Source Side Effect Warning:** Terraform `plan` commands evaluate `data` blocks by executing live read API queries against cloud providers. Data source queries can trigger regional network calls, hit rate limits, or produce transient failures. Provider behavior must be audited prior to any live read-only plan authorization.
- **Current Live Status:**
  ```
  STATUS: BLOCKED — CLOUD IDENTITY NOT CONFIGURED.
  APP REGISTRATIONS: NONE.
  AZURE MANAGED IDENTITIES: NONE.
  FEDERATED IDENTITY CREDENTIALS: NONE.
  ROLE ASSIGNMENTS: NONE.
  CLIENT SECRETS: STRICTLY PROHIBITED.
  ```

---

## 7. GitHub Environment Capability Assessment & Issue #167 Coordination

A read-only API inspection was performed against repository `Suthankan1/swiftdrop`:

### 7.1 Verified Repository Settings (Read-Only Audit)
- **Repository Visibility:** Public (`visibility: "public"`, `private: false`).
- **Repository ID:** `1420084393`.
- **Repository Owner ID:** `131566783`.
- **GitHub Environments:** `total_count: 0` (`[]` — No environments currently exist).
- **Branch Rulesets:** Ruleset `25813062` ("Protect main and test") is active on `refs/heads/test` and `~DEFAULT_BRANCH` (`main`), enforcing 7 required status checks:
  1. `Frontend Quality & Build`
  2. `Java 21 Services Build & Test`
  3. `Container Security & Missing-Target Gate`
  4. `GitGuardian Security Checks`
  5. `Identity Unit & Configuration Suite`
  6. `Contract Validation & Compatibility Gates`
  7. `Terraform Static Quality & Deny-by-Default Policy Gates`
- **Required Reviewers on Ruleset:** `required_approving_review_count: 0`.

### 7.2 Capability Limitations & Impact
1. **Environment Protection Rules:** Although public repositories support GitHub Environments with required reviewers, **no environments are currently defined in this repository**.
2. **Fail-Closed Governance:** Because no protected environment exists with enforced human approvals and branch restrictions:
   - Automated cloud OIDC token issuance cannot be secured behind required human reviewers.
   - Any future cloud job remains **HARD BLOCKED**.
   - Cloud deployment and staging promotion cannot rely on GitHub native environment gates alone.

### 7.3 Handoff to Issue #167 (Gated Staging Promotion & Recovery Drills)
Issue #167 owns release promotion, rollback, and disaster recovery drills. This document establishes:
1. **No Cloud OIDC Prerequisite for #167:** Release promotion and database restore drills operate entirely on local k3d / K3s topology and local PostgreSQL volumes.
2. **Fail-Closed Manual Promotion Checklist:** In the absence of native GitHub environment approval rules, release promotion from `test` to `main` mandates an explicit, documented peer review signoff from `@Harithkeshan` and `@Vibhushan-2003`.
3. **State Custody Integration:** If local Terraform state is utilized in staging drills, it must adhere to §2 (named custody, single writer, encrypted offline backups).

---

## 8. Downstream Technical Handoff Index

| Downstream Issue | Task Title | Primary Owner | Consumed State & OIDC Boundary |
|---|---|---|---|
| **[#167](https://github.com/Suthankan1/swiftdrop/issues/167)** | **Gated staging promotion, rollback and recovery drill** | @Suthankan1 | Consumes fail-closed manual promotion gate; local database restore drills are independent of cloud OIDC. |
| **[#175](https://github.com/Suthankan1/swiftdrop/issues/175)** | **Local k3d/K3s cluster bootstrap** | @Suthankan1 | Local cluster state (k3s SQLite/etcd) is completely independent of Terraform state; requires zero cloud credentials. |
| **[#176](https://github.com/Suthankan1/swiftdrop/issues/176)** | **$0 HTTPS and public access feasibility** | @Suthankan1 | Zero-cost tunnels (Cloudflare) do not require cloud OIDC or remote state; local fallback remains default. |
| **[#177](https://github.com/Suthankan1/swiftdrop/issues/177)** | **$0 operator handover and evidence index** | @Suthankan1 | Indexes state custody rules, backup procedures, and verified offline recovery test evidence. |
| **[#178](https://github.com/Suthankan1/swiftdrop/issues/178)** | **Optional future Azure spending authorization** | @Suthankan1 | Gated strictly as an optional future task documented in [`docs/deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md`](../deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md). Cloud identity and remote state remain blocked until #178 is authorized. |

---

## 9. Review & Verification Signoff Requirements

Prior to merging into `test`:
- [x] Authoritative state decision recorded: Remote state DEFERRED; CI backend disabled.
- [x] Named state custodian assigned (@Suthankan1).
- [x] Local state directory, permissions (`0700`/`0600`), and FileVault encryption requirements specified.
- [x] Single-writer procedure, locking semantics, and stale lock recovery documented.
- [x] Encrypted offline backup procedure (AES-256-CBC, PBKDF2, SHA-256 sidecar) defined.
- [x] Remote state options compared; billable Azure Storage backend rejected.
- [x] Non-executable OIDC trust policy contract defined in `platform/security/github-oidc-trust-policy.yaml`.
- [x] OIDC cloud identity disabled by default (`enabled: false`).
- [x] Prohibited Azure roles (Owner, Contributor, User Access Admin) formalized.
- [x] GitHub environment capability audited; zero environments recorded.
- [x] Negative security tests assert denial of untrusted claims, state leaks, and role escalations.
- [ ] Platform architecture signoff: **@Harithkeshan**
- [ ] Independent security review signoff: **@Vibhushan-2003**
