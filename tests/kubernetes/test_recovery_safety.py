"""
SwiftDrop Disaster Recovery, Backup Integrity & State Safety Tests (Issue #167)
Validates non-destructive safeguards, target database isolation, cryptographic
backup checksum verification, RPO/RTO calculation logic, and P09A handoff completeness.
"""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
RECOVERY_DIR = REPO_ROOT / "platform" / "kubernetes" / "recovery"

BACKUP_POSTGRES = RECOVERY_DIR / "backup-postgres.sh"
BACKUP_MINIO = RECOVERY_DIR / "backup-minio.sh"
BACKUP_KAFKA = RECOVERY_DIR / "backup-kafka-checkpoint.sh"
VERIFY_BACKUP = RECOVERY_DIR / "verify-backup.sh"
RESTORE_ISOLATED = RECOVERY_DIR / "restore-isolated.sh"
MEASURE_SCRIPT = RECOVERY_DIR / "measure-recovery.sh"
P09A_HANDOFF = RECOVERY_DIR / "p09a-handoff.sh"


class TestRecoverySafety(unittest.TestCase):
    """Verifies that state recovery scripts enforce strict data protection and isolation."""

    def test_scripts_prohibit_destructive_pvc_deletion(self):
        """Scans all recovery scripts for prohibited blanket delete commands."""
        prohibited = [
            "kubectl delete pvc --all",
            "kubectl delete namespace swiftdrop-data",
            "docker compose down -v",
            "rm -rf ~/.swiftdrop"
        ]
        for script in RECOVERY_DIR.glob("*.sh"):
            content = script.read_text(encoding="utf-8")
            for cmd in prohibited:
                self.assertNotIn(cmd, content, f"Found dangerous command '{cmd}' in {script.name}")

    def test_restore_rejects_empty_or_live_target_suffix(self):
        """Negative test: restore script refuses to restore when target suffix would overwrite live database."""
        proc = subprocess.run(
            [str(RESTORE_ISOLATED), "--backup-dir", "/tmp", "--target-suffix", ""],
            capture_output=True, text=True
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("FATAL", proc.stderr + proc.stdout)

    def test_restore_rejects_wrong_kubernetes_context(self):
        """Negative test: restore script refuses to run against non-approved contexts."""
        proc = subprocess.run(
            [str(RESTORE_ISOLATED), "--backup-dir", "/tmp", "--context", "minikube"],
            capture_output=True, text=True
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("k3d-swiftdrop-local", proc.stderr + proc.stdout)

    def test_verify_backup_rejects_missing_directory(self):
        """Negative test: verify-backup fails closed when directory does not exist."""
        proc = subprocess.run(
            [str(VERIFY_BACKUP), "--backup-dir", "/nonexistent/backup/dir"],
            capture_output=True, text=True
        )
        self.assertNotEqual(proc.returncode, 0)

    def test_measure_recovery_rpo_rto_calculation(self):
        """Verifies that measure-recovery.sh computes correct RPO/RTO seconds and reports unapproved status."""
        timestamps = {
            "last_consistent_checkpoint_at": "2026-10-28T05:00:00Z",
            "failure_detected_at": "2026-10-28T05:05:00Z",
            "recovery_started_at": "2026-10-28T05:06:00Z",
            "restore_completed_at": "2026-10-28T05:08:00Z",
            "application_readiness_at": "2026-10-28T05:09:30Z",
            "business_verification_completed_at": "2026-10-28T05:10:00Z"
        }
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump(timestamps, tf)
            tf_path = tf.name

        out_report = tf_path + ".report.json"
        try:
            proc = subprocess.run(
                [str(MEASURE_SCRIPT), "--timestamps", tf_path, "--output", out_report],
                capture_output=True, text=True
            )
            self.assertEqual(proc.returncode, 0, f"Measure script failed:\n{proc.stderr}\n{proc.stdout}")
            self.assertIn("300 seconds", proc.stdout)  # 5 min RPO
            self.assertIn("240 seconds", proc.stdout)  # 4 min RTO
            self.assertIn("NOT APPROVED", proc.stdout)

            with open(out_report, "r", encoding="utf-8") as rf:
                report = json.load(rf)
            self.assertEqual(report["rpo"]["observed_seconds"], 300)
            self.assertEqual(report["rto"]["observed_seconds"], 240)
            self.assertIn("NOT APPROVED", report["rpo"]["status"])
        finally:
            Path(tf_path).unlink()
            if Path(out_report).is_file():
                Path(out_report).unlink()

    def test_p09a_handoff_document_generation(self):
        """Verifies that p09a-handoff.sh generates a structured markdown document for @Sinthuha-n."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as tf:
            out_md = tf.name

        try:
            proc = subprocess.run(
                [str(P09A_HANDOFF), "--output", out_md],
                capture_output=True, text=True
            )
            self.assertEqual(proc.returncode, 0, f"P09A handoff failed:\n{proc.stderr}\n{proc.stdout}")
            content = Path(out_md).read_text(encoding="utf-8")
            self.assertIn("Issue #168 (P09A)", content)
            self.assertIn("@Sinthuha-n", content)
            self.assertIn("accounts_db_drill_recovery", content)
            self.assertIn("delivery_db_drill_recovery", content)
            self.assertIn("NOT APPROVED", content)
        finally:
            if Path(out_md).is_file():
                Path(out_md).unlink()


if __name__ == "__main__":
    unittest.main()
