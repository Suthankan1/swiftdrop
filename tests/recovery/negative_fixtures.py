"""
Negative recovery regression fixtures for Issue #168 / N10-P09A.
Provides 15+ isolated failure-injection snapshots to verify that the
consistency checker detects and flags all forms of database corruption,
concurrency violations, and durable-job state anomalies.
"""

from __future__ import annotations

import copy
from typing import Callable, Dict, Tuple

from .fixtures import build_deterministic_baseline_snapshot
from .models import (
    ApplicationSnapshot,
    DeliveryAssignmentRecord,
    NotificationJobRecord,
    OutboxEventRecord,
    ParcelRecord,
    StatusHistoryRecord,
    WebhookDeliveryRecord,
)


def create_corrupted_snapshot(modifier: Callable[[ApplicationSnapshot], None], name: str) -> ApplicationSnapshot:
    snap = build_deterministic_baseline_snapshot(snapshot_id=f"snapshot-corrupt-{name}")
    # deep copy lists so mutations don't bleed
    snap.parcels = [copy.deepcopy(p) for p in snap.parcels]
    snap.status_histories = [copy.deepcopy(h) for h in snap.status_histories]
    snap.delivery_assignments = [copy.deepcopy(a) for a in snap.delivery_assignments]
    snap.outbox_events = [copy.deepcopy(e) for e in snap.outbox_events]
    snap.consumed_events = [copy.deepcopy(c) for c in snap.consumed_events]
    snap.notifications = [copy.deepcopy(n) for n in snap.notifications]
    snap.notification_attempts = [copy.deepcopy(na) for na in snap.notification_attempts]
    snap.webhook_deliveries = [copy.deepcopy(w) for w in snap.webhook_deliveries]
    snap.proof_of_deliveries = [copy.deepcopy(pr) for pr in snap.proof_of_deliveries]
    snap.pricing_quotes = [copy.deepcopy(q) for q in snap.pricing_quotes]
    modifier(snap)
    return snap


# 1. Missing canonical parcel referenced by history
def inject_missing_parcel_in_history(snap: ApplicationSnapshot):
    snap.status_histories.append(
        StatusHistoryRecord(
            history_id="hist-orphan-0001",
            parcel_id="parc-nonexistent-0000-000000000999",
            previous_status=None,
            new_status="CREATED",
            reason="ORPHAN_ENTRY",
            note="Points to missing parcel",
            proof_id=None,
            actor_sub="sub-cust-alpha-001",
            resulting_version=1,
            occurred_at="2026-10-28T04:00:00Z",
        )
    )


# 2. Invalid parcel version sequence (jump or regression)
def inject_invalid_version_sequence(snap: ApplicationSnapshot):
    # Alter parcel 2 history so versions go 1 -> 4 instead of 1 -> 2 -> 3
    for h in snap.status_histories:
        if h.parcel_id == snap.parcels[1].parcel_id and h.resulting_version == 2:
            h.resulting_version = 4


# 3. Conflicting active courier assignments (double-active assignment for same parcel)
def inject_conflicting_active_assignments(snap: ApplicationSnapshot):
    p2_id = snap.parcels[1].parcel_id
    snap.delivery_assignments.append(
        DeliveryAssignmentRecord(
            assignment_id="asgn-conflict-active-0001",
            parcel_id=p2_id,
            courier_sub="sub-cour-kamal-004",  # second courier assigned actively
            assigned_by="sub-disp-sunil-006",
            assigned_at="2026-10-28T04:40:00Z",
            ended_at=None,
            active=True,  # VIOLATION: two active assignments for parcel 2
        )
    )


# 4. Duplicate logical outbox event (same aggregate_id and aggregate_version)
def inject_duplicate_outbox_event(snap: ApplicationSnapshot):
    p1_id = snap.parcels[0].parcel_id
    snap.outbox_events.append(
        OutboxEventRecord(
            event_id="evt-dup-0001-0000-000000000001",
            aggregate_type="PARCEL",
            aggregate_id=p1_id,
            aggregate_version=1,  # VIOLATION: duplicate version 1 for p1
            event_type="PARCEL_CREATED",
            schema_version="1.0.0",
            payload={"parcelId": p1_id, "status": "CREATED", "version": 1},
            status="PENDING",
            created_at="2026-10-28T03:36:00Z",
            attempt_count=0,
            topic="parcel.lifecycle.v1",
            partition_key=p1_id,
        )
    )


