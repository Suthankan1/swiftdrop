#!/usr/bin/env python3
"""
SwiftDrop Kafka Event Contract Regressions (Issue #140 / N10-QA-01)
===================================================================
Validates Kafka domain event JSON Schema 2020-12, envelope and payload invariants,
PII leakage protections, positive lifecycle fixtures, additive compatibility policy,
and negative invalid payload rejections.
"""

import copy
from datetime import datetime
import json
import unittest
import uuid
from pathlib import Path

import jsonschema
from jsonschema import Draft202012Validator

from tests.contracts.validate_contracts import (
    CONTRACTS_DIR,
    ROOT_DIR,
    ContractValidationError,
    validate_kafka_event_schemas,
    check_kafka_compatibility,
)


class KafkaEventContractRegressionTest(unittest.TestCase):
    """Regression tests for SwiftDrop Kafka domain events."""

    @classmethod
    def setUpClass(cls):
        cls.schema_path = CONTRACTS_DIR / "events" / "parcel.status.changed.v1.schema.json"
        cls.example_path = CONTRACTS_DIR / "events" / "parcel.status.changed.v1.example.json"
        cls.schema = json.loads(cls.schema_path.read_text(encoding="utf-8"))
        cls.example = json.loads(cls.example_path.read_text(encoding="utf-8"))

        fc = jsonschema.FormatChecker()

        @fc.checks("date-time")
        def check_datetime(val):
            if not isinstance(val, str):
                return True
            try:
                datetime.fromisoformat(val.replace("Z", "+00:00"))
                return True
            except Exception:
                return False

        cls.validator = Draft202012Validator(cls.schema, format_checker=fc)

    def build_event(
        self,
        old_status: str | None = None,
        new_status: str = "CREATED",
        aggregate_version: int = 1,
        reason: str | None = None,
        merchant_id: str | None = None,
        courier_sub: str | None = None,
        city_or_zone: str | None = "Colombo 03",
    ) -> dict:
        parcel_id = str(uuid.uuid4())
        return {
            "eventId": str(uuid.uuid4()),
            "eventType": "parcel.status.changed.v1",
            "schemaVersion": "1.0.0",
            "aggregateId": parcel_id,
            "aggregateType": "PARCEL",
            "aggregateVersion": aggregate_version,
            "occurredAt": "2026-10-28T10:15:30Z",
            "correlationId": f"corr-{uuid.uuid4()}",
            "producer": "delivery-operations",
            "payload": {
                "parcelId": parcel_id,
                "trackingNumber": "SWD-20261028-ABCD1234",
                "oldStatus": old_status,
                "newStatus": new_status,
                "reason": reason,
                "merchantId": merchant_id,
                "courierSub": courier_sub,
                "cityOrZone": city_or_zone,
            },
        }

    def test_example_event_passes_schema_validation(self):
        """Committed example event matches the official JSON schema."""
        self.validator.validate(instance=self.example)

    def test_schema_invariants_and_pii_guards(self):
        """Schema enforces additionalProperties: false on envelope and payload to guard PII."""
        self.assertFalse(self.schema.get("additionalProperties", True))
        payload_props = self.schema.get("properties", {}).get("payload", {})
        self.assertFalse(payload_props.get("additionalProperties", True))
        self.assertEqual(self.schema["properties"]["producer"]["const"], "delivery-operations")
        self.assertEqual(self.schema["properties"]["aggregateType"]["const"], "PARCEL")
        self.assertEqual(self.schema["properties"]["eventType"]["const"], "parcel.status.changed.v1")

    def test_positive_fixtures_for_all_valid_lifecycle_transitions(self):
        """Valid domain events for all lifecycle transitions pass schema validation."""
        transitions = [
            (None, "CREATED", 1, None),
            ("CREATED", "PICKED_UP", 2, None),
            ("PICKED_UP", "IN_TRANSIT", 3, None),
            ("IN_TRANSIT", "OUT_FOR_DELIVERY", 4, None),
            ("OUT_FOR_DELIVERY", "DELIVERED", 5, None),
            # FAILED transitions with approved reasons
            ("CREATED", "FAILED", 2, "CANCELLED_BY_SENDER"),
            ("CREATED", "FAILED", 2, "PICKUP_FAILED_SENDER_ABSENT"),
            ("PICKED_UP", "FAILED", 3, "PARCEL_DAMAGED"),
            ("PICKED_UP", "FAILED", 3, "FORCE_MAJEURE"),
            ("OUT_FOR_DELIVERY", "FAILED", 5, "RECIPIENT_UNAVAILABLE"),
            ("OUT_FOR_DELIVERY", "FAILED", 5, "INCORRECT_ADDRESS"),
            ("OUT_FOR_DELIVERY", "FAILED", 5, "REFUSED_BY_RECIPIENT"),
        ]

        for old_st, new_st, ver, rsn in transitions:
            event = self.build_event(old_status=old_st, new_status=new_st, aggregate_version=ver, reason=rsn)
            try:
                self.validator.validate(instance=event)
            except jsonschema.ValidationError as e:
                self.fail(f"Valid transition {old_st} -> {new_st} failed validation: {e.message}")

    def test_negative_fixture_missing_required_envelope_fields(self):
        """Rejects events missing mandatory envelope fields."""
        required = self.schema["required"]
        event = self.build_event()
        for field in required:
            bad_event = copy.deepcopy(event)
            del bad_event[field]
            with self.assertRaises(jsonschema.ValidationError, msg=f"Should reject missing {field}"):
                self.validator.validate(instance=bad_event)

    def test_negative_fixture_invalid_uuid_aggregate_id(self):
        """Rejects non-UUID aggregateId."""
        bad_event = self.build_event()
        bad_event["aggregateId"] = "not-a-valid-uuid"
        with self.assertRaises(jsonschema.ValidationError):
            self.validator.validate(instance=bad_event)

    def test_negative_fixture_non_positive_aggregate_version(self):
        """Rejects aggregateVersion < 1."""
        bad_event = self.build_event(aggregate_version=0)
        with self.assertRaises(jsonschema.ValidationError):
            self.validator.validate(instance=bad_event)

    def test_negative_fixture_invalid_timestamp(self):
        """Rejects non-datetime formatted occurredAt."""
        bad_event = self.build_event()
        bad_event["occurredAt"] = "Wednesday afternoon"
        with self.assertRaises(jsonschema.ValidationError):
            self.validator.validate(instance=bad_event)

    def test_negative_fixture_invalid_status_enum(self):
        """Rejects unknown status enum value."""
        bad_event = self.build_event(new_status="DISAPPEARED_IN_TRANSIT")
        with self.assertRaises(jsonschema.ValidationError):
            self.validator.validate(instance=bad_event)

    def test_negative_fixture_pii_injection_rejected(self):
        """Rejects unauthorized private customer data in payload (phone, street address, email)."""
        pii_injections = [
            {"recipientPhone": "+94771234567"},
            {"recipientEmail": "customer@gmail.com"},
            {"streetAddress": "No 12, Galle Road, Colombo 03"},
            {"customerCreditCard": "4111-2222-3333-4444"},
        ]
        for pii in pii_injections:
            bad_event = self.build_event()
            bad_event["payload"].update(pii)
            with self.assertRaises(jsonschema.ValidationError, msg=f"Should reject PII: {pii}"):
                self.validator.validate(instance=bad_event)

    def test_additive_compatibility_under_approved_policy(self):
        """
        Demonstrates approved additive compatibility policy:
        In v1.0.0, additionalProperties is false to strictly prevent customer data leaks.
        An evolved schema v1.1.0 can accept additive optional properties (e.g. hubCode)
        without breaking existing consumers.
        """
        # Create an evolved v1.1.0 schema adding optional 'hubCode'
        evolved_schema = copy.deepcopy(self.schema)
        evolved_schema["properties"]["payload"]["properties"]["hubCode"] = {
            "type": ["string", "null"],
            "description": "Optional regional hub identifier",
        }
        evolved_validator = Draft202012Validator(evolved_schema, format_checker=Draft202012Validator.FORMAT_CHECKER)

        # 1. Existing v1.0 event is valid in evolved v1.1 schema (backward compatible)
        base_event = self.build_event()
        evolved_validator.validate(instance=base_event)

        # 2. Additive event with hubCode is valid in evolved schema
        additive_event = copy.deepcopy(base_event)
        additive_event["schemaVersion"] = "1.1.0"
        additive_event["payload"]["hubCode"] = "HUB-CMB-NORTH"
        evolved_validator.validate(instance=additive_event)

        # 3. But unapproved additive property on strict v1.0 schema is rejected (fails closed)
        with self.assertRaises(jsonschema.ValidationError):
            self.validator.validate(instance=additive_event)


if __name__ == "__main__":
    unittest.main()
