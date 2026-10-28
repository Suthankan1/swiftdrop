"""Deterministic multi-tenant dataset, SSE subscriber planning and binding validation tests."""

import json
import re
import tempfile
import unittest
from pathlib import Path

from tests.capacity.fixtures import (STATUSES, SSE_MAX_PER_PARCEL, SSE_MAX_PER_USER, BindingError, build_dataset,
                                     load_binding, plan_sse_subscribers)
from tests.capacity.safety import EMAIL_PATTERN, PHONE_PATTERN

DELIVERY_TRACKING_PATTERN = re.compile(r"^SWD-[0-9]{8}-[A-Z0-9]{4,8}$")


def _strings(node):
    if isinstance(node, dict):
        for v in node.values():
            yield from _strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from _strings(v)
    elif isinstance(node, str):
        yield node


class DatasetTest(unittest.TestCase):
    def setUp(self):
        self.ds = build_dataset()

    def test_meets_issue_minimums(self):
        counts = self.ds.counts()
        self.assertGreaterEqual(counts["parcel_count"], 100)
        self.assertGreaterEqual(counts["merchant_tenant_count"], 2)
        self.assertGreaterEqual(counts["customer_count"], 2)
        self.assertGreaterEqual(counts["dispatcher_count"], 2)
        for status in STATUSES:
            self.assertGreater(counts["status_" + status.lower()], 0, status)
        with self.assertRaises(ValueError):
            build_dataset(99)

    def test_is_deterministic(self):
        again = build_dataset()
        self.assertEqual(self.ds.fixture_digest, again.fixture_digest)
        self.assertEqual([p.tracking_number for p in self.ds.parcels], [p.tracking_number for p in again.parcels])
        self.assertNotEqual(self.ds.fixture_digest, build_dataset(seed="other").fixture_digest)

    def test_identifiers_are_unique_and_contract_shaped(self):
        tracking = [p.tracking_number for p in self.ds.parcels]
        self.assertEqual(len(set(tracking)), len(tracking))
        self.assertEqual(len({p.parcel_id for p in self.ds.parcels}), len(self.ds.parcels))
        self.assertTrue(all(DELIVERY_TRACKING_PATTERN.match(t) for t in tracking))

    def test_tenant_visibility_is_disjoint(self):
        tenants = self.ds.merchant_tenants()
        seen = {}
        for tenant in tenants:
            for parcel in self.ds.visible_to(tenant):
                self.assertNotIn(parcel.alias, seen, "parcel visible to %s and %s" % (seen.get(parcel.alias), tenant))
                seen[parcel.alias] = tenant
        self.assertEqual(self.ds.visible_to("merchant-pending"), [])

    def test_boundary_and_pagination_cases(self):
        self.assertEqual(self.ds.visible_to("customer-05"), [], "empty-result customer")
        dispatcher = self.ds.visible_to("dispatcher-cmb01")
        self.assertTrue(dispatcher and all("CMB-01" in (p.pickup_zone, p.dropoff_zone) for p in dispatcher))
        self.assertGreaterEqual(len(self.ds.visible_to("admin-01")), len(dispatcher))
        self.assertEqual(self.ds.expected_pages("admin-01", 1), len(self.ds.unassigned()))

    def test_contains_no_personal_data(self):
        manifest = self.ds.manifest()
        for value in _strings(manifest):
            self.assertIsNone(EMAIL_PATTERN.search(value), value)
            self.assertIsNone(PHONE_PATTERN.search(value), value)
        for actor in self.ds.actors.values():
            self.assertTrue(actor.subject.startswith("capacity-"))


class SsePlanTest(unittest.TestCase):
    def test_twenty_connections_respect_server_caps(self):
        ds = build_dataset()
        plan = plan_sse_subscribers(ds, 20)
        self.assertEqual(len(plan), 20)
        per_user, per_parcel = {}, {}
        for actor, parcel in plan:
            self.assertTrue(ds.can_stream(actor, parcel))
            per_user[actor] = per_user.get(actor, 0) + 1
            per_parcel[parcel.alias] = per_parcel.get(parcel.alias, 0) + 1
        self.assertLessEqual(max(per_user.values()), SSE_MAX_PER_USER)
        self.assertLessEqual(max(per_parcel.values()), SSE_MAX_PER_PARCEL)
        self.assertGreaterEqual(len(per_user), 2, "subscribers must span several identities/tenants")
        self.assertTrue(any(a.startswith("merchant") for a in per_user) and any(a.startswith("customer") for a in per_user))

    def test_impossible_plan_fails_closed(self):
        with self.assertRaises(ValueError):
            plan_sse_subscribers(build_dataset(), 500, max_per_user=1, max_per_parcel=1)


class BindingTest(unittest.TestCase):
    def setUp(self):
        self.ds = build_dataset()
        self.td = tempfile.TemporaryDirectory()
        self.dir = Path(self.td.name)
        token = self.dir / "token.txt"
        token.write_text("synthetic-test-token", encoding="utf-8")
        self.doc = {"environment_id": "k3d-swiftdrop-local", "fixture_digest": self.ds.fixture_digest,
                    "actors": {a: {"token_file": str(token)} for a in self.ds.actors},
                    "parcels": {p.alias: {"parcel_id": p.parcel_id, "tracking_number": p.tracking_number}
                                for p in self.ds.parcels}}

    def tearDown(self):
        self.td.cleanup()

    def _write(self, doc):
        path = self.dir / "binding.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        return path

    def test_valid_binding_loads_and_reads_tokens_lazily(self):
        binding = load_binding(self._write(self.doc), self.ds)
        self.assertEqual(binding.token("customer-01"), "synthetic-test-token")
        self.assertEqual(len(binding.parcels), len(self.ds.parcels))

    def test_status_driver_must_be_an_assigned_courier_with_a_token(self):
        fixture = next(p for p in self.ds.parcels if p.status in ("PICKED_UP", "IN_TRANSIT"))
        valid = dict(self.doc, status_drivers=[{"parcel": fixture.alias, "actor": fixture.courier_alias}])
        binding = load_binding(self._write(valid), self.ds)
        self.assertEqual(binding.status_drivers, valid["status_drivers"])

        invalid = dict(self.doc, status_drivers=[{"parcel": fixture.alias, "actor": "customer-01"}])
        with self.assertRaises(BindingError):
            load_binding(self._write(invalid), self.ds)

        missing_token = dict(valid, actors={k: v for k, v in self.doc["actors"].items()
                                            if k != fixture.courier_alias})
        with self.assertRaises(BindingError):
            load_binding(self._write(missing_token), self.ds)

    def test_rejects_digest_mismatch_and_missing_parcels(self):
        bad = dict(self.doc, fixture_digest="0" * 64)
        with self.assertRaises(BindingError):
            load_binding(self._write(bad), self.ds)
        bad = dict(self.doc, parcels=dict(list(self.doc["parcels"].items())[:99]))
        with self.assertRaises(BindingError):
            load_binding(self._write(bad), self.ds)

    def test_rejects_token_files_inside_tests_tree(self):
        inside = Path(__file__).resolve().parent / "token.txt"
        bad = dict(self.doc, actors={a: {"token_file": str(inside)} for a in self.ds.actors})
        with self.assertRaises(BindingError):
            load_binding(self._write(bad), self.ds)


if __name__ == "__main__":
    unittest.main()