# 5. Outbox event with incorrect aggregate association (orphan outbox event)
def inject_outbox_missing_aggregate(snap: ApplicationSnapshot):
    snap.outbox_events.append(
        OutboxEventRecord(
            event_id="evt-orphan-0001-000000000001",
            aggregate_type="PARCEL",
            aggregate_id="parc-missing-aggregate-9999",
            aggregate_version=1,
            event_type="PARCEL_CREATED",
            schema_version="1.0.0",
            payload={"parcelId": "parc-missing-aggregate-9999"},
            status="PENDING",
            created_at="2026-10-28T04:00:00Z",
            attempt_count=0,
            topic="parcel.lifecycle.v1",
            partition_key="parc-missing-aggregate-9999",
        )
    )


# 6. Duplicate inbox application (same consumer_name and event_id)
def inject_duplicate_inbox_event(snap: ApplicationSnapshot):
    evt = snap.consumed_events[0]
    snap.consumed_events.append(
        copy.deepcopy(evt)  # VIOLATION: exact duplicate in inbox
    )


# 7. Missing required event-envelope field (missing parcelId or status in payload)
def inject_missing_envelope_field(snap: ApplicationSnapshot):
    snap.outbox_events[0].payload = {"version": 1}  # Missing parcelId and status


# 8. Invalid retry state (negative attempt count)
def inject_negative_retry_attempt(snap: ApplicationSnapshot):
    snap.notifications[0].attempts = -1  # VIOLATION: attempts < 0


# 9. Retry count exceeding approved limits without terminal status
def inject_unbounded_retry_without_exhaustion(snap: ApplicationSnapshot):
    snap.notifications[1].attempts = 7
    snap.notifications[1].status = "FAILED"  # VIOLATION: attempts >= 5 must be EXHAUSTED


# 10. Completed job incorrectly eligible for retry
def inject_completed_job_with_future_retry(snap: ApplicationSnapshot):
    snap.notifications[0].status = "SENT"
    snap.notifications[0].next_attempt_at = "2026-10-28T06:00:00Z"  # VIOLATION: SENT job must not have retry


# 11. Duplicate notification intent (same event_id, recipient_sub, channel)
def inject_duplicate_notification_intent(snap: ApplicationSnapshot):
    n0 = snap.notifications[0]
    snap.notifications.append(
        NotificationJobRecord(
            notification_id="notif-dup-0001",
            event_id=n0.event_id,
            recipient_sub=n0.recipient_sub,
            parcel_id=n0.parcel_id,
            channel=n0.channel,
            destination_ref=n0.destination_ref,
            template=n0.template,
            status="PENDING",
            attempts=0,
            next_attempt_at="2026-10-28T05:00:00Z",
            created_at="2026-10-28T04:46:00Z",
            updated_at="2026-10-28T04:46:00Z",
        )
    )


# 12. Missing proof reference (DELIVERED parcel with missing proof_of_deliveries)
def inject_delivered_parcel_missing_proof(snap: ApplicationSnapshot):
    snap.proof_of_deliveries.clear()  # VIOLATION: parcel 1 is DELIVERED but proof is missing


# 13. Quote/booking provenance mismatch (amount in parcel does not match quote snapshot)
def inject_quote_booking_amount_mismatch(snap: ApplicationSnapshot):
    snap.parcels[0].amount_minor_units = 999999  # VIOLATION: does not match quote 150000


# 14. Snapshot record outside expected checkpoint boundary
def inject_post_checkpoint_record_leakage(snap: ApplicationSnapshot):
    snap.parcels.append(
        ParcelRecord(
            parcel_id="parc-post-checkpoint-0001",
            tracking_number="SWD-20261028-9999",
            sender_sub="sub-cust-alpha-001",
            merchant_id=None,
            quote_id=None,
            rate_version="RV-LKR-2026-V1",
            amount_minor_units=100000,
            currency="LKR",
            weight_kg=1.0,
            status="CREATED",
            version=1,
            created_at="2026-10-28T05:15:00Z",  # AFTER cutoff 05:00:00Z!
            updated_at="2026-10-28T05:15:00Z",
        )
    )


