#!/usr/bin/env python3
"""Static Phase B TOTP MFA configuration and security invariant regression tests."""

import copy
import json
from pathlib import Path
import unittest


IDENTITY_DIR = Path(__file__).resolve().parents[1]
REALM_FILE = IDENTITY_DIR / "realm-swiftdrop.json" if (IDENTITY_DIR / "realm-swiftdrop.json").exists() else IDENTITY_DIR / "realm-devarom.json"
PRIVILEGED_ROLES = {"MERCHANT", "COURIER", "DISPATCHER", "ADMIN"}

EXPECTED_OTP_POLICY = {
    "otpPolicyType": "totp",
    "otpPolicyAlgorithm": "HmacSHA1",
    "otpPolicyInitialCounter": 0,
    "otpPolicyDigits": 6,
    "otpPolicyLookAheadWindow": 1,
    "otpPolicyPeriod": 30,
    "otpPolicyCodeReusable": False,
}

EXPECTED_SUPPORTED_APPS = {
    "totpAppGoogleName",
    "totpAppMicrosoftAuthenticatorName",
    "totpAppFreeOTPName",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_totp_policy(realm):
    for key, expected_val in EXPECTED_OTP_POLICY.items():
        actual_val = realm.get(key)
        require(
            actual_val == expected_val,
            f"Realm OTP policy mismatch for '{key}': expected {expected_val!r}, got {actual_val!r}",
        )
    apps = set(realm.get("otpSupportedApplications") or [])
    require(
        EXPECTED_SUPPORTED_APPS.issubset(apps),
        f"Realm must support standard authenticator apps: {EXPECTED_SUPPORTED_APPS - apps} missing",
    )
    # Ensure code replay is strictly disallowed
    require(
        realm.get("otpPolicyCodeReusable") is False,
        "TOTP code reuse within the same time step must be strictly forbidden (otpPolicyCodeReusable=false)",
    )


class TotpConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.realm = json.loads(REALM_FILE.read_text(encoding="utf-8"))

    def test_committed_totp_policy_invariants(self):
        """Verify realm-devarom.json contains standard RFC 6238 TOTP policy."""
        validate_totp_policy(self.realm)

    def test_email_verification_preserved_in_realm(self):
        """Verify Phase A email verification is not regressed by TOTP configuration."""
        self.assertTrue(self.realm.get("verifyEmail"), "Email verification must remain enabled")
        self.assertTrue(self.realm.get("registrationAllowed"), "Public registration must remain enabled")
        self.assertEqual(self.realm.get("actionTokenGeneratedByUserLifespan"), 300)
        self.assertNotIn("requiredActions", self.realm, "Realm import must not overwrite native actions")

    def test_privileged_roles_cannot_be_self_assigned(self):
        """Privileged staff roles must not be in default roles."""
        roles = {r["name"]: r for r in self.realm["roles"]["realm"]}
        self.assertTrue(PRIVILEGED_ROLES.issubset(set(roles)))
        default_role_name = self.realm.get("defaultRole", {}).get("name", "default-roles-swiftdrop")
        default_role = roles[default_role_name]
        composites = default_role.get("composites", {}).get("realm", [])
        for priv in PRIVILEGED_ROLES:
            self.assertNotIn(priv, composites, f"Privileged role {priv} found in default role composites")
        self.assertIn("CUSTOMER", composites, "Default role must include CUSTOMER")

    def test_reusable_otp_code_is_rejected(self):
        """Negative test: enabling OTP code reuse must fail invariant check."""
        mutated = copy.deepcopy(self.realm)
        mutated["otpPolicyCodeReusable"] = True
        with self.assertRaises(ValueError) as ctx:
            validate_totp_policy(mutated)
        self.assertIn("otpPolicyCodeReusable", str(ctx.exception))

    def test_unsupported_otp_algorithm_is_rejected(self):
        """Negative test: non-standard algorithm (e.g. MD5) is rejected."""
        mutated = copy.deepcopy(self.realm)
        mutated["otpPolicyAlgorithm"] = "HmacMD5"
        with self.assertRaises(ValueError) as ctx:
            validate_totp_policy(mutated)
        self.assertIn("otpPolicyAlgorithm", str(ctx.exception))

    def test_non_standard_totp_digits_is_rejected(self):
        """Negative test: 8 digits or 4 digits rejected for standard app compatibility."""
        mutated = copy.deepcopy(self.realm)
        mutated["otpPolicyDigits"] = 8
        with self.assertRaises(ValueError) as ctx:
            validate_totp_policy(mutated)
        self.assertIn("otpPolicyDigits", str(ctx.exception))

    def test_non_standard_totp_period_is_rejected(self):
        """Negative test: periods other than 30s rejected."""
        mutated = copy.deepcopy(self.realm)
        mutated["otpPolicyPeriod"] = 60
        with self.assertRaises(ValueError) as ctx:
            validate_totp_policy(mutated)
        self.assertIn("otpPolicyPeriod", str(ctx.exception))

    def test_hotp_type_is_rejected(self):
        """Negative test: counter-based HOTP is rejected in favor of time-based TOTP."""
        mutated = copy.deepcopy(self.realm)
        mutated["otpPolicyType"] = "hotp"
        with self.assertRaises(ValueError) as ctx:
            validate_totp_policy(mutated)
        self.assertIn("otpPolicyType", str(ctx.exception))

    def test_zero_secrets_or_otp_seeds_in_git(self):
        """Ensure no OTP seeds, secrets, or passwords are committed."""
        realm_text = REALM_FILE.read_text(encoding="utf-8")
        self.assertNotIn("totpSecret", realm_text)
        self.assertNotIn("otpSecret", realm_text)
        self.assertNotIn("secretKey", realm_text)


if __name__ == "__main__":
    unittest.main()
