# SwiftDrop Terraform Policy, State Custody & OIDC Security Test Suite

## 1. Overview

This directory contains the automated test suite enforcing the strict $0 hosting, safe state custody, and cloud workload identity security policies established in **ADR-0011**, **GitHub Issue #173 (N10-ZC02)**, and **GitHub Issue #174 (N10-ZC03)**.

- **Canonical Architecture Reference:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)
- **State Custody & OIDC Security Policy:** [`docs/security/TERRAFORM_STATE_AND_OIDC_BOUNDARY.md`](../../docs/security/TERRAFORM_STATE_AND_OIDC_BOUNDARY.md)
- **Machine-Readable Allowlist Contract:** [`platform/security/azure-allowlist-policy.yaml`](../../platform/security/azure-allowlist-policy.yaml)
- **Machine-Readable OIDC Trust Contract:** [`platform/security/github-oidc-trust-policy.yaml`](../../platform/security/github-oidc-trust-policy.yaml)
- **Policy Validator CLI:** [`platform/security/validate_terraform_policy.py`](../../platform/security/validate_terraform_policy.py)
- **Synthetic Plan Fixtures:** [`tests/terraform-policy/fixtures/`](fixtures/)

---

## 2. Test Architecture

The suite consists of four complementary security test modules:

### A. Policy Enforcement Tests (`test_policy.py`)
Evaluates Terraform JSON plans against the deny-by-default allowlist:
- **Canonical Policy Contract Invariants:** Verifies `cost_ceiling_usd == 0.00`, `deployment_authorized == false`, `allowed_resources == []`, `allowed_skus == {}`, `allowed_regions == []`, `subscription_status == "UNVERIFIED"`. Fails closed if policy is missing, corrupted, or missing required authorization fields.
- **Positive Controls (PASS):**
  - `empty-plan.json`: Minimal valid plan with 0 resources.
  - `local-only-plan.json`: Plan containing non-cloud local resources (`local_file`).
  - `noop-root-module-plan.json`: Harmless output-only plan.
  - False-positive avoidance: Harmless architectural strings or filenames mentioning "azure" do not cause false rejections.
- **Negative Controls (DENY):**
  - Prohibited Cloud Resources: Azure VMs (`azurerm_linux_virtual_machine`), managed disks, public IPs, NAT gateways, load balancers, AKS clusters, Azure Storage accounts, Azure Functions, Static Web Apps, and paid add-ons (Log Analytics workspaces).
  - Remote State Backend: Prohibits `azurerm` state backends.
  - Structural Variations: Resources in deeply nested modules, provider aliases, multiple instances.
  - Strict Deterministic Constraints: Unknown regions, unknown SKUs, unapproved SKUs, unapproved regions, expired pricing evidence, nonzero metered billing, promotional credit claims, unapproved subscriptions, unapproved default-off override attempts.
  - Error Handling (Fail Closed): Malformed JSON plans, missing plan files, missing allowlist files.
- **CLI Invocations:** Tests CLI exit codes (`0` for PASS, `1` for DENY, `2` for ERROR).

### B. Workflow Security Tests (`test_workflow_security.py`)
Statically inspects all executable `.github/workflows/*.yml` definitions:
- **Least Privilege:** Asserts permissions are scoped to `contents: read` on PR validation; rejects broad write permissions (`write-all`) and `id-token: write`.
- **Immutable Tooling:** Asserts all third-party GitHub Actions are pinned to full 40-character commit SHAs.
- **Trigger Security:** Prohibits elevated execution via `pull_request_target`.
- **Zero Cloud Mutations:** Statically scans `run:` steps to assert no `terraform apply`, `terraform destroy`, `az login`, or credential leakage occurs.
- **State Leakage Protection:** Prohibits uploading `.tfstate` or `.tfplan` files via artifact upload steps.

### C. OIDC Trust Boundary Tests (`test_oidc_trust.py`)
Validates the non-executable GitHub Actions OIDC workload identity federation policy:
- **Canonical Invariants:** Asserts `cloud_identity.enabled == false`, `deployment_authorized == false`, `approved_subjects == []`, `client_secret_permitted == false`.
- **Positive Controls:** Verifies structure and confirms that well-formed hypothetical trust fixtures structurally parse without activating cloud access.
- **Negative Rejection Controls:** Deterministically asserts explicit rejection for:
  - Wrong repository, wrong owner, wrong repository ID.
  - Wrong branch, wrong environment, wrong workflow ref.
  - Wrong issuer (`iss`), wrong audience (`aud`).
  - Wildcard subjects (`*`), pull-request subjects (`:pull_request`), fork executions.
  - Unprotected environments lacking required human reviewers.
  - Top-level and untrusted job-level `id-token: write`.
  - Broad repository permissions (`write-all`, `contents: write`).
  - Prohibited Azure roles (`Owner`, `Contributor`, `User Access Administrator`).
  - Unapproved RBAC scopes (root `/`, wildcard `*`).
  - Long-lived client secrets.
  - Unauthorized remote state backend configurations.

### D. State Custody & Disaster Recovery Tests (`test_state_custody.py`)
Enforces local state custody rules, Git ignore safety, and offline recovery:
- **Git Ignore Invariants:** Verifies `.gitignore` excludes `*.tfstate`, `*.tfstate.*`, `*.tfstate.backup`, `*.tfplan`, `tfplan`, `*.tfvars`, `*.tfvars.json`, and `**/.terraform/`. Asserts multi-platform dependency lockfile `.terraform.lock.hcl` remains tracked.
- **Backend Configuration Checks:** Inspects `platform/terraform/*.tf` to verify no remote backend (`backend "..."`) is declared and zero managed cloud resources exist.
- **CI State Leakage Checks:** Verifies workflows do not upload state or plan artifacts and clean temporary plans immediately.
- **Synthetic Backup & Recovery Rehearsal:**
  - Creates synthetic state in an isolated sandbox.
  - Packages and encrypts with OpenSSL AES-256-CBC PBKDF2 with restricted POSIX mode (`0600`).
  - Generates SHA-256 checksum sidecar.
  - Rehearses decryption and restore into clean temporary directory; validates JSON integrity.
  - Tests corruption detection: byte corruption triggers SHA-256 mismatch and aborts restore.
  - Tests missing backup handling (fails safely).
  - Tests overwrite protection: restore refuses to overwrite existing live state.

---

## 3. Running the Complete Test Suite

```bash
# Run using Python's built-in unittest discovery
python3 -m unittest discover -s tests/terraform-policy -p "test_*.py" -v

# Or run using pytest
python3 -m pytest tests/terraform-policy -v
```