# 15. Invalid terminal status transition (DELIVERED transitioning back to IN_TRANSIT)
def inject_terminal_state_transition(snap: ApplicationSnapshot):
    p1 = snap.parcels[0]
    p1.version = 6
    p1.status = "IN_TRANSIT"  # VIOLATION: illegal transition from terminal DELIVERED
    snap.status_histories.append(
        StatusHistoryRecord(
            history_id="hist-illegal-terminal-0001",
            parcel_id=p1.parcel_id,
            previous_status="DELIVERED",
            new_status="IN_TRANSIT",
            reason="ILLEGAL_REOPEN",
            note="Delivered parcel cannot be reopened",
            proof_id=None,
            actor_sub="sub-disp-sunil-006",
            resulting_version=6,
            occurred_at="2026-10-28T04:50:00Z",
        )
    )


# 16. Duplicate quote consumption across two parcels
def inject_duplicate_quote_consumption(snap: ApplicationSnapshot):
    # Set parcel 2 to consume the same quote_id as parcel 1
    snap.parcels[1].quote_id = snap.parcels[0].quote_id  # VIOLATION: uq_parcels_quote_id


# 17. Active assignment on terminal parcel
def inject_active_assignment_on_delivered_parcel(snap: ApplicationSnapshot):
    # Make parcel 1 assignment active again even though it is DELIVERED
    snap.delivery_assignments[0].active = True
    snap.delivery_assignments[0].ended_at = None


NEGATIVE_FIXTURE_FACTORIES: Dict[str, Tuple[Callable[[ApplicationSnapshot], None], str]] = {
    "NF01_MISSING_PARCEL_IN_HISTORY": (inject_missing_parcel_in_history, "StatusHistory"),
    "NF02_INVALID_VERSION_SEQUENCE": (inject_invalid_version_sequence, "StatusHistory"),
    "NF03_CONFLICTING_ACTIVE_ASSIGNMENTS": (inject_conflicting_active_assignments, "CourierAssignment"),
    "NF04_DUPLICATE_OUTBOX_EVENT": (inject_duplicate_outbox_event, "DeliveryOutbox"),
    "NF05_OUTBOX_MISSING_AGGREGATE": (inject_outbox_missing_aggregate, "DeliveryOutbox"),
    "NF06_DUPLICATE_INBOX_EVENT": (inject_duplicate_inbox_event, "CommunicationsInbox"),
    "NF07_MISSING_ENVELOPE_FIELD": (inject_missing_envelope_field, "DeliveryOutbox"),
    "NF08_NEGATIVE_RETRY_ATTEMPT": (inject_negative_retry_attempt, "NotificationJobs"),
    "NF09_UNBOUNDED_RETRY_NO_EXHAUSTION": (inject_unbounded_retry_without_exhaustion, "NotificationJobs"),
    "NF10_COMPLETED_JOB_FUTURE_RETRY": (inject_completed_job_with_future_retry, "NotificationJobs"),
    "NF11_DUPLICATE_NOTIFICATION_INTENT": (inject_duplicate_notification_intent, "NotificationJobs"),
    "NF12_DELIVERED_PARCEL_MISSING_PROOF": (inject_delivered_parcel_missing_proof, "PrivateProofs"),
    "NF13_QUOTE_BOOKING_AMOUNT_MISMATCH": (inject_quote_booking_amount_mismatch, "Pricing"),
    "NF14_POST_CHECKPOINT_RECORD_LEAKAGE": (inject_post_checkpoint_record_leakage, "RpoBoundary"),
    "NF15_TERMINAL_STATE_TRANSITION": (inject_terminal_state_transition, "StatusHistory"),
    "NF16_DUPLICATE_QUOTE_CONSUMPTION": (inject_duplicate_quote_consumption, "Delivery"),
    "NF17_ACTIVE_ASSIGNMENT_ON_TERMINAL_PARCEL": (inject_active_assignment_on_delivered_parcel, "CourierAssignment"),
}
