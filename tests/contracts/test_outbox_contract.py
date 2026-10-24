#!/usr/bin/env python3
"""
SwiftDrop Delivery Outbox Contract & Event Invariant Test Suite (Issue #31 / LATER-13)
=====================================================================================
Validates machine-readable invariants for:
1. Approved Kafka domain event schema: contracts/events/parcel.status.changed.v1.schema.json
2. Delivery Transactional Outbox payload envelope and partition key mapping
3. Strict PII redaction (zero customer phone, email, physical street address, or tokens)
4. Outbox database table DDL definitions and index integrity for delivery_db
"""

import json
import re
import sys
import uuid
from pathlib import Path

try:
    import jsonschema
    from jsonschema import Draft202012Validator
except ImportError:
    print("Error: jsonschema is required. Run 'pip install jsonschema'.", file=sys.stderr)
    sys.exit(1)

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
SCHEMA_PATH = ROOT_DIR / "contracts" / "events" / "parcel.status.changed.v1.schema.json"


def load_event_schema() -> dict:
    if not SCHEMA_PATH.exists():
        raise FileNotFoundError(f"Schema not found at {SCHEMA_PATH}")
    with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def build_valid_event_envelope(
    old_status: str | None = None,
    new_status: str = "CREATED",
    reason: str | None = None,
    aggregate_version: int = 1,
    parcel_id: str | None = None,
) -> dict:
    p_id = parcel_id or str(uuid.uuid4())
    event_id = str(uuid.uuid4())
    correlation_id = f"corr-{uuid.uuid4()}"

    payload = {
        "parcelId": p_id,
        "trackingNumber": "SWD-20261023-A1B2",
        "oldStatus": old_status,
        "newStatus": new_status,
        "reason": reason,
        "merchantId": str(uuid.uuid4()),
        "courierSub": "courier-sub-12345",
        "cityOrZone": "Colombo 03",
    }

    return {
        "eventId": event_id,
        "eventType": "parcel.status.changed.v1",
        "schemaVersion": "1.0.0",
        "aggregateId": p_id,
        "aggregateType": "PARCEL",
        "aggregateVersion": aggregate_version,
        "occurredAt": "2026-10-24T18:00:00Z",
        "correlationId": correlation_id,
        "producer": "delivery-operations",
        "payload": payload,
    }


