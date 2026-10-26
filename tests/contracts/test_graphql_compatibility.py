"""The #119 retirement must never become a general breaking-change bypass."""
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from tests.contracts import validate_contracts as validator


BASE = """
type Query {
  publicTracking(trackingNumber: String!): PublicTrackingView
  customerDashboard: CustomerDashboard!
}
type CustomerDashboard { userId: ID! }
type PublicTrackingView { currentStatus: Status }
enum Status { CREATED DELIVERED }
"""


class GraphqlCompatibilityTest(unittest.TestCase):
    def check_diff(self, head, base=BASE):
        with tempfile.TemporaryDirectory() as directory:
            contracts = Path(directory)
            (contracts / "graphql").mkdir()
            (contracts / "graphql/schema.graphql").write_text(head, encoding="utf-8")
            output = io.StringIO()
            with patch.object(validator, "CONTRACTS_DIR", contracts), patch.object(
                validator, "get_git_file_content", return_value=base
            ), redirect_stdout(output):
                errors = validator.check_graphql_compatibility("fixture-base")
            return errors, output.getvalue()

    def test_exact_retirement_is_reported_and_accepted(self):
        errors, output = self.check_diff(BASE.replace("publicTracking(trackingNumber: String!): PublicTrackingView", ""))
        self.assertEqual(errors, [])
        self.assertIn("ACCEPTED REMOVAL (#119)", output)

    def test_other_query_removal_still_fails(self):
        errors, _ = self.check_diff(BASE.replace("customerDashboard: CustomerDashboard!", ""))
        self.assertTrue(any("customerDashboard" in error for error in errors))

    def test_other_object_field_removal_still_fails(self):
        errors, _ = self.check_diff(BASE.replace("userId: ID!", "otherId: ID!"))
        self.assertTrue(any("CustomerDashboard.userId" in error for error in errors))

    def test_enum_removal_still_fails(self):
        errors, _ = self.check_diff(BASE.replace("CREATED DELIVERED", "CREATED"))
        self.assertTrue(any("Status.DELIVERED" in error for error in errors))

    def test_retirement_does_not_hide_another_breaking_change(self):
        head = BASE.replace("publicTracking(trackingNumber: String!): PublicTrackingView", "").replace("CREATED DELIVERED", "CREATED")
        errors, output = self.check_diff(head)
        self.assertIn("ACCEPTED REMOVAL (#119)", output)
        self.assertTrue(any("Status.DELIVERED" in error for error in errors))

    def test_changed_or_restored_signature_is_not_exempt(self):
        for original, modified in [("String!", "ID!"), (": PublicTrackingView", ": PublicTrackingView!"),
                                   ("trackingNumber: String!", "trackingNumber: String!, tenant: ID")]:
            base = BASE.replace(original, modified)
            head = base.replace(next(line for line in base.splitlines() if "publicTracking(" in line), "")
            errors, output = self.check_diff(head, base)
            self.assertTrue(any("publicTracking" in error for error in errors))
            self.assertNotIn("ACCEPTED REMOVAL", output)


if __name__ == "__main__":
    unittest.main()
