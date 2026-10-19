"""
Devarom Platform — Security E2E Master Test Runner (Task 018)
Coordinates automated execution of:
  - L06: JWT authentication rejection (Gateway 8080 and Accounts 8081)
  - L07: Cross-role authorization denial and privilege confinement
  - L08: Resource-owner and tenant isolation (Customer A vs B anti-spoofing)
Outputs machine-readable JSON artifacts and human-readable audit summaries.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import time

from harness_common import (
    Session,
    SecurityTestReport,
    HarnessError,
)
from test_jwt_rejection import run_jwt_rejection_suite
from test_role_and_ownership import run_role_and_ownership_suite

REPORT_OUTPUT_DIR = Path(__file__).resolve().parent / "reports"


def check_service_readiness(url: str, name: str, expected_statuses=(200,)) -> bool:
    session = Session(url)
    try:
        resp = session.request(url)
        return resp.status in expected_statuses
    except Exception:
        return False


def run_full_security_e2e_suite(
    gateway_url: str = "http://localhost:8080",
    accounts_url: str = "http://localhost:8081",
    keycloak_url: str = "http://localhost:8090",
    mailpit_url: str = "http://localhost:8025",
) -> int:
    print("\n" + "=" * 95)
    print(" DEVAROM PLATFORM — SECURITY E2E AUTOMATED TEST SUITE (TASK 018)")
    print("=" * 95)

    print("\n[Step 1/4] Checking live runtime service readiness...")
    services = [
        ("Keycloak IdP", f"{keycloak_url}/realms/devarom/.well-known/openid-configuration", (200,)),
        ("API Gateway", f"{gateway_url}/actuator/health", (200,)),
        ("Accounts Service", f"{accounts_url}/actuator/health", (200,)),
        ("Mailpit SMTP Dev", f"{mailpit_url}/api/v1/info", (200,)),
    ]

    all_ready = True
    for name, health_url, valid_codes in services:
        ready = check_service_readiness(health_url, name, valid_codes)
        mark = "\033[92mREADY\033[0m" if ready else "\033[91mNOT REACHABLE\033[0m"
        print(f"  - {name:<20}: {mark} ({health_url})")
        if not ready:
            all_ready = False

    if not all_ready:
        print("\n\033[91mERROR: Required services are not reachable. Please start them before running E2E tests.\033[0m")
        print("  - Keycloak: docker compose (port 8090)")
        print("  - API Gateway: services/api-gateway (port 8080)")
        print("  - Accounts Service: services/accounts-service (port 8081)")
        print("  - Mailpit: docker compose (port 8025)\n")
        return 1

    report = SecurityTestReport()
    start_time = time.time()

    print("\n[Step 2/4] Executing Suite 1: L06 JWT Authentication Rejection (401)...")
    run_jwt_rejection_suite(
        gateway_url=gateway_url,
        accounts_url=accounts_url,
        keycloak_url=keycloak_url,
        report=report,
    )

    print("\n[Step 3/4] Executing Suite 2: L07 Role Denial (403) & L08 Ownership Isolation...")
    run_role_and_ownership_suite(
        gateway_url=gateway_url,
        accounts_url=accounts_url,
        keycloak_url=keycloak_url,
        report=report,
    )

    duration = time.time() - start_time
    print(f"\n[Step 4/4] Completed all test executions in {duration:.2f}s.")

    report.print_summary()

    # Save JSON summary artifact
    REPORT_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    report_file = REPORT_OUTPUT_DIR / "security_e2e_results.json"
    data = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "duration_seconds": round(duration, 2),
        "total": len(report.results),
        "passed": sum(1 for r in report.results if r.status == "PASS"),
        "failed": sum(1 for r in report.results if r.status == "FAIL"),
        "blocked": sum(1 for r in report.results if r.status == "BLOCKED"),
        "results": [
            {
                "test_id": r.test_id,
                "name": r.name,
                "category": r.category,
                "service": r.service,
                "request": r.request_desc,
                "identity_role": r.identity_role,
                "expected_status": r.expected_status,
                "actual_status": r.actual_status,
                "status": r.status,
                "positive_control": r.positive_control,
                "detail": r.detail,
            }
            for r in report.results
        ],
    }
    report_file.write_text(json.dumps(data, indent=2))
    print(f"Artifact written to: {report_file}")

    has_failures = any(r.status == "FAIL" for r in report.results)
    return 1 if has_failures else 0


def main():
    parser = argparse.ArgumentParser(description="Devarom Platform Security E2E Test Runner")
    parser.add_argument("--gateway-url", default="http://localhost:8080", help="Spring Cloud Gateway URL")
    parser.add_argument("--accounts-url", default="http://localhost:8081", help="Accounts Service URL")
    parser.add_argument("--keycloak-url", default="http://localhost:8090", help="Keycloak IdP URL")
    parser.add_argument("--mailpit-url", default="http://localhost:8025", help="Mailpit SMTP Dev URL")
    args = parser.parse_args()

    return run_full_security_e2e_suite(
        gateway_url=args.gateway_url,
        accounts_url=args.accounts_url,
        keycloak_url=args.keycloak_url,
        mailpit_url=args.mailpit_url,
    )


if __name__ == "__main__":
    sys.exit(main())
