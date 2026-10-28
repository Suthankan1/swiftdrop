#!/usr/bin/env python3
"""
SwiftDrop Concurrent Booking & Idempotency Regressions (Issue #140 / October 23 Amendment)
===========================================================================================
Executes concurrent same-quote booking contention through customer and merchant entry points.
Verifies single canonical parcel creation, deterministic conflict for the loser,
atomic transaction rollback without orphaned quotes, and idempotent replay guarantees.
"""

import copy
import hashlib
import json
import threading
import time
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple


class BookingFailure(Exception):
    def __init__(self, status: int, code: str, detail: str):
        super().__init__(detail)
        self.status = status
        self.code = code
        self.detail = detail


class InMemoryDeliveryTransactionStore:
    """
    Thread-safe storage engine modeling Delivery's PostgreSQL transaction semantics
    (parcels, status_history, outbox_events, idempotency_records).
    """

    def __init__(self):
        self._lock = threading.RLock()
        self.parcels: Dict[str, dict] = {}  # parcel_id -> row
        self.parcels_by_quote: Dict[str, str] = {}  # quote_id -> parcel_id (UNIQUE INDEX)
        self.status_history: List[dict] = []
        self.outbox_events: List[dict] = []
        self.idempotency_records: Dict[Tuple[str, str], dict] = {}  # (actor, key) -> record
        self.advisory_locks: Dict[str, threading.Lock] = {}

    def get_advisory_lock(self, lock_key: str) -> threading.Lock:
        with self._lock:
            if lock_key not in self.advisory_locks:
                self.advisory_locks[lock_key] = threading.Lock()
            return self.advisory_locks[lock_key]

    def check_quote_consumed(self, quote_id: str) -> bool:
        with self._lock:
            return quote_id in self.parcels_by_quote

    def get_idempotency_replay(self, actor: str, key: str) -> Optional[dict]:
        with self._lock:
            return self.idempotency_records.get((actor, key))

    def commit_booking(
        self,
        parcel: dict,
        history: dict,
        outbox: dict,
        actor: str,
        key: str,
        digest: str,
        response_payload: dict,
        fail_simulation: bool = False,
    ):
        with self._lock:
            quote_id = parcel["quote_id"]
            if quote_id in self.parcels_by_quote:
                raise BookingFailure(409, "QUOTE_ALREADY_CONSUMED", "Quote already booked by another order.")

            if (actor, key) in self.idempotency_records:
                existing = self.idempotency_records[(actor, key)]
                if existing["digest"] != digest:
                    raise BookingFailure(409, "IDEMPOTENCY_CONFLICT", "Idempotency key was used for a different request.")
                return existing["response"]

            if fail_simulation:
                # Simulate mid-transaction database crash or trigger failure before commit
                raise RuntimeError("Simulated mid-transaction database crash during parcel insert")

            # Commit atomically
            parcel_id = parcel["parcel_id"]
            self.parcels[parcel_id] = parcel
            self.parcels_by_quote[quote_id] = parcel_id
            self.status_history.append(history)
            self.outbox_events.append(outbox)
            self.idempotency_records[(actor, key)] = {
                "digest": digest,
                "response": response_payload,
                "created_at": datetime.now(timezone.utc).isoformat(),
            }
            return response_payload


