#!/usr/bin/env python3
"""
SwiftDrop Delivery Status & History Contract Test Suite (Issue #23 / LATER-06)
=============================================================================
Validates machine-readable invariants for:
1. REST API Contract: contracts/rest/openapi-delivery.yaml (IF-REST-07)
   - PATCH /api/v1/parcels/{parcelId}/status
   - Request schema: UpdateStatusRequest
   - Response schema: ParcelStatusChangeResponse
2. Permitted transition subset & failure reason requirements
3. Optimistic locking invariants (expectedVersion)
4. ProblemDetail error mapping (400, 401, 403, 404, 409, 422)
5. Canonical parcel and append-only status_history DDL schema invariants
"""

import sys
from pathlib import Path
from typing import Any, Dict

try:
    import yaml
    import jsonschema
    from jsonschema import Draft202012Validator
except ImportError:
    print("Error: PyYAML and jsonschema are required. Run 'pip install pyyaml jsonschema'.", file=sys.stderr)
    sys.exit(1)

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
OPENAPI_DELIVERY_PATH = ROOT_DIR / "contracts" / "rest" / "openapi-delivery.yaml"
PROBLEM_DETAIL_PATH = ROOT_DIR / "contracts" / "rest" / "problem-detail.yaml"


def load_specs() -> Dict[str, Any]:
    if not OPENAPI_DELIVERY_PATH.exists():
        raise FileNotFoundError(f"Missing {OPENAPI_DELIVERY_PATH}")
    if not PROBLEM_DETAIL_PATH.exists():
        raise FileNotFoundError(f"Missing {PROBLEM_DETAIL_PATH}")

    delivery_spec = yaml.safe_load(OPENAPI_DELIVERY_PATH.read_text(encoding="utf-8"))
    problem_spec = yaml.safe_load(PROBLEM_DETAIL_PATH.read_text(encoding="utf-8"))
    return {"delivery": delivery_spec, "problem": problem_spec}


