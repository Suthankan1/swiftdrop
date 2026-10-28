# SwiftDrop Platform — Automated End-to-End Testing Suite (N10-QA-02)

## Overview

This directory provides automated end-to-end acceptance journeys covering the full SwiftDrop parcel lifecycle, addressing **GitHub Issue #157 (`[N10-QA-02] Automate customer booking, courier completion and live tracking journeys`)**.

### Primary Journey Tested

```
Customer Auth -> Valid Quote -> Confirmed Booking (Idempotent) -> Dispatcher Assignment ->
Courier Status Progression (CREATED -> PICKED_UP -> IN_TRANSIT -> OUT_FOR_DELIVERY) ->
Mandatory Proof Capture -> Terminal Delivery Completion -> Live SSE Updates & Reconnect Refetch ->
Authorized PDF Receipt Download
```

Negative security tests verify:
- Expired quote rejection (422 Unprocessable Entity)
- Stale state version rejection (409 Conflict)
- Unauthorized courier proof upload rejection (403 Forbidden)
- Cross-customer access rejection (403/404 IDOR protection)

## Directory Layout

```
tests/e2e/
├── README.md                              # This documentation
├── __init__.py                            # Package init
├── test_customer_courier_journey.py      # Unittest suite covering all 20 acceptance criteria
├── run_customer_courier_e2e.py            # Master test runner with live service discovery
└── reports/
    └── customer_courier_e2e_results.json  # Machine-readable sanitized audit report
```

In addition, frontend browser/DOM acceptance journeys are located at:
`frontend/src/test/e2e/customerCourierJourneys.test.tsx` (executed via Vitest).

## Execution Instructions

### 1. Python In-Process Journey Runner

```bash
# Discover and run unit tests
python3 -m unittest discover tests/e2e

# Run master runner with automated preflight checks and JSON report generation
python3 tests/e2e/run_customer_courier_e2e.py

# Run in strict mode (fails if Docker Compose services are offline)
python3 tests/e2e/run_customer_courier_e2e.py --strict-live
```

### 2. Frontend React / Vitest Acceptance Journeys

```bash
cd frontend
npm test -- src/test/e2e/customerCourierJourneys.test.tsx --run
```

## Runtime Modes

1. **Standalone Harness Mode (Default when local Docker daemon is offline):**
   Executes complete state-machine, contract invariants, JWT role scoping, and negative security checks in-process. Does not require external databases or running containers.
2. **Live Service Discovery Mode:**
   Automatically detects running backend services (Ports: 8080 Gateway, 8081 Accounts, 8082 Delivery, 8083 Pricing, 8090 Keycloak) and performs live TCP/HTTP interactions.
