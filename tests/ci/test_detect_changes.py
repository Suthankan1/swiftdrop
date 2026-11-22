"""
Tests for SwiftDrop CI Change Impact Detection Utility
Verifies matching rules, fail-open semantics, and PR change filtering.
"""

import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parent.parent.parent / "platform" / "ci" / "detect_changes.py"
spec = importlib.util.spec_from_file_location("detect_changes", MODULE_PATH)
detect_changes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(detect_changes)

match_path = detect_changes.match_path
main = detect_changes.main


class TestDetectChanges(unittest.TestCase):
    def test_match_path_directory_prefix(self):
        patterns = ["services/accounts-service/**", "shared/**", "pom.xml"]
        self.assertTrue(match_path("services/accounts-service/pom.xml", patterns))
        self.assertTrue(match_path("services/accounts-service/src/main/java/Foo.java", patterns))
        self.assertTrue(match_path("shared/proto-contracts/pom.xml", patterns))
        self.assertTrue(match_path("pom.xml", patterns))

        self.assertFalse(match_path("services/pricing-service/pom.xml", patterns))
        self.assertFalse(match_path("frontend/src/App.tsx", patterns))
        self.assertFalse(match_path("platform/identity/realm.json", patterns))

    def test_match_path_fnmatch(self):
        patterns = ["*.md", "contracts/**/*.yaml"]
        self.assertTrue(match_path("README.md", patterns))
        self.assertTrue(match_path("contracts/rest/openapi-accounts.yaml", patterns))
        self.assertFalse(match_path("services/pom.xml", patterns))

    def test_non_pr_event_fails_open(self):
        with tempfile.NamedTemporaryFile(mode="w+", delete=False) as out:
            out_path = out.name

        try:
            old_out = os.environ.get("GITHUB_OUTPUT")
            os.environ["GITHUB_OUTPUT"] = out_path

            # Test push event
            test_args = ["detect_changes.py", "--event", "push", "--patterns", "services/accounts-service/**"]
            import sys
            orig_argv = sys.argv
            sys.argv = test_args
            try:
                ret = main()
                self.assertEqual(ret, 0)
            finally:
                sys.argv = orig_argv

            with open(out_path, "r", encoding="utf-8") as f:
                content = f.read()
            self.assertIn("changed=true", content)

        finally:
            if old_out:
                os.environ["GITHUB_OUTPUT"] = old_out
            else:
                os.environ.pop("GITHUB_OUTPUT", None)
            if os.path.exists(out_path):
                os.unlink(out_path)

    def test_pr_with_matching_and_unrelated_files(self):
        with tempfile.NamedTemporaryFile(mode="w+", delete=False) as out:
            out_path = out.name

        try:
            old_out = os.environ.get("GITHUB_OUTPUT")
            os.environ["GITHUB_OUTPUT"] = out_path

            import unittest.mock as mock
            with mock.patch.object(detect_changes, "get_changed_files", return_value=["services/accounts-service/src/Main.java"]):
                import sys
                orig_argv = sys.argv
                sys.argv = ["detect_changes.py", "--event", "pull_request", "--patterns", "services/accounts-service/**"]
                try:
                    ret = main()
                    self.assertEqual(ret, 0)
                finally:
                    sys.argv = orig_argv

            with open(out_path, "r", encoding="utf-8") as f:
                content = f.read()
            self.assertIn("changed=true", content)

            # Now test when unrelated file changed
            with open(out_path, "w", encoding="utf-8") as f:
                f.write("")

            with mock.patch.object(detect_changes, "get_changed_files", return_value=["frontend/src/App.tsx"]):
                orig_argv = sys.argv
                sys.argv = ["detect_changes.py", "--event", "pull_request", "--patterns", "services/accounts-service/**"]
                try:
                    ret = main()
                    self.assertEqual(ret, 0)
                finally:
                    sys.argv = orig_argv

            with open(out_path, "r", encoding="utf-8") as f:
                content = f.read()
            self.assertIn("changed=false", content)

            # Test git error fails open
            with open(out_path, "w", encoding="utf-8") as f:
                f.write("")

            with mock.patch.object(detect_changes, "get_changed_files", return_value=None):
                orig_argv = sys.argv
                sys.argv = ["detect_changes.py", "--event", "pull_request", "--patterns", "services/accounts-service/**"]
                try:
                    ret = main()
                    self.assertEqual(ret, 0)
                finally:
                    sys.argv = orig_argv

            with open(out_path, "r", encoding="utf-8") as f:
                content = f.read()
            self.assertIn("changed=true", content)

        finally:
            if old_out:
                os.environ["GITHUB_OUTPUT"] = old_out
            else:
                os.environ.pop("GITHUB_OUTPUT", None)
            if os.path.exists(out_path):
                os.unlink(out_path)


if __name__ == "__main__":
    unittest.main()
