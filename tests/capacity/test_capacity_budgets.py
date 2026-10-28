"""Budget policy tests: approved values are sourced, pending values never pass, approvals are digest-pinned."""

import copy
import json
import tempfile
import unittest
from pathlib import Path

from tests.capacity import budgets as b
from tests.capacity.run_capacity_budgets import static_graphql_limits


def _approved_copy(raw):
    doc = copy.deepcopy(raw)
    for item in doc["budgets"].values():
        if item["status"] == b.PENDING:
            item["status"], item["value"] = b.APPROVED, item["illustrative_value"]
    doc["approval"].update(status=b.APPROVED, approved_by=["reviewer"], approved_budget_digest=b.budget_digest(doc))
    return doc


def _load(doc):
    with tempfile.TemporaryDirectory() as td:
        path = Path(td) / "budgets.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        return b.load_budgets(path)


class ShippedBudgetFileTest(unittest.TestCase):
    def setUp(self):
        self.budgets = b.load_budgets()

    def test_approved_values_match_documented_sources(self):
        expected = {"graphql.max_depth": 5, "graphql.max_complexity": 100, "graphql.max_page_size": 50,
                    "grpc.deadline_ms": 2000, "sse.concurrent_connections_min": 20,
                    "dataset.parcels_min": 100, "dataset.tenants_min": 2}
        approved = {k: v["value"] for k, v in self.budgets.budgets.items() if v["status"] == b.APPROVED}
        self.assertEqual(approved, expected)

    def test_release_thresholds_are_pending_without_authoritative_values(self):
        for budget_id in ("dashboard.customer.latency_p95_ms", "dashboard.merchant.latency_p95_ms",
                          "dashboard.dispatcher.latency_p95_ms", "tracking.public.latency_p95_ms",
                          "requests.error_rate_max", "requests.max_duration_ms", "dashboard.concurrent_clients_min",
                          "sse.reconnect_recovery_p95_ms", "backlog.outbox_pending_growth_per_min_max"):
            self.assertEqual(self.budgets.budgets[budget_id]["status"], b.PENDING, budget_id)
            self.assertIsNone(self.budgets.budgets[budget_id]["value"], budget_id)

    def test_shipped_file_blocks_acceptance(self):
        blockers = self.budgets.acceptance_blockers()
        self.assertTrue(any("approval.status" in x for x in blockers))
        self.assertTrue(any("dashboard.customer.latency_p95_ms" in x for x in blockers))

    def test_configured_service_limits_match_approved_budgets(self):
        limits = static_graphql_limits()
        self.assertEqual(limits["configured_max_depth"], self.budgets.approved_value("graphql.max_depth"))
        self.assertEqual(limits["configured_max_complexity"], self.budgets.approved_value("graphql.max_complexity"))
        self.assertEqual(limits["configured_max_page_size"], self.budgets.approved_value("graphql.max_page_size"))
        self.assertEqual(limits["client_deadline_ms"], self.budgets.approved_value("grpc.deadline_ms"))


class EvaluationPolicyTest(unittest.TestCase):
    def setUp(self):
        self.shipped = b.load_budgets()

    def _measurements(self, p95=10.0, leaks=0):
        return {"workloads": {"dashboard_customer": {"latency": {"p95_ms": p95}}},
                "security": {"isolation_violations": leaks}}

    def test_pending_budget_is_never_reported_as_pass(self):
        result = b.evaluate(self.shipped, self._measurements(p95=1.0), evidence_is_live=True, acceptance_mode=False)
        verdict = next(v for v in result.verdicts if v.budget_id == "dashboard.customer.latency_p95_ms")
        self.assertEqual(verdict.verdict, b.PENDING_VERDICT)
        self.assertEqual(result.overall, b.OVERALL_BLOCKED_APPROVAL)

    def test_invariant_violation_fails_even_when_budgets_pending(self):
        result = b.evaluate(self.shipped, self._measurements(leaks=1), evidence_is_live=True, acceptance_mode=False)
        self.assertEqual(result.overall, b.OVERALL_FAIL)
        self.assertTrue(any("isolation.cross_tenant_records" in r for r in result.reasons))

    def test_simulated_evidence_is_never_acceptance(self):
        result = b.evaluate(self.shipped, self._measurements(), evidence_is_live=False, acceptance_mode=True)
        self.assertEqual(result.overall, b.OVERALL_NOT_ACCEPTANCE)

    def test_unmeasured_metric_is_not_a_pass(self):
        result = b.evaluate(self.shipped, {}, evidence_is_live=True, acceptance_mode=False)
        self.assertTrue(all(v.verdict == b.NOT_MEASURED for v in result.verdicts))

    def test_fully_approved_and_measured_run_can_pass_and_fail(self):
        doc = _approved_copy(self.shipped.raw)
        approved = _load(doc)
        self.assertEqual(approved.acceptance_blockers(), [])
        measurements = {}
        for item in doc["budgets"].values():
            node = measurements
            parts = item["metric"].split(".")
            for part in parts[:-1]:
                node = node.setdefault(part, {})
            node[parts[-1]] = item["value"]
        for item in doc["invariants"].values():
            node = measurements
            parts = item["metric"].split(".")
            for part in parts[:-1]:
                node = node.setdefault(part, {})
            node[parts[-1]] = 0
        self.assertEqual(b.evaluate(approved, measurements, True, True).overall, b.OVERALL_PASS)
        measurements["workloads"]["dashboard_customer"]["latency"]["p95_ms"] += 1
        self.assertEqual(b.evaluate(approved, measurements, True, True).overall, b.OVERALL_FAIL)

    def test_editing_threshold_after_approval_blocks_acceptance(self):
        doc = _approved_copy(self.shipped.raw)
        doc["budgets"]["dashboard.customer.latency_p95_ms"]["value"] += 500
        blockers = _load(doc).acceptance_blockers()
        self.assertTrue(any("digest" in x for x in blockers))

    def test_document_validation_rejects_policy_violations(self):
        bad = copy.deepcopy(self.shipped.raw)
        bad["budgets"]["requests.error_rate_max"]["value"] = 0.5
        with self.assertRaises(b.BudgetError):
            b.validate_budget_document(bad)
        bad = copy.deepcopy(self.shipped.raw)
        bad["budgets"]["grpc.deadline_ms"]["value"] = None
        with self.assertRaises(b.BudgetError):
            b.validate_budget_document(bad)
        bad = copy.deepcopy(self.shipped.raw)
        bad["approval"]["status"] = b.APPROVED
        with self.assertRaises(b.BudgetError):
            b.validate_budget_document(bad)


if __name__ == "__main__":
    unittest.main()
