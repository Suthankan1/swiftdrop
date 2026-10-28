"""
Harness self-validation against the SIMULATED stub, including fault injection.

These tests prove the measuring machinery detects what it claims to detect. They are not
capacity evidence for SwiftDrop.
"""

import json
import tempfile
import unittest
from pathlib import Path

from tests.capacity import run_capacity_budgets as cli
from tests.capacity.fixtures import build_dataset
from tests.capacity.stub_server import Faults, StubSwiftDrop
from tests.capacity.workloads import CapacityRunner, SecurityLedger, Targets

DATASET = build_dataset()


def _runner(stub):
    base = stub.base_url
    return CapacityRunner(DATASET, stub.directory(), Targets(base + "/graphql", base, base, base + "/metrics/graphql",
                                                             base + "/metrics/delivery", 5.0), SecurityLedger())


class CleanStubTest(unittest.TestCase):
    def test_dashboards_tracking_and_probes_are_clean(self):
        with StubSwiftDrop(DATASET) as stub:
            runner = _runner(stub)
            for root, actors in (("customerDashboard", ["customer-01", "customer-02", "customer-05"]),
                                 ("merchantDashboard", DATASET.merchant_tenants()),
                                 ("dispatcherDashboard", ["dispatcher-cmb01", "dispatcher-gmp01", "admin-01"])):
                out = runner.run_dashboard(root, actors, clients=6, iterations=4, page_sizes=(1, 20, 50))
                self.assertEqual(out["failed_requests"], 0, root)
                self.assertEqual(out["total_requests"], 24)
                self.assertIsNotNone(out["latency"]["p95_ms"])
            tracking = runner.run_public_tracking(clients=4, iterations=5)
            self.assertEqual(tracking["successful_requests"], 20)
            probes = runner.run_security_probes()
            self.assertTrue(probes and all(p["result"] == "PASS" for p in probes), probes)
            grpc = runner.measure_grpc_calls_per_query(samples=3)
            self.assertEqual(grpc["calls_per_query"], {"customerDashboard": 1.0, "merchantDashboard": 2.0,
                                                       "dispatcherDashboard": 5.0})
            self.assertEqual(grpc["n_plus_one_detected"], 0)
            self.assertEqual(runner.max_accepted_page_size(), 50)
            ledger = runner.ledger.as_dict()
            self.assertEqual((ledger["isolation_violations"], ledger["pii_violations"], ledger["negative_probe_failures"]), (0, 0, 0))

    def test_twenty_authorized_sse_connections_with_reconnect_and_reconciliation(self):
        with StubSwiftDrop(DATASET, Faults(heartbeat_s=0.5)) as stub:
            runner = _runner(stub)
            out = runner.run_sse(20, duration_s=5.0, disconnect_every=4, disconnect_at_s=1.5, driver_interval_s=0.5)
        self.assertEqual(out["peak_concurrent"], 20)
        self.assertEqual(out["connected_subscribers"], 20)
        self.assertEqual(out["disconnects"], 5)
        self.assertEqual(out["recoveries"], 5)
        self.assertEqual(out["recovery"]["count"], 5)
        self.assertGreater(out["events_received"], 0)
        self.assertGreater(out["status_drivers"]["transitions_ok"], 0)
        self.assertGreater(out["event_delivery_from_command_start"]["count"], 0)
        self.assertEqual(out["reconciliation_mismatches"], 0)
        self.assertEqual(out["out_of_order_events"], 0)
        self.assertEqual(out["duplicate_events"], 0)
        self.assertEqual(runner.ledger.sse_leak_violations, 0)
        self.assertLess(out["max_frame_gap_s"], 2.0)
        self.assertEqual((out["public_streams"]["planned"], out["public_streams"]["connected"]), (3, 3))
        self.assertGreater(out["public_streams"]["events_received"], 0)
        self.assertEqual(runner.ledger.pii_violations, 0)

    def test_public_stream_pii_leak_is_detected(self):
        with StubSwiftDrop(DATASET, Faults(leak_pii=True, heartbeat_s=0.5)) as stub:
            runner = _runner(stub)
            out = runner.run_sse(20, duration_s=4.0, disconnect_every=0, driver_interval_s=0.5)
        self.assertGreater(out["public_streams"]["events_received"], 0)
        self.assertGreater(runner.ledger.pii_violations, 0)
        self.assertTrue(any("public SSE" in f for f in runner.ledger.findings))