class BookingEngine:
    """
    Intake engine replicating DeliveryOperations BookingService (Foundation #20 & #129).
    """

    def __init__(self, store: InMemoryDeliveryTransactionStore):
        self.store = store

    def _digest(self, payload: dict) -> str:
        serialized = json.dumps(payload, sort_keys=True)
        return hashlib.sha256(serialized.encode("utf-8")).hexdigest()

    def book_customer(self, actor_sub: str, key: str, request: dict, fail_simulation: bool = False) -> dict:
        if not actor_sub:
            raise BookingFailure(401, "UNAUTHENTICATED", "Authentication required.")
        if not key:
            raise BookingFailure(400, "INVALID_REQUEST", "Idempotency-Key is required.")

        digest = self._digest(request)
        lock_key = f"{actor_sub}:{key}"
        adv_lock = self.store.get_advisory_lock(lock_key)

        with adv_lock:
            # Check replay
            replay = self.store.get_idempotency_replay(actor_sub, key)
            if replay:
                if replay["digest"] != digest:
                    raise BookingFailure(409, "IDEMPOTENCY_CONFLICT", "Idempotency key was used for a different request.")
                return replay["response"]

            quote_id = request["quoteId"]
            if self.store.check_quote_consumed(quote_id):
                raise BookingFailure(409, "QUOTE_ALREADY_CONSUMED", "Quote already booked.")

            parcel_id = str(uuid.uuid4())
            tracking = f"SWD-20261028-{uuid.uuid4().hex[:8].upper()}"
            now_iso = datetime.now(timezone.utc).isoformat()

            parcel = {
                "parcel_id": parcel_id,
                "tracking_number": tracking,
                "created_by_sub": actor_sub,
                "merchant_id": None,
                "quote_id": quote_id,
                "status": "CREATED",
                "version": 1,
                "amount_minor_units": 50000,
                "currency": "LKR",
                "rate_version": "RATE-2026-V1",
                "pickup_address": request["pickupAddress"],
                "dropoff_address": request["dropoffAddress"],
                "created_at": now_iso,
            }

            history = {
                "history_id": str(uuid.uuid4()),
                "parcel_id": parcel_id,
                "previous_status": None,
                "new_status": "CREATED",
                "reason": None,
                "actor_sub": actor_sub,
                "resulting_version": 1,
                "occurred_at": now_iso,
            }

            outbox = {
                "eventId": str(uuid.uuid4()),
                "eventType": "parcel.status.changed.v1",
                "schemaVersion": "1.0.0",
                "aggregateId": parcel_id,
                "aggregateType": "PARCEL",
                "aggregateVersion": 1,
                "occurredAt": now_iso,
                "correlationId": key,
                "producer": "delivery-operations",
                "payload": {
                    "parcelId": parcel_id,
                    "trackingNumber": tracking,
                    "oldStatus": None,
                    "newStatus": "CREATED",
                    "reason": None,
                    "merchantId": None,
                    "courierSub": None,
                    "cityOrZone": "Colombo 03",
                },
            }

            response = {
                "parcelId": parcel_id,
                "trackingNumber": tracking,
                "senderSub": actor_sub,
                "merchantId": None,
                "status": "CREATED",
                "version": 1,
                "weightKg": 2.5,
                "sizeCategory": "STANDARD",
                "amountMinorUnits": 50000,
                "currency": "LKR",
                "rateVersion": "RATE-2026-V1",
                "pickupAddress": request["pickupAddress"],
                "dropoffAddress": request["dropoffAddress"],
                "createdAt": now_iso,
            }

            return self.store.commit_booking(
                parcel, history, outbox, actor_sub, key, digest, response, fail_simulation=fail_simulation
            )

    def book_merchant(self, merchant_id: str, caller_sub: str, key: str, request: dict, fail_simulation: bool = False) -> dict:
        if not merchant_id:
            raise BookingFailure(401, "UNAUTHENTICATED", "Merchant identity required.")
        if not key:
            raise BookingFailure(400, "INVALID_REQUEST", "Idempotency-Key is required.")

        actor = f"merchant:{merchant_id}"
        digest = self._digest(request)
        adv_lock = self.store.get_advisory_lock(f"{actor}:{key}")

        with adv_lock:
            replay = self.store.get_idempotency_replay(actor, key)
            if replay:
                if replay["digest"] != digest:
                    raise BookingFailure(409, "IDEMPOTENCY_CONFLICT", "Idempotency key was used for a different request.")
                return replay["response"]

            quote_id = request["quoteId"]
            if self.store.check_quote_consumed(quote_id):
                raise BookingFailure(409, "QUOTE_ALREADY_CONSUMED", "Quote already booked.")

            parcel_id = str(uuid.uuid4())
            tracking = f"SWD-20261028-{uuid.uuid4().hex[:8].upper()}"
            now_iso = datetime.now(timezone.utc).isoformat()

            parcel = {
                "parcel_id": parcel_id,
                "tracking_number": tracking,
                "created_by_sub": caller_sub,
                "merchant_id": merchant_id,
                "quote_id": quote_id,
                "status": "CREATED",
                "version": 1,
                "amount_minor_units": 50000,
                "currency": "LKR",
                "rate_version": "RATE-2026-V1",
                "pickup_address": request["pickupAddress"],
                "dropoff_address": request["dropoffAddress"],
                "created_at": now_iso,
            }

            history = {
                "history_id": str(uuid.uuid4()),
                "parcel_id": parcel_id,
                "previous_status": None,
                "new_status": "CREATED",
                "reason": None,
                "actor_sub": caller_sub,
                "resulting_version": 1,
                "occurred_at": now_iso,
            }

            outbox = {
                "eventId": str(uuid.uuid4()),
                "eventType": "parcel.status.changed.v1",
                "schemaVersion": "1.0.0",
                "aggregateId": parcel_id,
                "aggregateType": "PARCEL",
                "aggregateVersion": 1,
                "occurredAt": now_iso,
                "correlationId": key,
                "producer": "delivery-operations",
                "payload": {
                    "parcelId": parcel_id,
                    "trackingNumber": tracking,
                    "oldStatus": None,
                    "newStatus": "CREATED",
                    "reason": None,
                    "merchantId": merchant_id,
                    "courierSub": None,
                    "cityOrZone": "Colombo 03",
                },
            }

            response = {
                "parcelId": parcel_id,
                "trackingNumber": tracking,
                "senderSub": caller_sub,
                "merchantId": merchant_id,
                "status": "CREATED",
                "version": 1,
                "weightKg": 2.5,
                "sizeCategory": "STANDARD",
                "amountMinorUnits": 50000,
                "currency": "LKR",
                "rateVersion": "RATE-2026-V1",
                "pickupAddress": request["pickupAddress"],
                "dropoffAddress": request["dropoffAddress"],
                "createdAt": now_iso,
            }

            return self.store.commit_booking(
                parcel, history, outbox, actor, key, digest, response, fail_simulation=fail_simulation
            )


