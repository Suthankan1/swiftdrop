# SwiftDrop Terraform Policy & IaC Workflow Security Test Suite

## 1. Overview

This directory contains the automated test suite enforcing the strict $0 hosting and cloud security policies established in **ADR-0011** and **GitHub Issue #173 (N10-ZC02)**.

- **Canonical Policy:** [`platform/security/azure-allowlist-policy.yaml`](../../platform/security/azure-allowlist-policy.yaml)
- **Policy Validator CLI:** [`platform/security/validate_terraform_policy.py`](../../platform/security/validate_terraform_policy.py)
- **Synthetic Plan Fixtures:** [`tests/terraform-policy/fixtures/`](fixtures/)

---

## 2. Test Architecture

The suite consists of two core modules:

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
  - Structural Variations: Resources in deeply nested modules (`module.platform_compute.module.workers...`), provider aliases, multiple instances (`[0]`, `[1]`).
  - Strict Deterministic Constraints: Unknown regions (`after_unknown.location`), unknown SKUs, unapproved SKUs, unapproved regions, expired pricing evidence, nonzero metered billing, promotional credit claims (`azure_students_credit`), unapproved subscriptions, unapproved default-off override attempts.
  - Error Handling (Fail Closed): Malformed JSON plans, missing plan files, missing allowlist files.
- **CLI Invocations:** Tests CLI exit codes (`0` for PASS, `1` for DENY, `2` for ERROR).

### B. Workflow Security Tests (`test_workflow_security.py`)
Statically inspects all executable `.github/workflows/*.yml` definitions:
- **Least Privilege:** Asserts permissions are scoped to `contents: read` on PR validation; rejects broad write permissions (`write-all`) and `id-token: write`.
- **Immutable Tooling:** Asserts all third-party GitHub Actions are pinned to full 40-character commit SHAs.
- **Trigger Security:** Prohibits elevated execution via `pull_request_target`.
- **Zero Cloud Mutations:** Statically scans `run:` steps to assert no `terraform apply`, `terraform destroy`, `az login`, or credential leakage occurs.
- **State Leakage Protection:** Prohibits uploading `.tfstate` or `.tfplan` files via artifact upload steps.
- **Negative Control Tests:** Verified against synthetic insecure workflow definitions.

---

## 3. Running the Tests

```bash
# Run using Python's built-in unittest discovery
python3 -m unittest discover -s tests/terraform-policy -p "test_*.py" -v

# Or run using pytest
python3 -m pytest tests/terraform-policy -v
```