class FaultDetectionTest(unittest.TestCase):
    def test_cross_tenant_leak_is_detected(self):
        with StubSwiftDrop(DATASET, Faults(leak_cross_tenant=True)) as stub:
            runner = _runner(stub)
            runner.run_dashboard("customerDashboard", ["customer-01", "customer-02"], clients=2, iterations=2)
        self.assertEqual(runner.ledger.isolation_violations, 4)

    def test_public_tracking_pii_is_detected(self):
        with StubSwiftDrop(DATASET, Faults(leak_pii=True)) as stub:
            runner = _runner(stub)
            out = runner.run_public_tracking(clients=1, iterations=3)
        self.assertEqual(out["failed_requests"], 3)
        self.assertGreaterEqual(runner.ledger.pii_violations, 3)

    def test_n_plus_one_fan_out_is_detected(self):
        with StubSwiftDrop(DATASET, Faults(n_plus_one=True)) as stub:
            grpc = _runner(stub).measure_grpc_calls_per_query(samples=2)
        self.assertEqual(grpc["n_plus_one_detected"], 1)
        self.assertGreater(grpc["dispatcher_calls_page_size_50"], grpc["dispatcher_calls_page_size_1"])

    def test_oversized_page_acceptance_is_detected(self):
        with StubSwiftDrop(DATASET, Faults(accept_oversized_page=True)) as stub:
            runner = _runner(stub)
            runner.run_security_probes()
            self.assertEqual(runner.max_accepted_page_size(), 51)
        failed = [p["id"] for p in runner.ledger.probes if p["result"] == "FAIL"]
        self.assertEqual(failed, ["graphql.dispatcher_pagination_size_51"])

    def test_dropped_sse_events_surface_as_reconciliation_mismatch(self):
        with StubSwiftDrop(DATASET, Faults(drop_sse_events=True, heartbeat_s=0.5)) as stub:
            out = _runner(stub).run_sse(20, duration_s=4.0, disconnect_every=0, driver_interval_s=0.5)
        self.assertEqual(out["events_received"], 0)
        self.assertGreater(out["reconciliation_mismatches"], 0)

    def test_cross_stream_event_leak_is_detected(self):
        with StubSwiftDrop(DATASET, Faults(leak_sse_events=True, heartbeat_s=0.5)) as stub:
            runner = _runner(stub)
            runner.run_sse(20, duration_s=4.0, disconnect_every=0, driver_interval_s=0.5)
        self.assertGreater(runner.ledger.sse_leak_violations, 0)

    def test_server_errors_and_rate_limits_are_counted_separately(self):
        with StubSwiftDrop(DATASET, Faults(error_every=5)) as stub:
            out = _runner(stub).run_dashboard("merchantDashboard", DATASET.merchant_tenants(), clients=2, iterations=10)
        self.assertEqual(out["failure_kinds"].get("http_500"), 4)
        self.assertEqual(out["error_rate"], 0.2)
        with StubSwiftDrop(DATASET, Faults(tracking_burst=5)) as stub:
            probe = _runner(stub).run_rate_limit_probe(12)
        self.assertEqual((probe["ok"], probe["rate_limited"], probe["first_rate_limited_index"]), (5, 7, 5))
        self.assertEqual(probe["retry_after_header_on_429"], 7)


