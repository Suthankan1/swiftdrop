"""
Deterministic synthetic fixtures for recovery consistency verification (Issue #168 / N10-P09A).
Generates strictly valid baseline application snapshots, point-in-time test scenarios,
and domain models aligned with the SwiftDrop v1.3 architecture baseline.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Dict, List, Tuple

from .models import (
    AccountProfileRecord,
    ApplicationSnapshot,
    ConsumedEventRecord,
    DeliveryAssignmentRecord,
    IdempotencyRecord,
    KafkaOffsetRecord,
    KeycloakUserRecord,
    MerchantRecord,
    NotificationAttemptRecord,
    NotificationJobRecord,
    OutboxEventRecord,
    ParcelRecord,
    PoisonEventRecord,
    PricingQuoteRecord,
    PricingRateVersionRecord,
    PricingZoneRecord,
    ProofOfDeliveryRecord,
    StaffProfileRecord,
    StatusHistoryRecord,
    WebhookAttemptRecord,
    WebhookDeliveryRecord,
    WebhookSubscriptionRecord,
)

# Reference Point-in-Time Checkpoint
SNAPSHOT_CHECKPOINT_ISO = "2026-10-28T05:00:00Z"


def build_deterministic_baseline_snapshot(
    snapshot_id: str = "snapshot-baseline-pre-recovery",
    environment: str = "k3d-swiftdrop-local",
    captured_at: str = "2026-10-28T05:00:00Z",
) -> ApplicationSnapshot:
    """
    Constructs a completely valid, deterministic synthetic application snapshot
    at the approved recovery boundary (2026-10-28T05:00:00Z).
    All domain relations, version sequences, and job states satisfy all business invariants.
    """

    # 1. Accounts & Keycloak
    kc_users = [
        KeycloakUserRecord(
            id="sub-cust-alpha-001",
            username="customer.alpha",
            email="customer.alpha@swiftdrop.local",
            roles=["CUSTOMER"],
            enabled=True,
        ),
        KeycloakUserRecord(
            id="sub-cust-beta-002",
            username="customer.beta",
            email="customer.beta@swiftdrop.local",
            roles=["CUSTOMER"],
            enabled=True,
        ),
        KeycloakUserRecord(
            id="sub-merch-apex-003",
            username="merchant.apex",
            email="merchant.apex@swiftdrop.local",
            roles=["MERCHANT"],
            enabled=True,
        ),
        KeycloakUserRecord(
            id="sub-cour-kamal-004",
            username="courier.kamal",
            email="courier.kamal@swiftdrop.local",
            roles=["COURIER"],
            enabled=True,
        ),
        KeycloakUserRecord(
            id="sub-cour-nimal-005",
            username="courier.nimal",
            email="courier.nimal@swiftdrop.local",
            roles=["COURIER"],
            enabled=True,
        ),
        KeycloakUserRecord(
            id="sub-disp-sunil-006",
            username="dispatcher.sunil",
            email="dispatcher.sunil@swiftdrop.local",
            roles=["DISPATCHER"],
            enabled=True,
        ),
        KeycloakUserRecord(
            id="sub-admin-root-007",
            username="admin.root",
            email="admin.root@swiftdrop.local",
            roles=["ADMIN"],
            enabled=True,
        ),
    ]

    accounts = [
        AccountProfileRecord(
            id="acc-0001-0000-0000-000000000001",
            keycloak_sub="sub-cust-alpha-001",
            email="customer.alpha@swiftdrop.local",
            full_name="Alpha Perera",
            phone_number="+94771000001",
            created_at="2026-10-20T08:00:00Z",
        ),
        AccountProfileRecord(
            id="acc-0001-0000-0000-000000000002",
            keycloak_sub="sub-cust-beta-002",
            email="customer.beta@swiftdrop.local",
            full_name="Beta Silva",
            phone_number="+94771000002",
            created_at="2026-10-20T08:30:00Z",
        ),
        AccountProfileRecord(
            id="acc-0001-0000-0000-000000000003",
            keycloak_sub="sub-merch-apex-003",
            email="merchant.apex@swiftdrop.local",
            full_name="Apex Logistics Admin",
            phone_number="+94112000003",
            created_at="2026-10-21T09:00:00Z",
        ),
        AccountProfileRecord(
            id="acc-0001-0000-0000-000000000004",
            keycloak_sub="sub-cour-kamal-004",
            email="courier.kamal@swiftdrop.local",
            full_name="Kamal Fernando",
            phone_number="+94773000004",
            created_at="2026-10-22T07:00:00Z",
        ),
        AccountProfileRecord(
            id="acc-0001-0000-0000-000000000005",
            keycloak_sub="sub-cour-nimal-005",
            email="courier.nimal@swiftdrop.local",
            full_name="Nimal Wickramasinghe",
            phone_number="+94773000005",
            created_at="2026-10-22T07:30:00Z",
        ),
        AccountProfileRecord(
            id="acc-0001-0000-0000-000000000006",
            keycloak_sub="sub-disp-sunil-006",
            email="dispatcher.sunil@swiftdrop.local",
            full_name="Sunil Jayawardena",
            phone_number="+94774000006",
            created_at="2026-10-18T06:00:00Z",
        ),
        AccountProfileRecord(
            id="acc-0001-0000-0000-000000000007",
            keycloak_sub="sub-admin-root-007",
            email="admin.root@swiftdrop.local",
            full_name="SwiftDrop Platform Administrator",
            phone_number="+94119000007",
            created_at="2026-10-15T00:00:00Z",
        ),
    ]

    merchants = [
        MerchantRecord(
            id="merch-0001-0000-0000-000000000001",
            account_profile_id="acc-0001-0000-0000-000000000003",
            keycloak_sub="sub-merch-apex-003",
            business_name="Apex Logistics PVT LTD",
            registration_number="PV-100234",
            status="APPROVED",
            created_at="2026-10-21T09:15:00Z",
            updated_at="2026-10-21T10:00:00Z",
        )
    ]

    staff_profiles = [
        StaffProfileRecord(
            id="staff-0001-0000-0000-000000000001",
            account_profile_id="acc-0001-0000-0000-000000000004",
            keycloak_sub="sub-cour-kamal-004",
            staff_id="STF-CR-001",
            staff_role="COURIER",
            department="Operations",
            assigned_zone="CMB-01",
            status="ACTIVE",
            created_at="2026-10-22T07:10:00Z",
            updated_at="2026-10-22T07:10:00Z",
        ),
        StaffProfileRecord(
            id="staff-0001-0000-0000-000000000002",
            account_profile_id="acc-0001-0000-0000-000000000005",
            keycloak_sub="sub-cour-nimal-005",
            staff_id="STF-CR-002",
            staff_role="COURIER",
            department="Operations",
            assigned_zone="CMB-02",
            status="ACTIVE",
            created_at="2026-10-22T07:40:00Z",
            updated_at="2026-10-22T07:40:00Z",
        ),
        StaffProfileRecord(
            id="staff-0001-0000-0000-000000000003",
            account_profile_id="acc-0001-0000-0000-000000000006",
            keycloak_sub="sub-disp-sunil-006",
            staff_id="STF-DSP-001",
            staff_role="DISPATCHER",
            department="Dispatch Control",
            assigned_zone=None,
            status="ACTIVE",
            created_at="2026-10-18T06:10:00Z",
            updated_at="2026-10-18T06:10:00Z",
        ),
        StaffProfileRecord(
            id="staff-0001-0000-0000-000000000004",
            account_profile_id="acc-0001-0000-0000-000000000007",
            keycloak_sub="sub-admin-root-007",
            staff_id="STF-ADM-001",
            staff_role="ADMIN",
            department="Executive Management",
            assigned_zone=None,
            status="ACTIVE",
            created_at="2026-10-15T00:10:00Z",
            updated_at="2026-10-15T00:10:00Z",
        ),
    ]

    # 2. Pricing & Zones
    pricing_zones = [
        PricingZoneRecord(
            id="zone-0001-0000-0000-000000000001",
            zone_code="CMB-01",
            name="Colombo Central (Fort, Colpetty, Bambalapitiya)",
            active=True,
        ),
        PricingZoneRecord(
            id="zone-0001-0000-0000-000000000002",
            zone_code="CMB-02",
            name="Colombo Suburbs (Dehiwala, Nugegoda, Rajagiriya)",
            active=True,
        ),
    ]

    rate_versions = [
        PricingRateVersionRecord(
            id="rv-0001-0000-0000-000000000001",
            version_code="RV-LKR-2026-V1",
            currency="LKR",
            status="ACTIVE",
            effective_from="2026-01-01T00:00:00Z",
            effective_to=None,
        )
    ]

    pricing_quotes = [
        # Quote 1: Customer A, booked into Parcel 1
        PricingQuoteRecord(
            id="quote-0001-0000-0000-000000000001",
            rate_version_id="rv-0001-0000-0000-000000000001",
            rate_version_code="RV-LKR-2026-V1",
            pickup_zone_code="CMB-01",
            dropoff_zone_code="CMB-02",
            base_fee_minor_units=120000,
            weight_surcharge_minor_units=30000,
            total_amount_minor_units=150000,  # 1500.00 LKR
            currency="LKR",
            subject_sub="sub-cust-alpha-001",
            tenant_id=None,
            is_anonymous=False,
            created_at="2026-10-28T03:30:00Z",
            expires_at="2026-10-28T04:30:00Z",
        ),
        # Quote 2: Merchant A, booked into Parcel 2
        PricingQuoteRecord(
            id="quote-0001-0000-0000-000000000002",
            rate_version_id="rv-0001-0000-0000-000000000001",
            rate_version_code="RV-LKR-2026-V1",
            pickup_zone_code="CMB-01",
            dropoff_zone_code="CMB-01",
            base_fee_minor_units=100000,
            weight_surcharge_minor_units=0,
            total_amount_minor_units=100000,  # 1000.00 LKR
            currency="LKR",
            subject_sub="sub-merch-apex-003",
            tenant_id="merch-0001-0000-0000-000000000001",
            is_anonymous=False,
            created_at="2026-10-28T04:15:00Z",
            expires_at="2026-10-28T05:15:00Z",
        ),
        # Quote 3: Customer B, valid and unconsumed at checkpoint
        PricingQuoteRecord(
            id="quote-0001-0000-0000-000000000003",
            rate_version_id="rv-0001-0000-0000-000000000001",
            rate_version_code="RV-LKR-2026-V1",
            pickup_zone_code="CMB-02",
            dropoff_zone_code="CMB-01",
            base_fee_minor_units=130000,
            weight_surcharge_minor_units=50000,
            total_amount_minor_units=180000,  # 1800.00 LKR
            currency="LKR",
            subject_sub="sub-cust-beta-002",
            tenant_id=None,
            is_anonymous=False,
            created_at="2026-10-28T04:45:00Z",
            expires_at="2026-10-28T05:45:00Z",
        ),
        # Quote 4: Anonymous quote, expired before checkpoint
        PricingQuoteRecord(
            id="quote-0001-0000-0000-000000000004",
            rate_version_id="rv-0001-0000-0000-000000000001",
            rate_version_code="RV-LKR-2026-V1",
            pickup_zone_code="CMB-01",
            dropoff_zone_code="CMB-02",
            base_fee_minor_units=120000,
            weight_surcharge_minor_units=0,
            total_amount_minor_units=120000,
            currency="LKR",
            subject_sub=None,
            tenant_id=None,
            is_anonymous=True,
            created_at="2026-10-28T02:00:00Z",
            expires_at="2026-10-28T03:00:00Z",
        ),
    ]

    # 3. Delivery Service
    # Parcel 1: DELIVERED parcel with complete history, ended assignment, proof of delivery
    p1_id = "parc-0001-0000-0000-000000000001"
    proof1_id = "prof-0001-0000-0000-000000000001"
    p1 = ParcelRecord(
        parcel_id=p1_id,
        tracking_number="SWD-20261028-0001",
        sender_sub="sub-cust-alpha-001",
        merchant_id=None,
        quote_id="quote-0001-0000-0000-000000000001",
        rate_version="RV-LKR-2026-V1",
        amount_minor_units=150000,
        currency="LKR",
        weight_kg=2.5,
        status="DELIVERED",
        version=5,
        created_at="2026-10-28T03:35:00Z",
        updated_at="2026-10-28T04:45:00Z",
        merchant_reference=None,
        pickup_address="12 Galle Road, Colombo 03",
        dropoff_address="45 Station Road, Nugegoda",
        recipient_name="Dharshani Silva",
        recipient_phone="+94772345678",
        delivery_notes="Deliver to front porch",
        quote_snapshot={
            "quote_id": "quote-0001-0000-0000-000000000001",
            "rate_version_code": "RV-LKR-2026-V1",
            "total_amount_minor_units": 150000,
            "currency": "LKR",
        },
    )

    # Status history for Parcel 1: 5 monotonic transitions
    p1_history = [
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000001",
            parcel_id=p1_id,
            previous_status=None,
            new_status="CREATED",
            reason="INITIAL_BOOKING",
            note="Customer booking confirmed",
            proof_id=None,
            actor_sub="sub-cust-alpha-001",
            resulting_version=1,
            occurred_at="2026-10-28T03:35:00Z",
        ),
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000002",
            parcel_id=p1_id,
            previous_status="CREATED",
            new_status="PICKED_UP",
            reason="COURIER_PICKUP",
            note="Picked up from customer origin",
            proof_id=None,
            actor_sub="sub-cour-kamal-004",
            resulting_version=2,
            occurred_at="2026-10-28T04:00:00Z",
        ),
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000003",
            parcel_id=p1_id,
            previous_status="PICKED_UP",
            new_status="IN_TRANSIT",
            reason="HUB_SORTING",
            note="Departed Colombo Hub for delivery zone",
            proof_id=None,
            actor_sub="sub-disp-sunil-006",
            resulting_version=3,
            occurred_at="2026-10-28T04:15:00Z",
        ),
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000004",
            parcel_id=p1_id,
            previous_status="IN_TRANSIT",
            new_status="OUT_FOR_DELIVERY",
            reason="FINAL_MILE_DISPATCH",
            note="On vehicle for final dropoff",
            proof_id=None,
            actor_sub="sub-cour-kamal-004",
            resulting_version=4,
            occurred_at="2026-10-28T04:30:00Z",
        ),
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000005",
            parcel_id=p1_id,
            previous_status="OUT_FOR_DELIVERY",
            new_status="DELIVERED",
            reason="RECIPIENT_ACCEPTED",
            note="Handed directly to recipient",
            proof_id=proof1_id,
            actor_sub="sub-cour-kamal-004",
            resulting_version=5,
            occurred_at="2026-10-28T04:45:00Z",
        ),
    ]

    # Assignment for Parcel 1: ended (inactive)
    p1_assignment = DeliveryAssignmentRecord(
        assignment_id="asgn-0001-0000-0000-000000000001",
        parcel_id=p1_id,
        courier_sub="sub-cour-kamal-004",
        assigned_by="sub-disp-sunil-006",
        assigned_at="2026-10-28T03:50:00Z",
        ended_at="2026-10-28T04:45:00Z",
        active=False,
    )

    # Proof of delivery for Parcel 1
    proof1 = ProofOfDeliveryRecord(
        proof_id=proof1_id,
        parcel_id=p1_id,
        object_reference="pod/2026/10/28/parc-0001.jpg",
        evidence_type="PHOTO",
        content_type="image/jpeg",
        content_length=245800,
        recipient_name="Dharshani Silva",
        delivery_note="Recipient signed and photo taken at doorstep",
        uploaded_by_sub="sub-cour-kamal-004",
        version=1,
        created_at="2026-10-28T04:45:00Z",
        sha256_checksum="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    )

    # Parcel 2: Merchant booking, currently IN_TRANSIT with active assignment to Courier B
    p2_id = "parc-0001-0000-0000-000000000002"
    p2 = ParcelRecord(
        parcel_id=p2_id,
        tracking_number="SWD-20261028-0002",
        sender_sub="sub-merch-apex-003",
        merchant_id="merch-0001-0000-0000-000000000001",
        quote_id="quote-0001-0000-0000-000000000002",
        rate_version="RV-LKR-2026-V1",
        amount_minor_units=100000,
        currency="LKR",
        weight_kg=1.0,
        status="IN_TRANSIT",
        version=3,
        created_at="2026-10-28T04:20:00Z",
        updated_at="2026-10-28T04:55:00Z",
        merchant_reference="APX-ORD-9988",
        pickup_address="Warehouse 4, Port Access Road, Colombo 13",
        dropoff_address="78 Main Street, Colombo 11",
        recipient_name="Janaka Stores",
        recipient_phone="+94112345679",
        delivery_notes="Deliver during business hours",
        quote_snapshot={
            "quote_id": "quote-0001-0000-0000-000000000002",
            "rate_version_code": "RV-LKR-2026-V1",
            "total_amount_minor_units": 100000,
            "currency": "LKR",
        },
    )

    p2_history = [
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000006",
            parcel_id=p2_id,
            previous_status=None,
            new_status="CREATED",
            reason="MERCHANT_ORDER_CREATED",
            note="Merchant order intake via REST API",
            proof_id=None,
            actor_sub="sub-merch-apex-003",
            resulting_version=1,
            occurred_at="2026-10-28T04:20:00Z",
        ),
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000007",
            parcel_id=p2_id,
            previous_status="CREATED",
            new_status="PICKED_UP",
            reason="MERCHANT_DISPATCH",
            note="Courier picked up bulk order from warehouse",
            proof_id=None,
            actor_sub="sub-cour-nimal-005",
            resulting_version=2,
            occurred_at="2026-10-28T04:40:00Z",
        ),
        StatusHistoryRecord(
            history_id="hist-0001-0000-0000-000000000008",
            parcel_id=p2_id,
            previous_status="PICKED_UP",
            new_status="IN_TRANSIT",
            reason="EN_ROUTE",
            note="In transit to Colombo 11",
            proof_id=None,
            actor_sub="sub-cour-nimal-005",
            resulting_version=3,
            occurred_at="2026-10-28T04:55:00Z",
        ),
    ]

    p2_assignment = DeliveryAssignmentRecord(
        assignment_id="asgn-0001-0000-0000-000000000002",
        parcel_id=p2_id,
        courier_sub="sub-cour-nimal-005",
        assigned_by="sub-disp-sunil-006",
        assigned_at="2026-10-28T04:35:00Z",
        ended_at=None,
        active=True,  # Active assignment invariant: exactly one active
    )

    # Idempotency records for bookings
    idempotency_records = [
        IdempotencyRecord(
            record_id="idem-0001-0000-0000-000000000001",
            principal_or_tenant="sub-cust-alpha-001",
            idempotency_key="idemp-key-customer-alpha-001",
            request_digest="9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            status="COMPLETED",
            stored_result={"parcelId": p1_id, "trackingNumber": "SWD-20261028-0001"},
            created_at="2026-10-28T03:35:00Z",
            expires_at="2026-10-29T03:35:00Z",
        ),
        IdempotencyRecord(
            record_id="idem-0001-0000-0000-000000000002",
            principal_or_tenant="merchant:merch-0001-0000-0000-000000000001",
            idempotency_key="idemp-key-merchant-apex-001",
            request_digest="5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8",
            status="COMPLETED",
            stored_result={"parcelId": p2_id, "trackingNumber": "SWD-20261028-0002"},
            created_at="2026-10-28T04:20:00Z",
            expires_at="2026-10-29T04:20:00Z",
        ),
    ]

    # Outbox events
    outbox_events = [
        # Parcel 1 events: 5 published events
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000001",
            aggregate_type="PARCEL",
            aggregate_id=p1_id,
            aggregate_version=1,
            event_type="PARCEL_CREATED",
            schema_version="1.0.0",
            payload={"parcelId": p1_id, "status": "CREATED", "version": 1},
            status="PUBLISHED",
            created_at="2026-10-28T03:35:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T03:35:05Z",
            acknowledged_at="2026-10-28T03:35:05Z",
            topic="parcel.lifecycle.v1",
            partition_key=p1_id,
        ),
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000002",
            aggregate_type="PARCEL",
            aggregate_id=p1_id,
            aggregate_version=2,
            event_type="PARCEL_PICKED_UP",
            schema_version="1.0.0",
            payload={"parcelId": p1_id, "status": "PICKED_UP", "version": 2},
            status="PUBLISHED",
            created_at="2026-10-28T04:00:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T04:00:03Z",
            acknowledged_at="2026-10-28T04:00:03Z",
            topic="parcel.lifecycle.v1",
            partition_key=p1_id,
        ),
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000003",
            aggregate_type="PARCEL",
            aggregate_id=p1_id,
            aggregate_version=3,
            event_type="PARCEL_IN_TRANSIT",
            schema_version="1.0.0",
            payload={"parcelId": p1_id, "status": "IN_TRANSIT", "version": 3},
            status="PUBLISHED",
            created_at="2026-10-28T04:15:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T04:15:04Z",
            acknowledged_at="2026-10-28T04:15:04Z",
            topic="parcel.lifecycle.v1",
            partition_key=p1_id,
        ),
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000004",
            aggregate_type="PARCEL",
            aggregate_id=p1_id,
            aggregate_version=4,
            event_type="PARCEL_OUT_FOR_DELIVERY",
            schema_version="1.0.0",
            payload={"parcelId": p1_id, "status": "OUT_FOR_DELIVERY", "version": 4},
            status="PUBLISHED",
            created_at="2026-10-28T04:30:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T04:30:02Z",
            acknowledged_at="2026-10-28T04:30:02Z",
            topic="parcel.lifecycle.v1",
            partition_key=p1_id,
        ),
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000005",
            aggregate_type="PARCEL",
            aggregate_id=p1_id,
            aggregate_version=5,
            event_type="PARCEL_DELIVERED",
            schema_version="1.0.0",
            payload={"parcelId": p1_id, "status": "DELIVERED", "version": 5, "proofId": proof1_id},
            status="PUBLISHED",
            created_at="2026-10-28T04:45:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T04:45:03Z",
            acknowledged_at="2026-10-28T04:45:03Z",
            topic="parcel.lifecycle.v1",
            partition_key=p1_id,
        ),
        # Parcel 2 events: v1, v2 PUBLISHED, v3 PUBLISHED
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000006",
            aggregate_type="PARCEL",
            aggregate_id=p2_id,
            aggregate_version=1,
            event_type="PARCEL_CREATED",
            schema_version="1.0.0",
            payload={"parcelId": p2_id, "status": "CREATED", "version": 1},
            status="PUBLISHED",
            created_at="2026-10-28T04:20:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T04:20:05Z",
            acknowledged_at="2026-10-28T04:20:05Z",
            topic="parcel.lifecycle.v1",
            partition_key=p2_id,
        ),
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000007",
            aggregate_type="PARCEL",
            aggregate_id=p2_id,
            aggregate_version=2,
            event_type="PARCEL_PICKED_UP",
            schema_version="1.0.0",
            payload={"parcelId": p2_id, "status": "PICKED_UP", "version": 2},
            status="PUBLISHED",
            created_at="2026-10-28T04:40:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T04:40:03Z",
            acknowledged_at="2026-10-28T04:40:03Z",
            topic="parcel.lifecycle.v1",
            partition_key=p2_id,
        ),
        OutboxEventRecord(
            event_id="evt-0001-0000-0000-000000000008",
            aggregate_type="PARCEL",
            aggregate_id=p2_id,
            aggregate_version=3,
            event_type="PARCEL_IN_TRANSIT",
            schema_version="1.0.0",
            payload={"parcelId": p2_id, "status": "IN_TRANSIT", "version": 3},
            status="PUBLISHED",
            created_at="2026-10-28T04:55:00Z",
            attempt_count=1,
            last_attempt_at="2026-10-28T04:55:04Z",
            acknowledged_at="2026-10-28T04:55:04Z",
            topic="parcel.lifecycle.v1",
            partition_key=p2_id,
        ),
    ]

    # 4. Communications Service
    # Consumed events inbox
    consumed_events = [
        ConsumedEventRecord(
            consumer_name="swiftdrop-communications-notifications",
            event_id="evt-0001-0000-0000-000000000001",
            event_type="PARCEL_CREATED",
            version=1,
            parcel_id=p1_id,
            merchant_id=None,
            received_at="2026-10-28T03:35:10Z",
            processed_at="2026-10-28T03:35:11Z",
        ),
        ConsumedEventRecord(
            consumer_name="swiftdrop-communications-notifications",
            event_id="evt-0001-0000-0000-000000000005",
            event_type="PARCEL_DELIVERED",
            version=1,
            parcel_id=p1_id,
            merchant_id=None,
            received_at="2026-10-28T04:45:10Z",
            processed_at="2026-10-28T04:45:12Z",
        ),
        ConsumedEventRecord(
            consumer_name="swiftdrop-communications-webhooks",
            event_id="evt-0001-0000-0000-000000000006",
            event_type="PARCEL_CREATED",
            version=1,
            parcel_id=p2_id,
            merchant_id="merch-0001-0000-0000-000000000001",
            received_at="2026-10-28T04:20:10Z",
            processed_at="2026-10-28T04:20:12Z",
        ),
    ]

    # Notifications
    notifications = [
        # Job 1: Sent email for Parcel 1 delivery
        NotificationJobRecord(
            notification_id="notif-0001-0000-0000-000000000001",
            event_id="evt-0001-0000-0000-000000000005",
            recipient_sub="sub-cust-alpha-001",
            parcel_id=p1_id,
            channel="EMAIL",
            destination_ref="customer.alpha@swiftdrop.local",
            template="PARCEL_DELIVERED_V1",
            status="SENT",
            attempts=1,
            next_attempt_at=None,
            created_at="2026-10-28T04:45:12Z",
            updated_at="2026-10-28T04:45:15Z",
        ),
        # Job 2: Retryable failed SMS notification for Parcel 2 pickup
        NotificationJobRecord(
            notification_id="notif-0001-0000-0000-000000000002",
            event_id="evt-0001-0000-0000-000000000007",
            recipient_sub="sub-merch-apex-003",
            parcel_id=p2_id,
            channel="SMS_MOCK",
            destination_ref="+94112000003",
            template="PARCEL_PICKED_UP_V1",
            status="FAILED",
            attempts=2,
            next_attempt_at="2026-10-28T05:10:00Z",  # Bounded future retry
            created_at="2026-10-28T04:40:12Z",
            updated_at="2026-10-28T04:41:00Z",
            last_failure_category="GATEWAY_RATE_LIMIT",
        ),
        # Job 3: Pending notification
        NotificationJobRecord(
            notification_id="notif-0001-0000-0000-000000000003",
            event_id="evt-0001-0000-0000-000000000008",
            recipient_sub="sub-merch-apex-003",
            parcel_id=p2_id,
            channel="EMAIL",
            destination_ref="merchant.apex@swiftdrop.local",
            template="PARCEL_IN_TRANSIT_V1",
            status="PENDING",
            attempts=0,
            next_attempt_at="2026-10-28T05:00:00Z",
            created_at="2026-10-28T04:55:12Z",
            updated_at="2026-10-28T04:55:12Z",
        ),
    ]

    notification_attempts = [
        # Attempts for Job 1
        NotificationAttemptRecord(
            attempt_id="natm-0001-0000-0000-000000000001",
            notification_id="notif-0001-0000-0000-000000000001",
            attempt_number=1,
            channel="EMAIL",
            outcome="SUCCESS",
            failure_category=None,
            safe_error_code=None,
            duration_ms=210,
            attempted_at="2026-10-28T04:45:13Z",
            completed_at="2026-10-28T04:45:15Z",
        ),
        # Attempts for Job 2
        NotificationAttemptRecord(
            attempt_id="natm-0001-0000-0000-000000000002",
            notification_id="notif-0001-0000-0000-000000000002",
            attempt_number=1,
            channel="SMS_MOCK",
            outcome="TRANSIENT_FAILURE",
            failure_category="GATEWAY_RATE_LIMIT",
            safe_error_code="RATE_LIMIT_429",
            duration_ms=150,
            attempted_at="2026-10-28T04:40:15Z",
            completed_at="2026-10-28T04:40:16Z",
        ),
        NotificationAttemptRecord(
            attempt_id="natm-0001-0000-0000-000000000003",
            notification_id="notif-0001-0000-0000-000000000002",
            attempt_number=2,
            channel="SMS_MOCK",
            outcome="TRANSIENT_FAILURE",
            failure_category="GATEWAY_RATE_LIMIT",
            safe_error_code="RATE_LIMIT_429",
            duration_ms=160,
            attempted_at="2026-10-28T04:40:45Z",
            completed_at="2026-10-28T04:40:46Z",
        ),
    ]

    # Webhook subscriptions & jobs
    sub1_id = "wsub-0001-0000-0000-000000000001"
    webhook_subscriptions = [
        WebhookSubscriptionRecord(
            subscription_id=sub1_id,
            merchant_id="merch-0001-0000-0000-000000000001",
            https_url="https://api.apexlogistics.lk/swiftdrop/webhook",
            allowed_event_types="PARCEL_CREATED,PARCEL_PICKED_UP,PARCEL_IN_TRANSIT,PARCEL_DELIVERED",
            secret_ref="sec-merchant-apex-wh-001",
            active=True,
            created_at="2026-10-21T10:30:00Z",
            updated_at="2026-10-21T10:30:00Z",
        )
    ]

    webhook_deliveries = [
        WebhookDeliveryRecord(
            delivery_id="wdel-0001-0000-0000-000000000001",
            subscription_id=sub1_id,
            event_id="evt-0001-0000-0000-000000000006",
            parcel_id=p2_id,
            merchant_id="merch-0001-0000-0000-000000000001",
            state_version=1,
            signed_payload_digest="d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2",
            status="SUCCESS",
            attempt_count=1,
            next_attempt_at=None,
            created_at="2026-10-28T04:20:15Z",
            updated_at="2026-10-28T04:20:18Z",
        )
    ]

    webhook_attempts = [
        WebhookAttemptRecord(
            attempt_id="watm-0001-0000-0000-000000000001",
            delivery_id="wdel-0001-0000-0000-000000000001",
            attempted_at="2026-10-28T04:20:16Z",
            http_status_code=200,
            response_time_ms=85,
            error_summary=None,
        )
    ]

    # Kafka offsets
    kafka_offsets = [
        KafkaOffsetRecord(
            topic="parcel.lifecycle.v1",
            partition=0,
            consumer_group="swiftdrop-communications-notifications",
            committed_offset=5,
            high_watermark=5,
            timestamp="2026-10-28T05:00:00Z",
        ),
        KafkaOffsetRecord(
            topic="parcel.lifecycle.v1",
            partition=0,
            consumer_group="swiftdrop-communications-webhooks",
            committed_offset=3,
            high_watermark=5,
            timestamp="2026-10-28T05:00:00Z",
        ),
    ]

    return ApplicationSnapshot(
        snapshot_id=snapshot_id,
        captured_at=captured_at,
        environment=environment,
        accounts_profiles=accounts,
        merchants=merchants,
        staff_profiles=staff_profiles,
        keycloak_users=kc_users,
        pricing_zones=pricing_zones,
        pricing_rate_versions=rate_versions,
        pricing_quotes=pricing_quotes,
        parcels=[p1, p2],
        status_histories=p1_history + p2_history,
        idempotency_records=idempotency_records,
        delivery_assignments=[p1_assignment, p2_assignment],
        outbox_events=outbox_events,
        consumed_events=consumed_events,
        notifications=notifications,
        notification_attempts=notification_attempts,
        webhook_subscriptions=webhook_subscriptions,
        webhook_deliveries=webhook_deliveries,
        webhook_attempts=webhook_attempts,
        poison_events=[],
        proof_of_deliveries=[proof1],
        kafka_offsets=kafka_offsets,
    )