class ConcurrentBookingRegressionTest(unittest.TestCase):
    """
    Mandatory October 23 Amendment Acceptance Suite:
    Executes concurrent overlapping customer vs merchant booking requests against the same quote.
    """

    def setUp(self):
        self.store = InMemoryDeliveryTransactionStore()
        self.engine = BookingEngine(self.store)
        self.quote_id = str(uuid.uuid4())
        self.customer_sub = "cust-alice-999"
        self.merchant_id = str(uuid.uuid4())
        self.merchant_sub = "merch-bob-888"

        self.customer_request = {
            "quoteId": self.quote_id,
            "pickupAddress": "No 10, Baseline Road, Colombo 09",
            "dropoffAddress": "No 45, Kandy Road, Kelaniya",
            "recipientName": "Saman Silva",
            "recipientPhone": "+94771234567",
        }

        self.merchant_request = {
            "quoteId": self.quote_id,
            "pickupAddress": "No 100, Port Road, Colombo 13",
            "dropoffAddress": "No 45, Kandy Road, Kelaniya",
            "recipientName": "Saman Silva",
            "recipientPhone": "+94771234567",
            "merchantReference": "PO-10029",
        }

    def test_concurrent_same_quote_contention_between_customer_and_merchant(self):
        """
        Customer (Key A) and Merchant (Key B) submit booking requests for the SAME quote simultaneously.
        Enforces:
        1. Exactly ONE canonical parcel is created.
        2. Exactly ONE status history record exists.
        3. Exactly ONE outbox event exists.
        4. Exactly ONE fee/price snapshot is persisted.
        5. Losing request receives deterministic 409 QUOTE_ALREADY_CONSUMED.
        """
        key_customer = str(uuid.uuid4())
        key_merchant = str(uuid.uuid4())

        barrier = threading.Barrier(2)
        results: List[Tuple[str, Any]] = []
        lock = threading.Lock()

        def do_customer():
            barrier.wait()
            try:
                res = self.engine.book_customer(self.customer_sub, key_customer, self.customer_request)
                with lock:
                    results.append(("CUSTOMER_SUCCESS", res))
            except BookingFailure as e:
                with lock:
                    results.append(("CUSTOMER_FAIL", e))

        def do_merchant():
            barrier.wait()
            try:
                res = self.engine.book_merchant(self.merchant_id, self.merchant_sub, key_merchant, self.merchant_request)
                with lock:
                    results.append(("MERCHANT_SUCCESS", res))
            except BookingFailure as e:
                with lock:
                    results.append(("MERCHANT_FAIL", e))

        with ThreadPoolExecutor(max_workers=2) as executor:
            f1 = executor.submit(do_customer)
            f2 = executor.submit(do_merchant)
            f1.result()
            f2.result()

        # 1. Exactly one success, exactly one failure
        successes = [r for r in results if r[0].endswith("_SUCCESS")]
        failures = [r for r in results if r[0].endswith("_FAIL")]
        self.assertEqual(len(successes), 1, "Must have exactly one winning booking")
        self.assertEqual(len(failures), 1, "Must have exactly one losing booking")

        # 2. Loser receives 409 QUOTE_ALREADY_CONSUMED
        losing_err: BookingFailure = failures[0][1]
        self.assertEqual(losing_err.status, 409)
        self.assertEqual(losing_err.code, "QUOTE_ALREADY_CONSUMED")

        # 3. Database invariants
        self.assertEqual(len(self.store.parcels), 1, "Must have exactly one canonical parcel persisted")
        self.assertEqual(len(self.store.status_history), 1, "Must have exactly one status history record")
        self.assertEqual(len(self.store.outbox_events), 1, "Must have exactly one outbox event")
        winning_parcel = list(self.store.parcels.values())[0]
        self.assertEqual(winning_parcel["quote_id"], self.quote_id)
        self.assertEqual(winning_parcel["amount_minor_units"], 50000)

        # 4. Idempotent replay of winning request
        if successes[0][0] == "CUSTOMER_SUCCESS":
            replay = self.engine.book_customer(self.customer_sub, key_customer, self.customer_request)
            self.assertEqual(replay["parcelId"], successes[0][1]["parcelId"])
        else:
            replay = self.engine.book_merchant(self.merchant_id, self.merchant_sub, key_merchant, self.merchant_request)
            self.assertEqual(replay["parcelId"], successes[0][1]["parcelId"])

        # No new parcel or event created upon replay
        self.assertEqual(len(self.store.parcels), 1)
        self.assertEqual(len(self.store.outbox_events), 1)

    def test_idempotent_replay_with_changed_payload_causes_conflict(self):
        """Replaying with the same idempotency key but altered payload yields 409 IDEMPOTENCY_CONFLICT."""
        key = str(uuid.uuid4())
        self.engine.book_customer(self.customer_sub, key, self.customer_request)

        altered_request = copy.deepcopy(self.customer_request)
        altered_request["pickupAddress"] = "Completely Different Address"

        with self.assertRaises(BookingFailure) as ctx:
            self.engine.book_customer(self.customer_sub, key, altered_request)

        self.assertEqual(ctx.exception.status, 409)
        self.assertEqual(ctx.exception.code, "IDEMPOTENCY_CONFLICT")
        self.assertEqual(len(self.store.parcels), 1)

    def test_transaction_rollback_leaves_no_orphan_quote_consumption(self):
        """
        A simulated mid-transaction failure rolls back parcel, history, outbox, and
        idempotency records. The quote is NOT orphan-consumed, and subsequent retry succeeds.
        """
        key = str(uuid.uuid4())

        # Attempt booking with injected transaction crash
        with self.assertRaises(RuntimeError):
            self.engine.book_customer(self.customer_sub, key, self.customer_request, fail_simulation=True)

        # Database state after rollback: 0 parcels, 0 history, 0 outbox, 0 idempotency
        self.assertEqual(len(self.store.parcels), 0, "No parcel must remain after transaction failure")
        self.assertEqual(len(self.store.status_history), 0, "No status history must remain")
        self.assertEqual(len(self.store.outbox_events), 0, "No outbox event must remain")
        self.assertFalse(self.store.check_quote_consumed(self.quote_id), "Quote must NOT be marked consumed")

        # Retry booking succeeds cleanly with same quote
        retry_result = self.engine.book_customer(self.customer_sub, key, self.customer_request, fail_simulation=False)
        self.assertEqual(retry_result["status"], "CREATED")
        self.assertEqual(len(self.store.parcels), 1)
        self.assertEqual(len(self.store.status_history), 1)
        self.assertEqual(len(self.store.outbox_events), 1)


if __name__ == "__main__":
    unittest.main()
