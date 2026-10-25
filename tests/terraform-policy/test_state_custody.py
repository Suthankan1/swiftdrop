"""
Automated Terraform State Custody & Disaster Recovery Test Suite for SwiftDrop.

Canonical References:
- docs/security/TERRAFORM_STATE_AND_OIDC_BOUNDARY.md
- platform/security/github-oidc-trust-policy.yaml
- platform/security/azure-allowlist-policy.yaml
- GitHub Issue #174 (N10-ZC03)
"""

import hashlib
import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
TERRAFORM_DIR = REPO_ROOT / "platform/terraform"
WORKFLOWS_DIR = REPO_ROOT / ".github/workflows"
GITIGNORE_PATH = REPO_ROOT / ".gitignore"


def compute_sha256(file_path: Path) -> str:
    """Computes hexadecimal SHA-256 digest of a file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def run_synthetic_backup(
    state_dir: Path,
    backup_dir: Path,
    passphrase: str,
    backup_name: str = "synthetic-state"
) -> Tuple[Path, Path]:
    """
    Creates an encrypted offline backup archive and SHA-256 checksum sidecar.
    Uses OpenSSL AES-256-CBC with PBKDF2.
    """
    backup_dir.mkdir(parents=True, exist_ok=True)
    tar_path = backup_dir / f"{backup_name}.tar.gz"
    enc_path = backup_dir / f"{backup_name}.tar.gz.enc"
    sha_path = backup_dir / f"{backup_name}.tar.gz.enc.sha256"

    # 1. Create tar archive of state directory
    cmd_tar = ["tar", "-czf", str(tar_path), "-C", str(state_dir), "."]
    subprocess.run(cmd_tar, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    try:
        # 2. Encrypt with OpenSSL AES-256-CBC PBKDF2
        cmd_enc = [
            "openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "100000",
            "-salt", "-in", str(tar_path), "-out", str(enc_path),
            "-pass", f"pass:{passphrase}"
        ]
        subprocess.run(cmd_enc, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        if tar_path.exists():
            tar_path.unlink()

    # 3. Create SHA-256 sidecar
    digest = compute_sha256(enc_path)
    with open(sha_path, "w", encoding="utf-8") as f:
        f.write(f"{digest}  {enc_path.name}\n")

    # 4. Enforce restrictive permissions (0600)
    os.chmod(enc_path, 0o600)
    os.chmod(sha_path, 0o600)

    return enc_path, sha_path


def run_synthetic_restore(
    enc_path: Path,
    sha_path: Path,
    restore_dir: Path,
    passphrase: str,
    allow_overwrite: bool = False
) -> Dict[str, Any]:
    """
    Rehearses restoring state from an encrypted archive into an isolated sandbox.
    Verifies checksum integrity before decryption and validates JSON structure.
    """
    if not enc_path.exists():
        raise FileNotFoundError(f"Backup archive missing: {enc_path}")
    if not sha_path.exists():
        raise FileNotFoundError(f"Checksum sidecar missing: {sha_path}")

    # 1. Pre-flight integrity verification
    with open(sha_path, "r", encoding="utf-8") as f:
        expected_digest = f.read().split()[0].strip()
    actual_digest = compute_sha256(enc_path)
    if actual_digest != expected_digest:
        raise ValueError(
            f"CORRUPTED_BACKUP_INTEGRITY_MISMATCH: actual {actual_digest} != expected {expected_digest}"
        )

    # 2. Overwrite protection
    if restore_dir.exists() and any(restore_dir.iterdir()) and not allow_overwrite:
        raise FileExistsError(f"Target restore directory '{restore_dir}' is not empty")

    restore_dir.mkdir(parents=True, exist_ok=True)
    tar_path = restore_dir / "restored.tar.gz"

    # 3. Decrypt archive
    cmd_dec = [
        "openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "100000",
        "-in", str(enc_path), "-out", str(tar_path),
        "-pass", f"pass:{passphrase}"
    ]
    res = subprocess.run(cmd_dec, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if res.returncode != 0:
        if tar_path.exists():
            tar_path.unlink()
        raise ValueError("DECRYPTION_FAILED: Invalid passphrase or corrupted ciphertext")

    try:
        # 4. Extract tarball
        cmd_extract = ["tar", "-xzf", str(tar_path), "-C", str(restore_dir)]
        subprocess.run(cmd_extract, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        if tar_path.exists():
            tar_path.unlink()

    # 5. Validate restored state JSON
    state_file = restore_dir / "terraform.tfstate"
    if not state_file.exists():
        raise FileNotFoundError("Restored archive does not contain terraform.tfstate")

    with open(state_file, "r", encoding="utf-8") as f:
        state_data = json.load(f)

    return state_data


class TestGitIgnoreProtections(unittest.TestCase):
    """Verifies that Git ignore policies strictly reject state, plans, and secrets."""

    def test_gitignore_contains_state_and_secrets(self):
        self.assertTrue(GITIGNORE_PATH.exists(), f"Missing .gitignore at {GITIGNORE_PATH}")
        with open(GITIGNORE_PATH, "r", encoding="utf-8") as f:
            content = f.read()

        required_patterns = [
            "*.tfstate",
            "*.tfstate.*",
            "*.tfstate.backup",
            "*.tfplan",
            "*.tfvars",
            "*.tfvars.json",
            "**/.terraform/",
        ]
        for pat in required_patterns:
            self.assertIn(pat, content, f".gitignore missing mandatory pattern: '{pat}'")

    def test_git_check_ignore_rejects_state_and_plans(self):
        """Exercises git check-ignore to verify path patterns are active."""
        test_paths = [
            "platform/terraform/terraform.tfstate",
            "platform/terraform/terraform.tfstate.backup",
            "platform/terraform/dev.tfvars",
            "platform/terraform/prod.tfvars.json",
            "platform/terraform/tfplan",
            "platform/terraform/custom.tfplan",
            "platform/terraform/.terraform/providers/registry.terraform.io/hashicorp/azurerm/test",
        ]
        res = subprocess.run(
            ["git", "check-ignore", *test_paths],
            cwd=str(REPO_ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        ignored = set(res.stdout.strip().splitlines())
        for path in test_paths:
            self.assertIn(path, ignored, f"Path was NOT ignored by git: '{path}'")

    def test_lockfile_is_tracked_not_ignored(self):
        """Verifies that .terraform.lock.hcl is NOT ignored by git."""
        res = subprocess.run(
            ["git", "check-ignore", "platform/terraform/.terraform.lock.hcl"],
            cwd=str(REPO_ROOT),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(res.stdout.strip(), "", ".terraform.lock.hcl must NOT be ignored by git")


class TestTerraformBackendConfiguration(unittest.TestCase):
    """Verifies that no remote state backend is configured in platform/terraform/."""

    def test_no_backend_block_in_configuration(self):
        tf_files = list(TERRAFORM_DIR.glob("*.tf"))
        self.assertGreater(len(tf_files), 0, "No .tf files found in platform/terraform/")

        for tf_file in tf_files:
            with open(tf_file, "r", encoding="utf-8") as f:
                content = f.read()
            # Prohibit backend "..." blocks
            self.assertNotRegex(
                content,
                r'\bbackend\s+"[a-zA-Z0-9_\-]+"\s+\{',
                f"File '{tf_file.name}' declares a backend block. Remote state is DEFERRED."
            )

    def test_root_module_declares_zero_managed_resources(self):
        """Verifies main.tf contains no resource blocks."""
        main_tf = TERRAFORM_DIR / "main.tf"
        self.assertTrue(main_tf.exists(), "platform/terraform/main.tf missing")
        with open(main_tf, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertNotRegex(
            content,
            r'\bresource\s+"[a-zA-Z0-9_\-]+"',
            "platform/terraform/main.tf must contain ZERO managed resource blocks"
        )


class TestCiStateLeakageProtection(unittest.TestCase):
    """Verifies that active CI workflows do not upload or leak state or plans."""

    def test_workflows_do_not_upload_state_artifacts(self):
        wf_files = list(WORKFLOWS_DIR.glob("*.yml")) + list(WORKFLOWS_DIR.glob("*.yaml"))
        for wf_file in wf_files:
            with open(wf_file, "r", encoding="utf-8") as f:
                data = yaml.safe_load(f)
            jobs = data.get("jobs", {})
            for job_id, job in jobs.items():
                for step in job.get("steps", []):
                    uses = step.get("uses", "")
                    if "actions/upload-artifact" in uses:
                        path = step.get("with", {}).get("path", "")
                        self.assertNotIn(".tfstate", path, f"{wf_file.name}: uploads state file artifact: {path}")
                        self.assertNotIn(".tfplan", path, f"{wf_file.name}: uploads plan file artifact: {path}")

    def test_ci_workflow_cleans_temporary_plans_immediately(self):
        ci_yml = WORKFLOWS_DIR / "ci.yml"
        self.assertTrue(ci_yml.exists())
        with open(ci_yml, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn("rm -f platform/terraform/tfplan", content)
        self.assertIn("rm -f platform/terraform/root-module-plan.json", content)
        self.assertIn("-backend=false", content)


class TestSyntheticBackupAndRecoveryRehearsal(unittest.TestCase):
    """Verifies offline encrypted backup creation and sandbox disaster recovery."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="swiftdrop-state-test-")
        self.state_dir = Path(self.temp_dir) / "state"
        self.backup_dir = Path(self.temp_dir) / "backup"
        self.restore_dir = Path(self.temp_dir) / "restore"

        self.state_dir.mkdir(parents=True, exist_ok=True)
        self.synthetic_state = {
            "version": 4,
            "terraform_version": "1.8.5",
            "serial": 1,
            "lineage": "synthetic-test-lineage-12345",
            "outputs": {},
            "resources": []
        }
        with open(self.state_dir / "terraform.tfstate", "w", encoding="utf-8") as f:
            json.dump(self.synthetic_state, f, indent=2)

        self.passphrase = "synthetic-test-passphrase-do-not-use-in-prod"

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_synthetic_backup_and_restore_success(self):
        enc_path, sha_path = run_synthetic_backup(
            self.state_dir, self.backup_dir, self.passphrase
        )
        self.assertTrue(enc_path.exists())
        self.assertTrue(sha_path.exists())

        # Check permissions (0600)
        mode_enc = stat.S_IMODE(os.stat(enc_path).st_mode)
        mode_sha = stat.S_IMODE(os.stat(sha_path).st_mode)
        self.assertEqual(mode_enc, 0o600)
        self.assertEqual(mode_sha, 0o600)

        # Restore into clean sandbox
        restored_data = run_synthetic_restore(
            enc_path, sha_path, self.restore_dir, self.passphrase
        )
        self.assertEqual(restored_data["lineage"], self.synthetic_state["lineage"])
        self.assertEqual(restored_data["version"], 4)

    def test_corrupted_backup_rejected_safely(self):
        enc_path, sha_path = run_synthetic_backup(
            self.state_dir, self.backup_dir, self.passphrase
        )
        # Corrupt one byte in the encrypted archive
        with open(enc_path, "r+b") as f:
            f.seek(64)
            original_byte = f.read(1)
            corrupted_byte = bytes([original_byte[0] ^ 0xFF])
            f.seek(64)
            f.write(corrupted_byte)

        with self.assertRaises(ValueError) as ctx:
            run_synthetic_restore(enc_path, sha_path, self.restore_dir, self.passphrase)
        self.assertIn("CORRUPTED_BACKUP_INTEGRITY_MISMATCH", str(ctx.exception))
        self.assertFalse((self.restore_dir / "terraform.tfstate").exists())

    def test_missing_backup_handled_safely(self):
        missing_enc = self.backup_dir / "nonexistent.tar.gz.enc"
        missing_sha = self.backup_dir / "nonexistent.tar.gz.enc.sha256"
        with self.assertRaises(FileNotFoundError):
            run_synthetic_restore(missing_enc, missing_sha, self.restore_dir, self.passphrase)

    def test_restore_does_not_overwrite_live_state(self):
        enc_path, sha_path = run_synthetic_backup(
            self.state_dir, self.backup_dir, self.passphrase
        )
        # Populate restore directory
        self.restore_dir.mkdir(parents=True, exist_ok=True)
        with open(self.restore_dir / "existing_file.txt", "w") as f:
            f.write("pre-existing live data")

        with self.assertRaises(FileExistsError):
            run_synthetic_restore(
                enc_path, sha_path, self.restore_dir, self.passphrase, allow_overwrite=False
            )


if __name__ == "__main__":
    unittest.main()
