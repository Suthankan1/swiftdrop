#!/usr/bin/env python3
"""
SwiftDrop Abort & Cancellation Actor Matrix Regressions (Issue #140 / October 23 Amendment)
============================================================================================
Validates authoritative lifecycle abort & cancellation permissions across actor classes
(CUSTOMER, MERCHANT, COURIER, DISPATCHER, ADMIN) and lifecycle states (CREATED through FAILED).
Enforces terminal immutability, reason code constraints, tenant boundary isolation,
and optimistic version guards per docs/architecture/RUNTIME_DECISION_REGISTER.md.
"""

import unittest
import uuid
from typing import Dict, List, Optional, Tuple


class StatusUpdateResult:
    def __init__(self, status_code: int, code: str, previous_status: Optional[str] = None, current_status: Optional[str] = None, version: int = 1):
        self.status_code = status_code
        self.code = code
        self.previous_status = previous_status
        self.current_status = current_status
        self.version = version


class ParcelStateModel:
    def __init__(
        self,
        parcel_id: str,
        current_status: str,
        version: int,
        sender_sub: str,
        merchant_id: Optional[str] = None,
        assigned_courier_sub: Optional[str] = None,
    ):
        self.parcel_id = parcel_id
        self.current_status = current_status
        self.version = version
        self.sender_sub = sender_sub
        self.merchant_id = merchant_id
        self.assigned_courier_sub = assigned_courier_sub


class AbortPolicyEngine:
    """
    Authoritative state transition guard engine conforming to SwiftDrop v1.3 baseline
    and runtime decision DEC-DEL-01 (Pre-Pickup Failure vs Cancellation).
    """

    TERMINAL_STATES = {"DELIVERED", "FAILED"}

    VALID_FORWARD_TRANSITIONS = {
        "CREATED": {"PICKED_UP", "FAILED"},
        "PICKED_UP": {"IN_TRANSIT", "FAILED"},
        "IN_TRANSIT": {"OUT_FOR_DELIVERY", "FAILED"},
        "OUT_FOR_DELIVERY": {"DELIVERED", "FAILED"},
        "DELIVERED": set(),
        "FAILED": set(),
    }

    ALLOWED_REASONS = {
        "CREATED": {"CANCELLED_BY_SENDER", "PICKUP_FAILED_SENDER_ABSENT"},
        "PICKED_UP": {"PARCEL_DAMAGED", "FORCE_MAJEURE"},
        "IN_TRANSIT": {"PARCEL_DAMAGED", "FORCE_MAJEURE"},
        "OUT_FOR_DELIVERY": {"RECIPIENT_UNAVAILABLE", "INCORRECT_ADDRESS", "REFUSED_BY_RECIPIENT"},
    }

    def attempt_transition(
        self,
        parcel: ParcelStateModel,
        actor_sub: str,
        actor_roles: List[str],
        actor_merchant_id: Optional[str],
        new_status: str,
        expected_version: int,
        reason: Optional[str] = None,
    ) -> StatusUpdateResult:
        # 1. Terminal immutability
        if parcel.current_status in self.TERMINAL_STATES:
            return StatusUpdateResult(409, "TERMINAL_STATE_IMMUTABLE", parcel.current_status, parcel.current_status, parcel.version)

        # 2. Version guard
        if expected_version != parcel.version:
            return StatusUpdateResult(409, "VERSION_CONFLICT", parcel.current_status, parcel.current_status, parcel.version)

        # 3. Transition graph validation
        allowed_targets = self.VALID_FORWARD_TRANSITIONS.get(parcel.current_status, set())
        if new_status not in allowed_targets:
            return StatusUpdateResult(422, "ILLEGAL_STATE_TRANSITION", parcel.current_status, parcel.current_status, parcel.version)

        # 4. Abort / Failure specific actor matrix
        if new_status == "FAILED":
            # Reason code check
            valid_reasons = self.ALLOWED_REASONS.get(parcel.current_status, set())
            if not reason or reason not in valid_reasons:
                return StatusUpdateResult(400, "INVALID_FAILURE_REASON", parcel.current_status, parcel.current_status, parcel.version)

            if parcel.current_status == "CREATED":
                # Only unassigned sender (CUSTOMER/MERCHANT) or DISPATCHER/ADMIN may cancel/abort
                if "ADMIN" in actor_roles or "DISPATCHER" in actor_roles:
                    # Allowed
                    pass
                elif "CUSTOMER" in actor_roles:
                    # Must be the exact creator and no courier assigned
                    if actor_sub != parcel.sender_sub or parcel.assigned_courier_sub is not None:
                        return StatusUpdateResult(403, "FORBIDDEN", parcel.current_status, parcel.current_status, parcel.version)
                elif "MERCHANT" in actor_roles:
                    # Must be the owning merchant tenant and no courier assigned
                    if not actor_merchant_id or actor_merchant_id != parcel.merchant_id or parcel.assigned_courier_sub is not None:
                        return StatusUpdateResult(403, "FORBIDDEN", parcel.current_status, parcel.current_status, parcel.version)
                else:
                    return StatusUpdateResult(403, "FORBIDDEN", parcel.current_status, parcel.current_status, parcel.version)

            elif parcel.current_status in ("PICKED_UP", "IN_TRANSIT"):
                # Senders can NO LONGER abort after physical pickup
                if "CUSTOMER" in actor_roles or "MERCHANT" in actor_roles:
                    return StatusUpdateResult(403, "CANNOT_ABORT_AFTER_PICKUP", parcel.current_status, parcel.current_status, parcel.version)

                # Must be assigned courier or DISPATCHER/ADMIN
                if "ADMIN" in actor_roles or "DISPATCHER" in actor_roles:
                    pass
                elif "COURIER" in actor_roles:
                    if actor_sub != parcel.assigned_courier_sub:
                        return StatusUpdateResult(403, "NOT_ASSIGNED_COURIER", parcel.current_status, parcel.current_status, parcel.version)
                else:
                    return StatusUpdateResult(403, "FORBIDDEN", parcel.current_status, parcel.current_status, parcel.version)

            elif parcel.current_status == "OUT_FOR_DELIVERY":
                if "COURIER" in actor_roles:
                    if actor_sub != parcel.assigned_courier_sub:
                        return StatusUpdateResult(403, "NOT_ASSIGNED_COURIER", parcel.current_status, parcel.current_status, parcel.version)
                elif "ADMIN" not in actor_roles and "DISPATCHER" not in actor_roles:
                    return StatusUpdateResult(403, "FORBIDDEN", parcel.current_status, parcel.current_status, parcel.version)

        # Transition succeeds
        old = parcel.current_status
        parcel.current_status = new_status
        parcel.version += 1
        return StatusUpdateResult(200, "SUCCESS", old, parcel.current_status, parcel.version)