class CliSafetyTest(unittest.TestCase):
    def test_acceptance_requires_exact_revision_resource_and_recovery_inputs(self):
        args = cli.build_parser().parse_args(["--mode", "acceptance", "--sse-disconnect-every", "0"])
        blockers = cli.acceptance_input_blockers(args)
        self.assertTrue(any("--target-revision" in item for item in blockers))
        self.assertTrue(any("--resource-file" in item for item in blockers))
        self.assertTrue(any("disconnect" in item for item in blockers))

        measurements = {"workloads": {"sse_authorized": {
            "status_drivers": {"configured": 1, "transitions_ok": 0},
            "events_received": 0, "disconnects": 1, "recoveries": 0}}}
        evidence = cli.acceptance_measurement_blockers(measurements)
        self.assertTrue(any("status transition" in item for item in evidence))
        self.assertTrue(any("status event" in item for item in evidence))
        self.assertTrue(any("recovery" in item for item in evidence))

    def test_acceptance_mode_refuses_before_sending_load(self):
        self.assertEqual(cli.main(["--mode", "acceptance", "--environment-id", "k3d-swiftdrop-local",
                                   "--api-base-url", "http://127.0.0.1:9", "--binding", "missing.json"]), cli.EXIT_BLOCKED)

    def test_live_mode_refuses_production_targets_and_missing_binding(self):
        self.assertEqual(cli.main(["--mode", "measure", "--environment-id", "k3d-swiftdrop-local",
                                   "--api-base-url", "https://api.prod.swiftdrop.example", "--binding", "x.json"]), cli.EXIT_BLOCKED)
        self.assertEqual(cli.main(["--mode", "measure", "--environment-id", "k3d-swiftdrop-local",
                                   "--api-base-url", "http://127.0.0.1:9"]), cli.EXIT_BLOCKED)

    def test_missing_binding_and_unreachable_environment_are_blocked(self):
        self.assertEqual(cli.main(["--mode", "measure", "--environment-id", "k3d-swiftdrop-local",
                                   "--api-base-url", "http://127.0.0.1:9", "--binding", "does-not-exist.json"]), cli.EXIT_BLOCKED)
        with tempfile.TemporaryDirectory() as td:
            token = Path(td) / "token.txt"
            token.write_text("synthetic-test-token", encoding="utf-8")
            binding = Path(td) / "binding.json"
            binding.write_text(json.dumps({
                "environment_id": "k3d-swiftdrop-local", "fixture_digest": DATASET.fixture_digest,
                "actors": {a: {"token_file": str(token)} for a in DATASET.actors},
                "parcels": {p.alias: {"parcel_id": p.parcel_id, "tracking_number": p.tracking_number} for p in DATASET.parcels}}),
                encoding="utf-8")
            self.assertEqual(cli.main(["--mode", "measure", "--environment-id", "k3d-swiftdrop-local", "--timeout", "1",
                                       "--api-base-url", "http://127.0.0.1:9", "--binding", str(binding),
                                       "--report-dir", td]), cli.EXIT_BLOCKED)
            self.assertFalse(list(Path(td).glob("capacity-report-*.json")), "no report may be produced without a run")

    def test_fewer_than_twenty_sse_connections_is_blocked(self):
        self.assertEqual(cli.main(["--mode", "self-check", "--sse-connections", "19"]), cli.EXIT_BLOCKED)

    def test_self_check_report_is_labelled_simulated_and_secret_free(self):
        with tempfile.TemporaryDirectory() as td:
            code = cli.main(["--mode", "self-check", "--clients", "3", "--iterations", "2", "--sse-duration", "3",
                             "--sse-disconnect-at", "1", "--backlog-interval", "1", "--report-dir", td])
            self.assertEqual(code, cli.EXIT_OK)
            text = (Path(td) / "capacity-report-self-check.json").read_text(encoding="utf-8")
            report = json.loads(text)
            self.assertNotIn("stub.customer-01", text)
            self.assertTrue((Path(td) / "capacity-report-self-check.md").is_file())
        self.assertEqual(report["environment"]["evidence_class"], "SIMULATED")
        self.assertEqual(report["evaluation"]["overall"], "NOT_ACCEPTANCE_EVIDENCE")
        self.assertGreaterEqual(report["dataset"]["parcel_count"], 100)
        self.assertEqual(report["measurements"]["workloads"]["sse_authorized"]["peak_concurrent"], 20)
        self.assertTrue(report["provenance"]["harness_sha"])


if __name__ == "__main__":
    unittest.main()