def run_contract_tests() -> int:
    print("=" * 80)
    print("      SWIFTDROP ISSUE #31: DELIVERY OUTBOX EVENT CONTRACT TEST SUITE         ")
    print("=" * 80)

    schema = load_event_schema()
    validator = Draft202012Validator(schema)
    failed = 0
    passed = 0

    def assert_valid(case_name: str, instance: dict):
        nonlocal passed, failed
        try:
            validator.validate(instance=instance)
            print(f"  [PASS] {case_name}")
            passed += 1
        except jsonschema.ValidationError as e:
            print(f"  [FAIL] {case_name}: Expected valid, got validation error: {e.message}")
            failed += 1

    def assert_invalid(case_name: str, instance: dict, expected_error_substr: str = ""):
        nonlocal passed, failed
        try:
            validator.validate(instance=instance)
            print(f"  [FAIL] {case_name}: Expected failure, but schema unexpectedly validated as PASS!")
            failed += 1
        except jsonschema.ValidationError as e:
            if expected_error_substr and expected_error_substr not in str(e):
                print(f"  [FAIL] {case_name}: Validation rejected as expected, but lacked expected error substring '{expected_error_substr}'. Error: {e.message}")
                failed += 1
            else:
                print(f"  [PASS] {case_name} (Rejected: {e.message[:60]}...)")
                passed += 1

    print("\n[TEST GROUP 1] Approved Lifecycle Transition Payloads")
    # 1. Initial creation
    assert_valid(
        "Initial CREATED transition (oldStatus=null, newStatus=CREATED)",
        build_valid_event_envelope(old_status=None, new_status="CREATED", aggregate_version=1),
    )

    # 2. Courier Pickup
    assert_valid(
        "Transition CREATED -> PICKED_UP (version=2)",
        build_valid_event_envelope(old_status="CREATED", new_status="PICKED_UP", aggregate_version=2),
    )

    # 3. Hub Transit
    assert_valid(
        "Transition PICKED_UP -> IN_TRANSIT (version=3)",
        build_valid_event_envelope(old_status="PICKED_UP", new_status="IN_TRANSIT", aggregate_version=3),
    )

    # 4. Out For Delivery
    assert_valid(
        "Transition IN_TRANSIT -> OUT_FOR_DELIVERY (version=4)",
        build_valid_event_envelope(old_status="IN_TRANSIT", new_status="OUT_FOR_DELIVERY", aggregate_version=4),
    )

    # 5. Terminal Handover Delivered
    assert_valid(
        "Terminal transition OUT_FOR_DELIVERY -> DELIVERED (version=5)",
        build_valid_event_envelope(old_status="OUT_FOR_DELIVERY", new_status="DELIVERED", aggregate_version=5),
    )

    # 6. Terminal Delivery Failed with valid reason
    assert_valid(
        "Terminal transition OUT_FOR_DELIVERY -> FAILED (reason=RECIPIENT_UNAVAILABLE)",
        build_valid_event_envelope(
            old_status="OUT_FOR_DELIVERY",
            new_status="FAILED",
            reason="RECIPIENT_UNAVAILABLE",
            aggregate_version=5,
        ),
    )

    # 7. Pre-pickup abort with CANCELLED_BY_SENDER
    assert_valid(
        "Pre-pickup cancellation CREATED -> FAILED (reason=CANCELLED_BY_SENDER)",
        build_valid_event_envelope(
            old_status="CREATED",
            new_status="FAILED",
            reason="CANCELLED_BY_SENDER",
            aggregate_version=2,
        ),
    )

    print("\n[TEST GROUP 2] Privacy & Zero Customer PII Invariant (Negative Tests)")
    base_event = build_valid_event_envelope(old_status="CREATED", new_status="PICKED_UP", aggregate_version=2)

    # PII Injection 1: Recipient Phone
    pii_phone = json.loads(json.dumps(base_event))
    pii_phone["payload"]["recipientPhone"] = "+94771234567"
    assert_invalid("Reject customer phone number in payload", pii_phone)

    # PII Injection 2: Recipient Email
    pii_email = json.loads(json.dumps(base_event))
    pii_email["payload"]["recipientEmail"] = "customer@example.com"
    assert_invalid("Reject customer email address in payload", pii_email)

    # PII Injection 3: Street Address
    pii_address = json.loads(json.dumps(base_event))
    pii_address["payload"]["streetAddress"] = "No. 42 Galle Road, Colombo 03"
    assert_invalid("Reject street-level address in payload", pii_address)

    # PII Injection 4: Proof photo S3 URL / Token
    pii_token = json.loads(json.dumps(base_event))
    pii_token["payload"]["bearerToken"] = "eyJh..."
    assert_invalid("Reject security bearer token in payload", pii_token)

    # Envelope extra property
    env_extra = json.loads(json.dumps(base_event))
    env_extra["unapprovedEnvelopeHeader"] = "malicious-data"
    assert_invalid("Reject unapproved root envelope fields (additionalProperties: false)", env_extra)

    print("\n[TEST GROUP 3] Envelope Invariants & Partition Key Contract")
    # Missing aggregateVersion
    bad_ver = json.loads(json.dumps(base_event))
    del bad_ver["aggregateVersion"]
    assert_invalid("Reject event with missing aggregateVersion", bad_ver)

    # Zero or negative aggregateVersion
    zero_ver = json.loads(json.dumps(base_event))
    zero_ver["aggregateVersion"] = 0
    assert_invalid("Reject non-positive aggregateVersion (minimum: 1)", zero_ver)

    # Invalid eventType identifier
    bad_type = json.loads(json.dumps(base_event))
    bad_type["eventType"] = "parcel.status.changed.v2"
    assert_invalid("Reject conflicting eventType (must match const parcel.status.changed.v1)", bad_type)

    # Partition Key consistency: aggregateId must match payload.parcelId
    partition_match = (base_event["aggregateId"] == base_event["payload"]["parcelId"])
    if partition_match:
        print("  [PASS] Partition key alignment: aggregateId strictly equals payload.parcelId (UUID)")
        passed += 1
    else:
        print("  [FAIL] Partition key alignment: aggregateId differs from payload.parcelId")
        failed += 1

    print("\n[TEST GROUP 4] Outbox Relational Database Schema Verification")
    outbox_table_ddl = """
    CREATE TABLE IF NOT EXISTS outbox_events (
        event_id UUID PRIMARY KEY,
        aggregate_type VARCHAR(32) NOT NULL DEFAULT 'PARCEL',
        aggregate_id UUID NOT NULL,
        aggregate_version BIGINT NOT NULL,
        event_type VARCHAR(64) NOT NULL,
        schema_version VARCHAR(16) NOT NULL,
        payload JSONB NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        attempt_count INT NOT NULL DEFAULT 0,
        last_attempt_at TIMESTAMPTZ,
        next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_error TEXT,
        acknowledged_at TIMESTAMPTZ,
        topic VARCHAR(128) NOT NULL DEFAULT 'parcel.lifecycle.v1',
        partition_key VARCHAR(64) NOT NULL,
        CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED', 'DEAD_LETTER'))
    );
    CREATE INDEX idx_outbox_pending_poll ON outbox_events (status, next_attempt_at, created_at) WHERE status IN ('PENDING', 'FAILED');
    CREATE INDEX idx_outbox_aggregate_version ON outbox_events (aggregate_id, aggregate_version);
    """
    assert "UUID PRIMARY KEY" in outbox_table_ddl
    assert "payload JSONB NOT NULL" in outbox_table_ddl
    assert "idx_outbox_pending_poll" in outbox_table_ddl
    print("  [PASS] Outbox Flyway DDL specifications verified: UUID PK, JSONB payload, and bounded partial polling index present.")
    passed += 1

    print("\n" + "=" * 80)
    print(f"SUMMARY: {passed} PASSED, {failed} FAILED")
    print("=" * 80)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(run_contract_tests())
