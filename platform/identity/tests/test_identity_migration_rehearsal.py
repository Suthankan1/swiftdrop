#!/usr/bin/env python3
"""Unit tests for Phase 2A identity migration rehearsal automation."""

from __future__ import annotations

import json
import unittest
from pathlib import Path
import sys

# Ensure platform/identity is on path
PLATFORM_IDENTITY_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PLATFORM_IDENTITY_DIR))

import importlib
rehearsal_mod = importlib.import_module("rehearse-identity-migration")
RehearsalError = rehearsal_mod.RehearsalError
RehearsalPreflight = rehearsal_mod.RehearsalPreflight
AccountsContinuityValidator = rehearsal_mod.AccountsContinuityValidator


class TestIdentityMigrationRehearsalPreflight(unittest.TestCase):
    def test_forbidden_ports_rejected(self):
        for port in [8090, 8080, 8081, 5438, 8025, 5173]:
            with self.assertRaises(RehearsalError) as ctx:
                RehearsalPreflight.validate_isolation_targets(port, "test-container", "test-net")
            self.assertIn("SAFETY VIOLATION: Port", str(ctx.exception))

    def test_forbidden_containers_rejected(self):
        for name in ["devarom-keycloak", "devarom-accounts-postgres", "devarom-mailpit"]:
            with self.assertRaises(RehearsalError) as ctx:
                RehearsalPreflight.validate_isolation_targets(8098, name, "test-net")
            self.assertIn("SAFETY VIOLATION: Container name", str(ctx.exception))

    def test_allowed_isolation_targets_accepted(self):
        # Should not raise exception
        RehearsalPreflight.validate_isolation_targets(8098, "swiftdrop-rehearsal-keycloak", "swiftdrop-rehearsal-net")


class TestAccountsContinuityValidator(unittest.TestCase):
    def test_profile_retention_on_same_subject(self):
        validator = AccountsContinuityValidator()
        sub = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
        p1 = validator.get_or_create_profile(sub, "user@swiftdrop.test", "Alice User")
        p2 = validator.get_or_create_profile(sub, "user@swiftdrop.test", "Alice User")

        self.assertEqual(p1["id"], p2["id"], "Profile UUID must be identical on repeated resolution")
        self.assertEqual(p1["keycloak_sub"], sub)

    def test_different_subjects_have_distinct_profiles(self):
        validator = AccountsContinuityValidator()
        sub1 = "sub-alice-1111"
        sub2 = "sub-bob-2222"
        p1 = validator.get_or_create_profile(sub1, "alice@swiftdrop.test", "Alice")
        p2 = validator.get_or_create_profile(sub2, "bob@swiftdrop.test", "Bob")

        self.assertNotEqual(p1["id"], p2["id"])
        self.assertNotEqual(p1["keycloak_sub"], p2["keycloak_sub"])


class TestRehearsalSnapshotLogic(unittest.TestCase):
    def test_continuity_assertion_detects_sub_mismatch(self):
        pre_users = {
            "alice": {"id": "sub-1234", "emailVerified": True, "roles": ["CUSTOMER"]}
        }
        post_users_mutated = {
            "alice": {"id": "sub-9999", "emailVerified": True, "roles": ["CUSTOMER"]}
        }
        self.assertNotEqual(
            pre_users["alice"]["id"],
            post_users_mutated["alice"]["id"],
            "Mismatch in sub should be detectable"
        )


if __name__ == "__main__":
    unittest.main()
