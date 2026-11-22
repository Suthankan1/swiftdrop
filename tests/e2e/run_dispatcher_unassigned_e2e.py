#!/usr/bin/env python3
"""
SwiftDrop Platform — Master Dispatcher Unassigned Parcels & Courier Assignment Runner
Orchestrates live service health discovery and full end-to-end integration assertions.
"""

import datetime
import json
from pathlib import Path
import socket
import sys
import time
import unittest

from test_dispatcher_unassigned_e2e import TestDispatcherUnassignedIntegration

REPORT_DIR = Path(__file__).resolve().parent / "reports"
REPORT_FILE = REPORT_DIR / "dispatcher_unassigned_e2e_results.json"


def check_port_open(host: str, port: int, timeout_sec: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout_sec):
            return True
    except (socket.timeout, ConnectionRefusedError, OSError):
        return False


def run_suite():
    print("=" * 90)
    print(" SWIFTDROP PLATFORM — DISPATCHER UNASSIGNED PARCELS INTEGRATION E2E RUNNER")
    print("=" * 90)

    services = [
        ("Frontend UI", "localhost", 3000),
        ("API Gateway", "localhost", 8080),
        ("Keycloak IdP", "localhost", 8090),
        ("Kafka Broker", "localhost", 9092),
        ("Mailpit", "localhost", 8025),
        ("MinIO Storage", "localhost", 9000),
    ]

    print("\n[PREFLIGHT] Probing live infrastructure ports:")
    service_status = {}
    for name, host, port in services:
        is_open = check_port_open(host, port)
        service_status[name] = "UP" if is_open else "DOWN"
        status_mark = "ONLINE" if is_open else "OFFLINE"
        print(f"  - {name:<22} ({host}:{port}): {status_mark}")

    print("\n[EXECUTION] Running Dispatcher unassigned parcels and courier assignment assertions...")
    suite = unittest.TestLoader().loadTestsFromTestCase(TestDispatcherUnassignedIntegration)
    runner = unittest.TextTestRunner(verbosity=2)
    start_time = time.time()
    result = runner.run(suite)
    duration = time.time() - start_time

    passed = result.wasSuccessful()
    REPORT_DIR.mkdir(parents=True, exist_ok=True)

    report = {
        "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "suite": "Dispatcher Unassigned Parcels & Courier Integration E2E",
        "serviceStatus": service_status,
        "totalTests": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "durationSec": round(duration, 3),
        "passed": passed,
        "criteria": [
            "AC-01: Canonical zone code & label normalization",
            "AC-02: Delivery unassigned parcel query matches by zone code, alias, and name",
            "AC-03: Accounts courier retrieval matches couriers across zone codes and regional aliases",
            "AC-04: Cross-scope protection: Dispatcher scoped to Colombo/ZONE_1 allowed in ZONE_1",
            "AC-05: Cross-scope protection: Scoped dispatcher rejected outside operational scope",
            "AC-06: Dispatcher selects unassigned parcel & eligible courier, confirming assignment",
            "AC-07: Parcel state lifecycle: Version incremented, parcel removed from unassigned queue",
            "AC-08: Assigned parcel visible in courier operational workspace",
            "AC-09: Optimistic locking prevents stale assignment race (409 Conflict)",
            "AC-10: Single active assignment invariant prevents duplicate assignment",
            "AC-11: Security boundary: Non-dispatcher roles rejected with FORBIDDEN / AccessDenied",
            "AC-12: Diagnostic & log scrubber eliminates credentials or PII from reports",
        ]
    }

    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print(f"\n[REPORT] Saved machine-readable audit report to {REPORT_FILE}")

    if passed:
        print("\n" + "=" * 90)
        print(" ALL 12 DISPATCHER INTEGRATION ASSERTIONS PASSED SUCCESSFULLY.")
        print("=" * 90)
        return 0
    else:
        print("\n[FAILURE] One or more assertions failed.")
        return 1


if __name__ == "__main__":
    sys.exit(run_suite())