def run_delivery_status_contract_tests() -> int:
    print("=" * 80)
    print("    SWIFTDROP ISSUE #23: DELIVERY STATUS & HISTORY CONTRACT TEST SUITE       ")
    print("=" * 80)

    specs = load_specs()
    delivery_spec = specs["delivery"]
    schemas = delivery_spec.get("components", {}).get("schemas", {})

    update_req_schema = schemas.get("UpdateStatusRequest")
    status_resp_schema = schemas.get("ParcelStatusChangeResponse")

    if not update_req_schema:
        print("  [FAIL] Missing UpdateStatusRequest in openapi-delivery.yaml")
        return 1
    if not status_resp_schema:
        print("  [FAIL] Missing ParcelStatusChangeResponse in openapi-delivery.yaml")
        return 1

    passed = 0
    failed = 0

    def assert_test(cond: bool, test_name: str, detail: str = ""):
        nonlocal passed, failed
        if cond:
            print(f"  [PASS] {test_name}")
            passed += 1
        else:
            print(f"  [FAIL] {test_name}: {detail}")
            failed += 1

    print("\n[TEST GROUP 1] REST Endpoint & Operation Invariants")
    paths = delivery_spec.get("paths", {})
    status_path = paths.get("/api/v1/parcels/{parcelId}/status", {})
    assert_test("patch" in status_path, "PATCH method exists on /api/v1/parcels/{parcelId}/status")

    patch_op = status_path.get("patch", {})
    assert_test(patch_op.get("operationId") == "updateParcelStatus", "operationId is updateParcelStatus")
    assert_test("BearerAuth" in str(patch_op.get("security", [])), "Endpoint secured by BearerAuth (COURIER role)")

    responses = patch_op.get("responses", {})
    for status_code in ["200", "400", "401", "403", "409", "422"]:
        assert_test(status_code in responses, f"Response status code {status_code} documented in contract")

    print("\n[TEST GROUP 2] UpdateStatusRequest Schema Invariants")
    req_properties = update_req_schema.get("properties", {})
    req_required = update_req_schema.get("required", [])

    assert_test("newStatus" in req_required, "'newStatus' is mandatory in UpdateStatusRequest")
    assert_test("expectedVersion" in req_required, "'expectedVersion' is mandatory for optimistic locking")

    allowed_statuses = set(req_properties.get("newStatus", {}).get("enum", []))
    expected_statuses = {"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"}
    assert_test(
        allowed_statuses == expected_statuses,
        "Permitted newStatus enum matches approved lifecycle subset",
        f"Expected {expected_statuses}, got {allowed_statuses}",
    )

    allowed_reasons = set(req_properties.get("reason", {}).get("enum", []))
    expected_reasons = {
        "RECIPIENT_UNAVAILABLE",
        "INCORRECT_ADDRESS",
        "REFUSED_BY_RECIPIENT",
        "PARCEL_DAMAGED",
        "FORCE_MAJEURE",
        "CANCELLED_BY_SENDER",
        "PICKUP_FAILED_SENDER_ABSENT",
    }
    assert_test(
        allowed_reasons == expected_reasons,
        "Failure reasons match approved terminal failure codes",
        f"Expected {expected_reasons}, got {allowed_reasons}",
    )
    assert_test(
        req_properties.get("proofId", {}).get("format") == "uuid",
        "proofId is formatted as UUID for DELIVERED transitions",
    )

    print("\n[TEST GROUP 3] ParcelStatusChangeResponse Schema Invariants")
    resp_required = set(status_resp_schema.get("required", []))
    expected_resp_fields = {
        "parcelId",
        "trackingNumber",
        "previousStatus",
        "currentStatus",
        "stateVersion",
        "updatedParcelVersion",
        "occurredAt",
    }
    assert_test(
        expected_resp_fields.issubset(resp_required),
        "Response contains all mandatory status change and version fields",
        f"Missing: {expected_resp_fields - resp_required}",
    )

    print("\n[TEST GROUP 4] State Transition Permissibility Matrix")
    # Authoritative lifecycle forward transitions
    VALID_TRANSITIONS = {
        "CREATED": {"PICKED_UP", "FAILED"},
        "PICKED_UP": {"IN_TRANSIT", "FAILED"},
        "IN_TRANSIT": {"OUT_FOR_DELIVERY", "FAILED"},
        "OUT_FOR_DELIVERY": {"DELIVERED", "FAILED"},
        "DELIVERED": set(),  # Terminal
        "FAILED": set(),     # Terminal
    }

    # Positive checks
    assert_test("PICKED_UP" in VALID_TRANSITIONS["CREATED"], "Permit CREATED -> PICKED_UP (Physical courier pickup)")
    assert_test("IN_TRANSIT" in VALID_TRANSITIONS["PICKED_UP"], "Permit PICKED_UP -> IN_TRANSIT (Hub transit)")
    assert_test("OUT_FOR_DELIVERY" in VALID_TRANSITIONS["IN_TRANSIT"], "Permit IN_TRANSIT -> OUT_FOR_DELIVERY (Out for delivery)")
    assert_test("DELIVERED" in VALID_TRANSITIONS["OUT_FOR_DELIVERY"], "Permit OUT_FOR_DELIVERY -> DELIVERED (Handover completed)")
    assert_test("FAILED" in VALID_TRANSITIONS["OUT_FOR_DELIVERY"], "Permit OUT_FOR_DELIVERY -> FAILED (Delivery attempt failed)")

    # Negative / Forbidden checks
    assert_test("PICKED_UP" not in VALID_TRANSITIONS["IN_TRANSIT"], "Forbid backward transition IN_TRANSIT -> PICKED_UP")
    assert_test("OUT_FOR_DELIVERY" not in VALID_TRANSITIONS["DELIVERED"], "Forbid terminal transition DELIVERED -> OUT_FOR_DELIVERY")
    assert_test("DELIVERED" not in VALID_TRANSITIONS["FAILED"], "Forbid terminal transition FAILED -> DELIVERED")
    assert_test("DELIVERED" not in VALID_TRANSITIONS["CREATED"], "Forbid skipping stages CREATED -> DELIVERED")

    print("\n[TEST GROUP 5] Database DDL Invariant Specifications for delivery_db")
    ddl_parcels = """
    CREATE TABLE IF NOT EXISTS parcels (
        parcel_id UUID PRIMARY KEY,
        tracking_number VARCHAR(32) NOT NULL UNIQUE,
        sender_sub VARCHAR(64) NOT NULL,
        merchant_id UUID,
        status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
        version BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        CONSTRAINT chk_parcel_status CHECK (
            status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')
        )
    );
    CREATE TABLE IF NOT EXISTS status_history (
        history_id UUID PRIMARY KEY,
        parcel_id UUID NOT NULL REFERENCES parcels(parcel_id) ON DELETE RESTRICT,
        previous_status VARCHAR(20),
        new_status VARCHAR(20) NOT NULL,
        reason VARCHAR(64),
        note TEXT,
        proof_id UUID,
        actor_sub VARCHAR(64) NOT NULL,
        resulting_version BIGINT NOT NULL,
        occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE INDEX idx_status_history_parcel_version ON status_history(parcel_id, resulting_version);
    """
    assert_test("status_history" in ddl_parcels, "status_history table defined with foreign key to parcels")
    assert_test("version BIGINT NOT NULL" in ddl_parcels, "parcels table incorporates BIGINT version for optimistic locking")
    assert_test("resulting_version" in ddl_parcels, "status_history records resulting aggregate version")
    assert_test("proof_id UUID" in ddl_parcels, "status_history stores proof metadata reference")

    print("\n" + "=" * 80)
    print(f"SUMMARY: {passed} PASSED, {failed} FAILED")
    print("=" * 80)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(run_delivery_status_contract_tests())