class AbortActorMatrixRegressionTest(unittest.TestCase):
    """
    Exhaustive regression test suite verifying the complete Abort & Cancellation Actor Matrix.
    """

    def setUp(self):
        self.engine = AbortPolicyEngine()
        self.sender_sub = "cust-alice"
        self.other_customer_sub = "cust-eve"
        self.merchant_id = str(uuid.uuid4())
        self.other_merchant_id = str(uuid.uuid4())
        self.courier_sub = "courier-kamal"
        self.other_courier_sub = "courier-nimal"
        self.dispatcher_sub = "dispatcher-01"
        self.admin_sub = "admin-root"

    def test_unassigned_created_parcel_cancellation_by_sender_customer(self):
        """Customer who created the parcel can cancel unassigned CREATED parcel with CANCELLED_BY_SENDER."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "CREATED", 1, self.sender_sub)
        res = self.engine.attempt_transition(
            parcel, self.sender_sub, ["CUSTOMER"], None, "FAILED", 1, "CANCELLED_BY_SENDER"
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.current_status, "FAILED")
        self.assertEqual(res.version, 2)

    def test_unassigned_created_parcel_cancellation_denied_to_other_customer(self):
        """Different customer cannot cancel another customer's parcel."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "CREATED", 1, self.sender_sub)
        res = self.engine.attempt_transition(
            parcel, self.other_customer_sub, ["CUSTOMER"], None, "FAILED", 1, "CANCELLED_BY_SENDER"
        )
        self.assertEqual(res.status_code, 403)
        self.assertEqual(parcel.current_status, "CREATED")
        self.assertEqual(parcel.version, 1)

    def test_unassigned_created_parcel_cancellation_by_merchant_owner(self):
        """Owning merchant tenant can cancel unassigned CREATED parcel."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "CREATED", 1, "merchant-caller", self.merchant_id)
        res = self.engine.attempt_transition(
            parcel, "merchant-caller", ["MERCHANT"], self.merchant_id, "FAILED", 1, "CANCELLED_BY_SENDER"
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.current_status, "FAILED")

    def test_unassigned_created_parcel_cancellation_denied_to_cross_tenant_merchant(self):
        """Cross-tenant merchant cannot cancel another tenant's parcel."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "CREATED", 1, "merchant-caller", self.merchant_id)
        res = self.engine.attempt_transition(
            parcel, "other-caller", ["MERCHANT"], self.other_merchant_id, "FAILED", 1, "CANCELLED_BY_SENDER"
        )
        self.assertEqual(res.status_code, 403)
        self.assertEqual(parcel.current_status, "CREATED")

    def test_unassigned_created_parcel_abort_by_dispatcher(self):
        """Dispatcher can abort unassigned CREATED parcel with PICKUP_FAILED_SENDER_ABSENT."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "CREATED", 1, self.sender_sub)
        res = self.engine.attempt_transition(
            parcel, self.dispatcher_sub, ["DISPATCHER"], None, "FAILED", 1, "PICKUP_FAILED_SENDER_ABSENT"
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.current_status, "FAILED")

    def test_courier_cannot_abort_unassigned_created_parcel(self):
        """Courier has no permission to abort an unassigned CREATED parcel."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "CREATED", 1, self.sender_sub)
        res = self.engine.attempt_transition(
            parcel, self.courier_sub, ["COURIER"], None, "FAILED", 1, "CANCELLED_BY_SENDER"
        )
        self.assertEqual(res.status_code, 403)
        self.assertEqual(parcel.current_status, "CREATED")

    def test_customer_cannot_abort_after_pickup(self):
        """Customer cannot cancel once parcel is in PICKED_UP state."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "PICKED_UP", 2, self.sender_sub, assigned_courier_sub=self.courier_sub)
        res = self.engine.attempt_transition(
            parcel, self.sender_sub, ["CUSTOMER"], None, "FAILED", 2, "CANCELLED_BY_SENDER"
        )
        self.assertEqual(res.status_code, 400)  # CANCELLED_BY_SENDER invalid for PICKED_UP
        # Even with valid transit failure reason, customer role is forbidden
        res2 = self.engine.attempt_transition(
            parcel, self.sender_sub, ["CUSTOMER"], None, "FAILED", 2, "PARCEL_DAMAGED"
        )
        self.assertEqual(res2.status_code, 403)
        self.assertEqual(parcel.current_status, "PICKED_UP")

    def test_assigned_courier_can_fail_picked_up_parcel(self):
        """Assigned courier can mark PICKED_UP parcel as FAILED with PARCEL_DAMAGED."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "PICKED_UP", 2, self.sender_sub, assigned_courier_sub=self.courier_sub)
        res = self.engine.attempt_transition(
            parcel, self.courier_sub, ["COURIER"], None, "FAILED", 2, "PARCEL_DAMAGED"
        )
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.current_status, "FAILED")

    def test_unassigned_courier_cannot_fail_another_couriers_picked_up_parcel(self):
        """Courier who is not assigned to the parcel cannot fail it."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "PICKED_UP", 2, self.sender_sub, assigned_courier_sub=self.courier_sub)
        res = self.engine.attempt_transition(
            parcel, self.other_courier_sub, ["COURIER"], None, "FAILED", 2, "PARCEL_DAMAGED"
        )
        self.assertEqual(res.status_code, 403)
        self.assertEqual(parcel.current_status, "PICKED_UP")

    def test_terminal_delivered_state_is_immutable_to_all_actors(self):
        """No actor (even Admin) can abort or alter a DELIVERED parcel."""
        for role, sub in [("CUSTOMER", self.sender_sub), ("COURIER", self.courier_sub), ("DISPATCHER", self.dispatcher_sub), ("ADMIN", self.admin_sub)]:
            parcel = ParcelStateModel(str(uuid.uuid4()), "DELIVERED", 5, self.sender_sub, assigned_courier_sub=self.courier_sub)
            res = self.engine.attempt_transition(
                parcel, sub, [role], None, "FAILED", 5, "PARCEL_DAMAGED"
            )
            self.assertEqual(res.status_code, 409)
            self.assertEqual(res.code, "TERMINAL_STATE_IMMUTABLE")
            self.assertEqual(parcel.current_status, "DELIVERED")

    def test_terminal_failed_state_is_immutable_to_all_actors(self):
        """No actor can mutate a FAILED parcel."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "FAILED", 3, self.sender_sub)
        res = self.engine.attempt_transition(
            parcel, self.admin_sub, ["ADMIN"], None, "DELIVERED", 3
        )
        self.assertEqual(res.status_code, 409)
        self.assertEqual(res.code, "TERMINAL_STATE_IMMUTABLE")

    def test_version_conflict_aborts_transition_without_state_change(self):
        """Stale expectedVersion aborts transition with 409 VERSION_CONFLICT."""
        parcel = ParcelStateModel(str(uuid.uuid4()), "CREATED", 3, self.sender_sub)
        res = self.engine.attempt_transition(
            parcel, self.sender_sub, ["CUSTOMER"], None, "FAILED", 2, "CANCELLED_BY_SENDER"
        )
        self.assertEqual(res.status_code, 409)
        self.assertEqual(res.code, "VERSION_CONFLICT")
        self.assertEqual(parcel.current_status, "CREATED")
        self.assertEqual(parcel.version, 3)


if __name__ == "__main__":
    unittest.main()
