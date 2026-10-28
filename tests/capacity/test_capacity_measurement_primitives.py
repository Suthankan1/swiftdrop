"""Percentiles, counters, SSE framing, target safety, secret scrubbing and redaction detectors."""

import base64
import unittest

from tests.capacity.metrics import WorkloadStats, linear_slope_per_min, metric_sum, parse_prometheus_text, percentile
from tests.capacity.safety import (UnsafeTargetError, assert_safe_environment_id, assert_safe_target, find_secret_leaks,
                                   public_sse_event_violations, public_tracking_violations, scrub)
from tests.capacity.transport import SseParser


class MetricsTest(unittest.TestCase):
    def test_nearest_rank_percentiles(self):
        samples = list(range(1, 101))
        self.assertEqual(percentile(samples, 50), 50)
        self.assertEqual(percentile(samples, 95), 95)
        self.assertEqual(percentile(samples, 99), 99)
        self.assertEqual(percentile([7.0], 99), 7.0)
        self.assertIsNone(percentile([], 95), "no samples must not become a fabricated zero")

    def test_rate_limited_responses_are_separated_from_errors(self):
        stats = WorkloadStats("w")
        stats.record(True, 10)
        stats.record(False, 1, "rate_limited")
        stats.record(False, 30, "http_500")
        stats.record(False, 2000, "timeout")
        out = stats.as_dict()
        self.assertEqual(out["total_requests"], 4)
        self.assertEqual(out["rate_limited"], 1)
        self.assertEqual(out["timeouts"], 1)
        self.assertAlmostEqual(out["error_rate"], 2 / 3, places=5)
        self.assertEqual(out["error_rate_including_rate_limited"], 0.75)
        self.assertEqual(out["latency"]["count"], 3)

    def test_backlog_slope_and_prometheus_parsing(self):
        self.assertEqual(linear_slope_per_min([(0, 0), (30, 5), (60, 10)]), 10.0)
        self.assertIsNone(linear_slope_per_min([(0, 1)]))
        values = parse_prometheus_text('# HELP x\nswiftdrop_grpc_client_requests_total{service="a"} 3\n'
                                       'swiftdrop_grpc_client_requests_total{service="b"} 4.0\nswiftdrop_outbox_pending 7\n')
        self.assertEqual(metric_sum(values, "swiftdrop_grpc_client_requests_total"), 7.0)
        self.assertEqual(metric_sum(values, "swiftdrop_outbox_pending"), 7.0)
        self.assertIsNone(metric_sum(values, "swiftdrop_missing"))


class SseParserTest(unittest.TestCase):
    def test_parses_spring_framing_across_chunk_boundaries(self):
        parser = SseParser()
        frames = parser.feed(":keepalive\n\nid:e1\nevent:parcel.status.changed\nda")
        self.assertEqual([f.comment for f in frames], [True])
        frames = parser.feed('ta:{"a":1}\n\nid: e2\r\nevent: parcel.status.changed\r\ndata: x\r\ndata: y\r\n\r\n')
        self.assertEqual([(f.id, f.event, f.data) for f in frames],
                         [("e1", "parcel.status.changed", '{"a":1}'), ("e2", "parcel.status.changed", "x\ny")])


class TargetSafetyTest(unittest.TestCase):
    def test_loopback_and_local_hosts_are_allowed(self):
        for url in ("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080",
                    "https://swiftdrop.localhost", "http://api.swiftdrop.local"):
            assert_safe_target(url)

    def test_production_third_party_and_unlisted_hosts_are_refused(self):
        for url in ("https://api.prod.swiftdrop.example", "https://live-swiftdrop.example", "https://www.googleapis.com",
                    "https://staging.example.com", "ftp://localhost"):
            with self.assertRaises(UnsafeTargetError, msg=url):
                assert_safe_target(url)
        with self.assertRaises(UnsafeTargetError):
            assert_safe_target("https://staging.example.com", ["staging.example.com"], confirmed_non_production=False)
        assert_safe_target("https://staging.example.com", ["staging.example.com"], confirmed_non_production=True)
        with self.assertRaises(UnsafeTargetError):
            assert_safe_environment_id("swiftdrop-production")

    def test_secret_scrubbing(self):
        # Built at runtime so no JWT-shaped literal is committed (keeps secret scanners quiet).
        part = lambda raw: base64.urlsafe_b64encode(raw.encode()).decode().rstrip("=")
        jwt = ".".join([part('{"alg":"RS256"}'), part('{"sub":"synthetic"}'), part("synthetic-signature")])
        text = "Authorization: Bearer %s token=opaque-secret-123" % jwt
        self.assertTrue(find_secret_leaks(text, ["opaque-secret-123"]))
        cleaned = scrub(text, ["opaque-secret-123"])
        self.assertEqual(find_secret_leaks(cleaned, ["opaque-secret-123"]), [])


class RedactionTest(unittest.TestCase):
    CLEAN = {"trackingNumber": "SWD-20261106-A1B2C3", "currentStatus": "IN_TRANSIT", "originCity": "Colombo",
             "destinationCity": "Gampaha", "timeline": [{"status": "CREATED", "occurredAt": "2026-11-06T08:00:00Z",
                                                         "cityOrZone": "Colombo"}]}

    def test_contract_payload_is_clean(self):
        self.assertEqual(public_tracking_violations(self.CLEAN), [])

    def test_detects_pii_identifiers_and_extra_fields(self):
        cases = [dict(self.CLEAN, recipientPhone="+94 77 123 4567"),
                 dict(self.CLEAN, originCity="someone@example.com"),
                 dict(self.CLEAN, destinationCity="0771234567"),
                 dict(self.CLEAN, parcelId="6f1d2c1e-6a51-4c39-9c0e-165165165165"),
                 dict(self.CLEAN, timeline=[{"status": "FAILED", "occurredAt": "x", "reason": "REFUSED"}])]
        for payload in cases:
            self.assertTrue(public_tracking_violations(payload), payload)
        self.assertTrue(public_tracking_violations(self.CLEAN, private_values=["Gampaha"]))

    def test_public_sse_event_allowlist(self):
        event = {"eventId": "6f1d2c1e-6a51-4c39-9c0e-165165165165", "trackingNumber": "SWD-20261106-A1B2C3",
                 "aggregateVersion": 3, "oldStatus": "PICKED_UP", "newStatus": "IN_TRANSIT",
                 "cityOrZone": "Colombo", "occurredAt": "2026-11-06T08:00:00Z"}
        self.assertEqual(public_sse_event_violations(event), [])
        self.assertTrue(public_sse_event_violations(dict(event, courierSub="courier-1")))


if __name__ == "__main__":
    unittest.main()
