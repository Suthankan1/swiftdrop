"""
Core consistency verification engine for Issue #168 / N10-P09A.
Implements domain invariants, point-in-time boundary auditing,
idempotent recovery assertions, and structured reporting.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set, Tuple

from .models import (
    ApplicationSnapshot,
    DomainCheckResult,
    DomainName,
    InvariantResult,
    RecoveryMetadata,
    VerificationReport,
    VerificationStatus,
)

ALLOWED_STATUS_TRANSITIONS: Dict[Optional[str], Set[str]] = {
    None: {"CREATED"},
    "CREATED": {"PICKED_UP", "FAILED"},
    "PICKED_UP": {"IN_TRANSIT", "FAILED"},
    "IN_TRANSIT": {"OUT_FOR_DELIVERY", "FAILED"},
    "OUT_FOR_DELIVERY": {"DELIVERED", "FAILED"},
    "DELIVERED": set(),  # Terminal state
    "FAILED": set(),     # Terminal state
}

MAX_NOTIFICATION_RETRIES = 5
MAX_WEBHOOK_RETRIES = 5


def parse_iso(ts_str: Optional[str]) -> Optional[datetime]:
    if not ts_str:
        return None
    try:
        # Normalize trailing Z to +00:00
        clean = ts_str.replace("Z", "+00:00")
        return datetime.fromisoformat(clean)
    except Exception:
        return None


class RecoveryConsistencyVerifier:
    """
    Evaluates an application snapshot against SwiftDrop v1.3 domain invariants
    and point-in-time recovery boundaries.
    """

    def __init__(
        self,
        post_recovery_snapshot: ApplicationSnapshot,
        pre_recovery_baseline: Optional[ApplicationSnapshot] = None,
        metadata: Optional[RecoveryMetadata] = None,
        mode: str = "offline",
    ):
        self.snapshot = post_recovery_snapshot
        self.baseline = pre_recovery_baseline
        self.metadata = metadata or RecoveryMetadata()
        self.mode = mode
        self.invariants: List[InvariantResult] = []
        self.domains: Dict[str, DomainCheckResult] = {}
        self.unverified_items: List[str] = []
        self.recommendations: List[str] = []

    def _record_invariant(
        self,
        invariant_id: str,
        domain: DomainName,
        description: str,
        passed: bool,
        message: str,
        records_checked: int = 0,
        violations_count: int = 0,
        violation_samples: Optional[List[Dict[str, Any]]] = None,
    ) -> InvariantResult:
        status = VerificationStatus.PASS if passed else VerificationStatus.FAIL
        if not passed and violation_samples:
            sample_errors = [str(v.get("error", "")) for v in violation_samples if "error" in v]
            if sample_errors:
                message = f"{message}: {'; '.join(sample_errors[:3])}"
        result = InvariantResult(
            invariant_id=invariant_id,
            domain=domain,
            description=description,
            status=status,
            message=message,
            records_checked=records_checked,
            violations_count=violations_count,
            violation_samples=violation_samples or [],
        )
        self.invariants.append(result)
        return result

    # -------------------------------------------------------------------------
    # 1. Accounts & Keycloak Identity
    # -------------------------------------------------------------------------
    def verify_accounts_and_identity(self):
        domain = DomainName.ACCOUNTS
        checked = len(self.snapshot.accounts_profiles)
        violations = []

        # Check unique non-empty subject
        subs_seen = set()
        for p in self.snapshot.accounts_profiles:
            if not p.keycloak_sub or not p.keycloak_sub.strip():
                violations.append({"id": p.id, "error": "Blank keycloak_sub"})
            if p.keycloak_sub in subs_seen:
                violations.append({"id": p.id, "sub": p.keycloak_sub, "error": "Duplicate keycloak_sub"})
            subs_seen.add(p.keycloak_sub)

        # Check Keycloak user mapping
        kc_users_by_sub = {u.id: u for u in self.snapshot.keycloak_users}
        for p in self.snapshot.accounts_profiles:
            if p.keycloak_sub not in kc_users_by_sub:
                violations.append({"id": p.id, "sub": p.keycloak_sub, "error": "Orphan profile without Keycloak user"})

        self._record_invariant(
            "INV-ACC-01",
            domain,
            "Account profiles have unique non-blank Keycloak subjects and link to valid users",
            len(violations) == 0,
            "All account profiles valid" if not violations else f"Found {len(violations)} account profile violations",
            checked,
            len(violations),
            violations,
        )

        # Staff role consistency
        staff_checked = len(self.snapshot.staff_profiles)
        staff_violations = []
        for s in self.snapshot.staff_profiles:
            if s.staff_role not in ("COURIER", "DISPATCHER", "ADMIN"):
                staff_violations.append({"staff_id": s.staff_id, "error": f"Invalid staff role {s.staff_role}"})
            if s.status not in ("ACTIVE", "SUSPENDED", "INACTIVE"):
                staff_violations.append({"staff_id": s.staff_id, "error": f"Invalid staff status {s.status}"})
            # Check user role in Keycloak
            kc_user = kc_users_by_sub.get(s.keycloak_sub)
            if kc_user and s.staff_role not in kc_user.roles:
                staff_violations.append({
                    "staff_id": s.staff_id,
                    "sub": s.keycloak_sub,
                    "expected_role": s.staff_role,
                    "actual_roles": kc_user.roles,
                    "error": "Staff role mismatch with Keycloak role",
                })

        self._record_invariant(
            "INV-ACC-02",
            domain,
            "Staff profiles have valid roles, statuses, and matching Keycloak role assignments",
            len(staff_violations) == 0,
            "Staff profiles role consistent" if not staff_violations else f"Found {len(staff_violations)} staff profile violations",
            staff_checked,
            len(staff_violations),
            staff_violations,
        )

        # Anti-privilege escalation: Customer profiles cannot have ADMIN or DISPATCHER roles
        priv_violations = []
        staff_subs = {s.keycloak_sub for s in self.snapshot.staff_profiles}
        for u in self.snapshot.keycloak_users:
            if ("ADMIN" in u.roles or "DISPATCHER" in u.roles) and u.id not in staff_subs:
                priv_violations.append({"sub": u.id, "username": u.username, "error": "Elevated role without staff profile"})

        self._record_invariant(
            "INV-ACC-03",
            domain,
            "Zero privilege escalation: customer subjects do not hold administrative roles",
            len(priv_violations) == 0,
            "Privilege confinement verified" if not priv_violations else f"Found {len(priv_violations)} privilege escalation violations",
            len(self.snapshot.keycloak_users),
            len(priv_violations),
            priv_violations,
        )

    # -------------------------------------------------------------------------
    # 2. Pricing & Quotes
    # -------------------------------------------------------------------------
    def verify_pricing_and_quotes(self):
        domain = DomainName.PRICING
        checked = len(self.snapshot.pricing_quotes)
        violations = []

        rate_versions_by_code = {rv.version_code: rv for rv in self.snapshot.pricing_rate_versions}
        # Rate card invariant: At most one active rate version per currency
        active_rv_by_curr: Dict[str, str] = {}
        rv_violations = []
        for rv in self.snapshot.pricing_rate_versions:
            if rv.status == "ACTIVE":
                if rv.currency in active_rv_by_curr:
                    rv_violations.append({
                        "currency": rv.currency,
                        "rv1": active_rv_by_curr[rv.currency],
                        "rv2": rv.version_code,
                        "error": "Multiple active rate versions for currency",
                    })
                active_rv_by_curr[rv.currency] = rv.version_code

        self._record_invariant(
            "INV-PRC-01",
            domain,
            "At most one active rate card per currency in pricing_rate_versions",
            len(rv_violations) == 0,
            "Rate card uniqueness verified" if not rv_violations else f"Found {len(rv_violations)} rate card violations",
            len(self.snapshot.pricing_rate_versions),
            len(rv_violations),
            rv_violations,
        )

        for q in self.snapshot.pricing_quotes:
            c_at = parse_iso(q.created_at)
            e_at = parse_iso(q.expires_at)
            if not c_at or not e_at or e_at <= c_at:
                violations.append({"quote_id": q.id, "error": "expires_at must be strictly after created_at"})

            if q.total_amount_minor_units != (q.base_fee_minor_units + q.weight_surcharge_minor_units):
                violations.append({
                    "quote_id": q.id,
                    "total": q.total_amount_minor_units,
                    "expected": q.base_fee_minor_units + q.weight_surcharge_minor_units,
                    "error": "Total amount does not equal base fee + weight surcharge",
                })

            if q.rate_version_code not in rate_versions_by_code:
                violations.append({"quote_id": q.id, "rate_version_code": q.rate_version_code, "error": "Unknown rate version"})

            if q.is_anonymous and q.subject_sub is not None:
                violations.append({"quote_id": q.id, "error": "Anonymous quote cannot have subject_sub"})
            if not q.is_anonymous and q.subject_sub is None:
                violations.append({"quote_id": q.id, "error": "Non-anonymous quote must have subject_sub"})

        self._record_invariant(
            "INV-PRC-02",
            domain,
            "Quote interval, integer money arithmetic, and ownership consistency preserved",
            len(violations) == 0,
            "All pricing quotes valid" if not violations else f"Found {len(violations)} quote violations",
            checked,
            len(violations),
            violations,
        )

    # -------------------------------------------------------------------------
    # 3. Delivery & Quote-to-Booking Provenance
    # -------------------------------------------------------------------------
    def verify_delivery_booking_provenance(self):
        domain = DomainName.DELIVERY
        checked = len(self.snapshot.parcels)
        violations = []

        quotes_by_id = {q.id: q for q in self.snapshot.pricing_quotes}
        consumed_quotes: Dict[str, str] = {}

        for p in self.snapshot.parcels:
            if p.quote_id:
                # Single-use quote consumption invariant: uq_parcels_quote_id
                if p.quote_id in consumed_quotes:
                    violations.append({
                        "quote_id": p.quote_id,
                        "parcel_1": consumed_quotes[p.quote_id],
                        "parcel_2": p.parcel_id,
                        "error": "Quote double-consumed across multiple parcels",
                    })
                consumed_quotes[p.quote_id] = p.parcel_id

                # Quote provenance check: booked amount and currency must match quote snapshot
                if p.quote_snapshot:
                    snap_amount = p.quote_snapshot.get("total_amount_minor_units")
                    snap_curr = p.quote_snapshot.get("currency")
                    if p.amount_minor_units is not None and snap_amount is not None and p.amount_minor_units != snap_amount:
                        violations.append({
                            "parcel_id": p.parcel_id,
                            "parcel_amount": p.amount_minor_units,
                            "quote_snapshot_amount": snap_amount,
                            "error": "Parcel booked amount does not match quote snapshot",
                        })
                    if p.currency != snap_curr:
                        violations.append({
                            "parcel_id": p.parcel_id,
                            "parcel_currency": p.currency,
                            "quote_snapshot_currency": snap_curr,
                            "error": "Parcel currency does not match quote snapshot",
                        })

                # Check against pricing quotes table if present
                quote_record = quotes_by_id.get(p.quote_id)
                if quote_record:
                    if p.amount_minor_units is not None and p.amount_minor_units != quote_record.total_amount_minor_units:
                        violations.append({
                            "parcel_id": p.parcel_id,
                            "parcel_amount": p.amount_minor_units,
                            "authoritative_quote_amount": quote_record.total_amount_minor_units,
                            "error": "Parcel booked amount does not match authoritative quote",
                        })

        self._record_invariant(
            "INV-DEL-01",
            domain,
            "Global single-use quote consumption and quote snapshot provenance intact",
            len(violations) == 0,
            "Quote booking provenance intact" if not violations else f"Found {len(violations)} booking provenance violations",
            checked,
            len(violations),
            violations,
        )

        # Idempotency booking checks
        idem_checked = len(self.snapshot.idempotency_records)
        idem_violations = []
        parcels_by_id = {p.parcel_id: p for p in self.snapshot.parcels}
        for idem in self.snapshot.idempotency_records:
            if idem.status == "COMPLETED" and idem.stored_result:
                pid = idem.stored_result.get("parcelId")
                if pid and pid not in parcels_by_id:
                    idem_violations.append({
                        "record_id": idem.record_id,
                        "parcel_id": pid,
                        "error": "Completed idempotency record references missing canonical parcel",
                    })

        self._record_invariant(
            "INV-DEL-02",
            domain,
            "Completed booking idempotency records reference valid canonical parcels without orphan residue",
            len(idem_violations) == 0,
            "Idempotency records consistent" if not idem_violations else f"Found {len(idem_violations)} idempotency record violations",
            idem_checked,
            len(idem_violations),
            idem_violations,
        )

    # -------------------------------------------------------------------------
    # 4. Parcel Version & Status History Monotonicity
    # -------------------------------------------------------------------------
    def verify_parcel_status_history(self):
        domain = DomainName.STATUS_HISTORY
        parcels_by_id = {p.parcel_id: p for p in self.snapshot.parcels}
        history_by_parcel: Dict[str, List[Any]] = {}

        orphan_violations = []
        for h in self.snapshot.status_histories:
            if h.parcel_id not in parcels_by_id:
                orphan_violations.append({
                    "history_id": h.history_id,
                    "parcel_id": h.parcel_id,
                    "error": "Status history references nonexistent canonical parcel",
                })
            history_by_parcel.setdefault(h.parcel_id, []).append(h)

        self._record_invariant(
            "INV-HST-01",
            domain,
            "Zero orphan status history records: all events reference canonical parcels",
            len(orphan_violations) == 0,
            "All history records link to parcels" if not orphan_violations else f"Found {len(orphan_violations)} orphan history records",
            len(self.snapshot.status_histories),
            len(orphan_violations),
            orphan_violations,
        )

        # Check version monotonicity, transition legality, and projection alignment
        version_violations = []
        transition_violations = []

        for p in self.snapshot.parcels:
            hist_entries = history_by_parcel.get(p.parcel_id, [])
            if not hist_entries:
                version_violations.append({"parcel_id": p.parcel_id, "error": "Parcel has zero status history records"})
                continue

            # Sort deterministically by resulting_version, with history_id tie-breaker
            sorted_hist = sorted(hist_entries, key=lambda x: (x.resulting_version, x.history_id))

            expected_version = 1
            last_status: Optional[str] = None
            for idx, entry in enumerate(sorted_hist):
                # Monotonic sequence check: resulting_version must be exactly 1, 2, 3...
                if entry.resulting_version != expected_version:
                    version_violations.append({
                        "parcel_id": p.parcel_id,
                        "history_id": entry.history_id,
                        "expected_version": expected_version,
                        "actual_version": entry.resulting_version,
                        "error": "Non-monotonic version sequence in status history",
                    })
                expected_version += 1

                # Legal transition check
                allowed_next = ALLOWED_STATUS_TRANSITIONS.get(entry.previous_status, set())
                if entry.new_status not in allowed_next:
                    transition_violations.append({
                        "parcel_id": p.parcel_id,
                        "history_id": entry.history_id,
                        "prev_status": entry.previous_status,
                        "new_status": entry.new_status,
                        "allowed": list(allowed_next),
                        "error": "Illegal status transition according to lifecycle state machine",
                    })

                # Terminal state check: no transition allowed if previous status was DELIVERED or FAILED
                if entry.previous_status in ("DELIVERED", "FAILED"):
                    transition_violations.append({
                        "parcel_id": p.parcel_id,
                        "history_id": entry.history_id,
                        "prev_status": entry.previous_status,
                        "error": "Illegal mutation after reaching terminal state",
                    })

                last_status = entry.new_status

            # Projection alignment check: parcel.version must equal latest resulting_version
            latest_entry = sorted_hist[-1]
            if p.version != latest_entry.resulting_version:
                version_violations.append({
                    "parcel_id": p.parcel_id,
                    "parcel_version": p.version,
                    "latest_history_version": latest_entry.resulting_version,
                    "error": "Parcel version does not match latest status history version",
                })

            # Status projection alignment: parcel.status must equal latest new_status
            if p.status != latest_entry.new_status:
                transition_violations.append({
                    "parcel_id": p.parcel_id,
                    "parcel_status": p.status,
                    "latest_history_status": latest_entry.new_status,
                    "error": "Parcel status does not match latest history status",
                })

        self._record_invariant(
            "INV-HST-02",
            domain,
            "Strictly monotonic resulting_version sequence (1..N) and parcel version projection parity",
            len(version_violations) == 0,
            "Version monotonicity verified" if not version_violations else f"Found {len(version_violations)} version sequence violations",
            len(self.snapshot.parcels),
            len(version_violations),
            version_violations,
        )

        self._record_invariant(
            "INV-HST-03",
            domain,
            "Status transitions strictly comply with lifecycle state machine and terminal finality",
            len(transition_violations) == 0,
            "Lifecycle transitions valid" if not transition_violations else f"Found {len(transition_violations)} transition violations",
            len(self.snapshot.status_histories),
            len(transition_violations),
            transition_violations,
        )

    # -------------------------------------------------------------------------
    # 5. Courier Assignment Invariants
    # -------------------------------------------------------------------------
    def verify_courier_assignments(self):
        domain = DomainName.COURIER_ASSIGNMENT
        parcels_by_id = {p.parcel_id: p for p in self.snapshot.parcels}
        active_assignments_by_parcel: Dict[str, List[Any]] = {}
        violations = []

        for asgn in self.snapshot.delivery_assignments:
            if asgn.parcel_id not in parcels_by_id:
                violations.append({
                    "assignment_id": asgn.assignment_id,
                    "parcel_id": asgn.parcel_id,
                    "error": "Assignment references nonexistent parcel",
                })
                continue

            parcel = parcels_by_id[asgn.parcel_id]

            if asgn.active:
                active_assignments_by_parcel.setdefault(asgn.parcel_id, []).append(asgn)

                # Parcel terminal status check: terminal parcels cannot have active assignments
                if parcel.status in ("DELIVERED", "FAILED"):
                    violations.append({
                        "assignment_id": asgn.assignment_id,
                        "parcel_id": asgn.parcel_id,
                        "parcel_status": parcel.status,
                        "error": "Active courier assignment exists on terminal parcel",
                    })

                if asgn.ended_at is not None:
                    violations.append({
                        "assignment_id": asgn.assignment_id,
                        "error": "Active assignment cannot have ended_at timestamp",
                    })
            else:
                # Inactive historical assignment must have ended_at
                if asgn.ended_at is None:
                    violations.append({
                        "assignment_id": asgn.assignment_id,
                        "error": "Inactive assignment must have ended_at timestamp",
                    })

        # Single active assignment invariant: at most one active per parcel
        for pid, active_list in active_assignments_by_parcel.items():
            if len(active_list) > 1:
                violations.append({
                    "parcel_id": pid,
                    "active_assignments_count": len(active_list),
                    "assignment_ids": [a.assignment_id for a in active_list],
                    "error": "Multiple active courier assignments on the same parcel (violates uq_delivery_assignments_active_parcel)",
                })

        self._record_invariant(
            "INV-ASG-01",
            domain,
            "At most one active assignment per parcel and proper terminal unlinking",
            len(violations) == 0,
            "Single active courier assignment invariant verified" if not violations else f"Found {len(violations)} courier assignment violations",
            len(self.snapshot.delivery_assignments),
            len(violations),
            violations,
        )

    # -------------------------------------------------------------------------
    # 6. Delivery Transactional Outbox
    # -------------------------------------------------------------------------
    def verify_delivery_outbox(self):
        domain = DomainName.DELIVERY_OUTBOX
        checked = len(self.snapshot.outbox_events)
        parcels_by_id = {p.parcel_id: p for p in self.snapshot.parcels}
        violations = []

        seen_event_ids = set()
        seen_agg_versions: Set[Tuple[str, int]] = set()

        for evt in self.snapshot.outbox_events:
            # Event ID uniqueness
            if evt.event_id in seen_event_ids:
                violations.append({"event_id": evt.event_id, "error": "Duplicate outbox event_id"})
            seen_event_ids.add(evt.event_id)

            # Aggregate version uniqueness per aggregate (uq_outbox_aggregate_version)
            agg_key = (evt.aggregate_id, evt.aggregate_version)
            if agg_key in seen_agg_versions:
                violations.append({
                    "event_id": evt.event_id,
                    "aggregate_id": evt.aggregate_id,
                    "version": evt.aggregate_version,
                    "error": "Duplicate (aggregate_id, aggregate_version) violates outbox sequence constraint",
                })
            seen_agg_versions.add(agg_key)

            # Aggregate linkage
            if evt.aggregate_id not in parcels_by_id:
                violations.append({
                    "event_id": evt.event_id,
                    "aggregate_id": evt.aggregate_id,
                    "error": "Outbox event references missing aggregate parcel",
                })

            # Envelope validation
            if not isinstance(evt.payload, dict) or "parcelId" not in evt.payload:
                violations.append({
                    "event_id": evt.event_id,
                    "error": "Outbox payload missing mandatory 'parcelId' field",
                })

            if evt.status not in ("PENDING", "IN_FLIGHT", "PUBLISHED", "FAILED", "DEAD_LETTER"):
                violations.append({"event_id": evt.event_id, "status": evt.status, "error": "Invalid outbox status"})

            if evt.status == "PUBLISHED" and not evt.acknowledged_at:
                violations.append({"event_id": evt.event_id, "error": "Published event missing acknowledged_at timestamp"})

            if evt.status == "IN_FLIGHT" and not evt.claim_expires_at:
                violations.append({"event_id": evt.event_id, "error": "In-flight event missing claim_expires_at lease"})

        self._record_invariant(
            "INV-OTB-01",
            domain,
            "Outbox event uniqueness, aggregate version sequencing, envelope validation, and lease integrity",
            len(violations) == 0,
            "Delivery outbox integrity verified" if not violations else f"Found {len(violations)} outbox violations",
            checked,
            len(violations),
            violations,
        )

    # -------------------------------------------------------------------------
    # 7. Communications Inbox & Deduplication
    # -------------------------------------------------------------------------
    def verify_communications_inbox(self):
        domain = DomainName.COMMUNICATIONS_INBOX
        checked = len(self.snapshot.consumed_events)
        violations = []

        seen_inbox: Set[Tuple[str, str]] = set()
        for c in self.snapshot.consumed_events:
            key = (c.consumer_name, c.event_id)
            if key in seen_inbox:
                violations.append({
                    "consumer_name": c.consumer_name,
                    "event_id": c.event_id,
                    "error": "Duplicate consumed event violates composite PK (consumer_name, event_id)",
                })
            seen_inbox.add(key)

        self._record_invariant(
            "INV-INB-01",
            domain,
            "Consumed events inbox enforces composite PK deduplication and idempotent suppression",
            len(violations) == 0,
            "Communications inbox deduplication verified" if not violations else f"Found {len(violations)} inbox violations",
            checked,
            len(violations),
            violations,
        )

    # -------------------------------------------------------------------------
    # 8. Durable Notification & Webhook Jobs
    # -------------------------------------------------------------------------
    def verify_durable_jobs(self):
        domain_notif = DomainName.NOTIFICATION_JOBS
        domain_wh = DomainName.WEBHOOK_JOBS

        # A. Notifications
        notif_checked = len(self.snapshot.notifications)
        notif_violations = []
        seen_notif_keys: Set[Tuple[str, str, str]] = set()

        for n in self.snapshot.notifications:
            key = (n.event_id, n.recipient_sub, n.channel)
            if key in seen_notif_keys:
                notif_violations.append({
                    "notification_id": n.notification_id,
                    "event_id": n.event_id,
                    "channel": n.channel,
                    "error": "Duplicate notification intent violates uq_notifications_event_recipient_channel",
                })
            seen_notif_keys.add(key)

            if n.attempts < 0:
                notif_violations.append({"notification_id": n.notification_id, "attempts": n.attempts, "error": "Negative attempt count"})

            # Terminal status invariants
            if n.status == "SENT":
                if n.next_attempt_at is not None:
                    notif_violations.append({
                        "notification_id": n.notification_id,
                        "error": "Completed (SENT) notification has future next_attempt_at",
                    })

            if n.status in ("PENDING", "FAILED"):
                if n.attempts >= MAX_NOTIFICATION_RETRIES:
                    notif_violations.append({
                        "notification_id": n.notification_id,
                        "attempts": n.attempts,
                        "status": n.status,
                        "error": f"Notification attempts >= {MAX_NOTIFICATION_RETRIES} must be transitioned to EXHAUSTED",
                    })

            if n.status == "EXHAUSTED":
                if n.next_attempt_at is not None:
                    notif_violations.append({
                        "notification_id": n.notification_id,
                        "error": "EXHAUSTED notification has active next_attempt_at",
                    })

        self._record_invariant(
            "INV-JOB-01",
            domain_notif,
            "Durable notification jobs enforce single-intent uniqueness, bounded retries, and terminal closure",
            len(notif_violations) == 0,
            "Notification jobs state machine verified" if not notif_violations else f"Found {len(notif_violations)} notification job violations",
            notif_checked,
            len(notif_violations),
            notif_violations,
        )

        # B. Webhook Deliveries
        wh_checked = len(self.snapshot.webhook_deliveries)
        wh_violations = []
        seen_wh_keys: Set[Tuple[str, str]] = set()

        for w in self.snapshot.webhook_deliveries:
            key = (w.subscription_id, w.event_id)
            if key in seen_wh_keys:
                wh_violations.append({
                    "delivery_id": w.delivery_id,
                    "subscription_id": w.subscription_id,
                    "event_id": w.event_id,
                    "error": "Duplicate webhook delivery violates uq_webhook_deliveries_subscription_event",
                })
            seen_wh_keys.add(key)

            if w.attempt_count < 0:
                wh_violations.append({"delivery_id": w.delivery_id, "error": "Negative attempt_count"})

            if w.status == "SUCCESS" and w.next_attempt_at is not None:
                wh_violations.append({"delivery_id": w.delivery_id, "error": "SUCCESS webhook delivery has future next_attempt_at"})

            if w.status in ("PENDING", "FAILED") and w.attempt_count >= MAX_WEBHOOK_RETRIES:
                wh_violations.append({
                    "delivery_id": w.delivery_id,
                    "attempt_count": w.attempt_count,
                    "status": w.status,
                    "error": f"Webhook delivery attempts >= {MAX_WEBHOOK_RETRIES} must be transitioned to EXHAUSTED",
                })

        self._record_invariant(
            "INV-JOB-02",
            domain_wh,
            "Durable webhook jobs enforce unique subscription-event binding, bounded retry policies, and terminal closure",
            len(wh_violations) == 0,
            "Webhook jobs state machine verified" if not wh_violations else f"Found {len(wh_violations)} webhook delivery violations",
            wh_checked,
            len(wh_violations),
            wh_violations,
        )

    # -------------------------------------------------------------------------
    # 9. Private Proof-of-Delivery
    # -------------------------------------------------------------------------
    def verify_private_proofs(self):
        domain = DomainName.PRIVATE_PROOFS
        checked = len(self.snapshot.proof_of_deliveries)
        parcels_by_id = {p.parcel_id: p for p in self.snapshot.parcels}
        proofs_by_parcel = {pr.parcel_id: pr for pr in self.snapshot.proof_of_deliveries}
        violations = []

        seen_proof_parcels = set()
        for pr in self.snapshot.proof_of_deliveries:
            if pr.parcel_id in seen_proof_parcels:
                violations.append({"proof_id": pr.proof_id, "parcel_id": pr.parcel_id, "error": "Duplicate proof for parcel (violates uq_proof_parcel_id)"})
            seen_proof_parcels.add(pr.parcel_id)

            if pr.parcel_id not in parcels_by_id:
                violations.append({"proof_id": pr.proof_id, "parcel_id": pr.parcel_id, "error": "Proof references nonexistent parcel"})

            # Private object storage key invariant: must start with pod/ and not be an http/https url
            if pr.object_reference.startswith("http://") or pr.object_reference.startswith("https://"):
                violations.append({"proof_id": pr.proof_id, "ref": pr.object_reference, "error": "Proof exposed as public URL (violates private storage policy)"})

        # Mandatory proof check: DELIVERED parcels MUST have proof of delivery
        for p in self.snapshot.parcels:
            if p.status == "DELIVERED":
                if p.parcel_id not in proofs_by_parcel:
                    violations.append({
                        "parcel_id": p.parcel_id,
                        "status": p.status,
                        "error": "DELIVERED parcel is missing mandatory proof of delivery record",
                    })

        self._record_invariant(
            "INV-POD-01",
            domain,
            "Delivered parcels hold valid proof metadata, private object references, and unique parcel binding",
            len(violations) == 0,
            "Private proof of delivery integrity verified" if not violations else f"Found {len(violations)} proof of delivery violations",
            checked,
            len(violations),
            violations,
        )

    # -------------------------------------------------------------------------
    # 10. Point-in-Time & RPO Boundary Check
    # -------------------------------------------------------------------------
    def verify_point_in_time_boundary(self):
        domain = DomainName.RPO_BOUNDARY
        cutoff = parse_iso(self.metadata.snapshot_cutoff)
        violations = []
        checked = 0

        if not cutoff:
            self._record_invariant(
                "INV-RPO-01",
                domain,
                "Snapshot cutoff timestamp parsing and consistency",
                False,
                f"Invalid or missing snapshot cutoff: {self.metadata.snapshot_cutoff}",
                0,
                1,
            )
            return

        # Check all parcels: created_at must not be after snapshot cutoff in restored target
        for p in self.snapshot.parcels:
            checked += 1
            cat = parse_iso(p.created_at)
            if cat and cat > cutoff:
                violations.append({
                    "parcel_id": p.parcel_id,
                    "created_at": p.created_at,
                    "cutoff": self.metadata.snapshot_cutoff,
                    "error": "Record created after snapshot cutoff present in restored database",
                })

        # Check outbox events: created_at must not be after cutoff
        for e in self.snapshot.outbox_events:
            checked += 1
            cat = parse_iso(e.created_at)
            if cat and cat > cutoff:
                violations.append({
                    "event_id": e.event_id,
                    "created_at": e.created_at,
                    "cutoff": self.metadata.snapshot_cutoff,
                    "error": "Outbox event created after snapshot cutoff present in restored database",
                })

        # Pre/Post baseline comparison if baseline provided
        missing_pre_checkpoint = []
        if self.baseline:
            post_parcel_ids = {p.parcel_id for p in self.snapshot.parcels}
            for bp in self.baseline.parcels:
                b_cat = parse_iso(bp.created_at)
                if b_cat and b_cat <= cutoff:
                    # Pre-checkpoint record MUST survive restore
                    if bp.parcel_id not in post_parcel_ids:
                        missing_pre_checkpoint.append({
                            "parcel_id": bp.parcel_id,
                            "created_at": bp.created_at,
                            "error": "Pre-checkpoint canonical parcel missing from restored target",
                        })

        all_violations = violations + missing_pre_checkpoint
        self._record_invariant(
            "INV-RPO-01",
            domain,
            "Restored records strictly respect the snapshot cutoff boundary and preserve pre-checkpoint data",
            len(all_violations) == 0,
            "Point-in-time recovery boundary respected" if not all_violations else f"Found {len(all_violations)} point-in-time boundary violations",
            checked,
            len(all_violations),
            all_violations,
        )

    # -------------------------------------------------------------------------
    # Master Execution
    # -------------------------------------------------------------------------
    def run_all(self) -> VerificationReport:
        self.invariants.clear()
        self.domains.clear()

        # Run all domain verification methods
        self.verify_accounts_and_identity()
        self.verify_pricing_and_quotes()
        self.verify_delivery_booking_provenance()
        self.verify_parcel_status_history()
        self.verify_courier_assignments()
        self.verify_delivery_outbox()
        self.verify_communications_inbox()
        self.verify_durable_jobs()
        self.verify_private_proofs()
        self.verify_point_in_time_boundary()

        # Aggregate domain results
        for inv in self.invariants:
            d_name = inv.domain.value
            if d_name not in self.domains:
                self.domains[d_name] = DomainCheckResult(domain=inv.domain, status=VerificationStatus.PASS)

            dom = self.domains[d_name]
            if inv.status == VerificationStatus.PASS:
                dom.invariants_passed += 1
            elif inv.status == VerificationStatus.FAIL:
                dom.invariants_failed += 1
                dom.status = VerificationStatus.FAIL
            elif inv.status == VerificationStatus.BLOCKED:
                dom.invariants_blocked += 1
                if dom.status != VerificationStatus.FAIL:
                    dom.status = VerificationStatus.BLOCKED

        # Calculate overall status
        failed_count = sum(1 for inv in self.invariants if inv.status == VerificationStatus.FAIL)
        blocked_count = sum(1 for inv in self.invariants if inv.status == VerificationStatus.BLOCKED)

        if failed_count > 0:
            overall = VerificationStatus.FAIL
        elif blocked_count > 0:
            overall = VerificationStatus.BLOCKED
        else:
            overall = VerificationStatus.PASS

        # Record unverified items and recommendations
        if self.mode == "offline":
            self.unverified_items.append("Live operator-restored database targets (running in Mode A offline)")
            self.unverified_items.append("Live Kafka broker consumer offset commit state")
            self.recommendations.append("Execute Mode B when operator @Suthankan1 provides isolated database targets and checkpoint authorization.")
        elif self.mode == "operator-checkpoint":
            if self.metadata.budget_approval_status != "APPROVED":
                self.unverified_items.append("Formal RPO/RTO release budget approval (pending team ADR decision)")
                self.recommendations.append("Obtain formal team ADR approval for 300s RPO / 240s RTO before production promotion.")

        report = VerificationReport(
            report_id=f"rep-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}",
            generated_at=datetime.now(timezone.utc).isoformat(),
            verification_mode=self.mode,
            overall_status=overall,
            metadata=self.metadata,
            domains=self.domains,
            invariants=self.invariants,
            unverified_items=self.unverified_items,
            recommendations=self.recommendations,
        )
        return report
