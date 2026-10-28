"""
Data models and typed schemas for recovery consistency verification (Issue #168).
Defines domain records, snapshots, invariant check results, and evidence schemas.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional


class VerificationStatus(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    BLOCKED = "BLOCKED"
    SKIPPED = "SKIPPED"


class DomainName(str, Enum):
    ACCOUNTS = "Accounts"
    PRICING = "Pricing"
    DELIVERY = "Delivery"
    STATUS_HISTORY = "StatusHistory"
    COURIER_ASSIGNMENT = "CourierAssignment"
    DELIVERY_OUTBOX = "DeliveryOutbox"
    COMMUNICATIONS_INBOX = "CommunicationsInbox"
    NOTIFICATION_JOBS = "NotificationJobs"
    WEBHOOK_JOBS = "WebhookJobs"
    KAFKA_CHECKPOINTS = "KafkaCheckpoints"
    KEYCLOAK_IDENTITY = "KeycloakIdentity"
    PRIVATE_PROOFS = "PrivateProofs"
    RPO_BOUNDARY = "RpoBoundary"


@dataclass
class InvariantResult:
    invariant_id: str
    domain: DomainName
    description: str
    status: VerificationStatus
    message: str
    records_checked: int = 0
    violations_count: int = 0
    violation_samples: List[Dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "invariant_id": self.invariant_id,
            "domain": self.domain.value,
            "description": self.description,
            "status": self.status.value,
            "message": self.message,
            "records_checked": self.records_checked,
            "violations_count": self.violations_count,
            "violation_samples": self.violation_samples[:5],  # sanitize / limit sample size
        }


@dataclass
class DomainCheckResult:
    domain: DomainName
    status: VerificationStatus
    invariants_passed: int = 0
    invariants_failed: int = 0
    invariants_blocked: int = 0
    notes: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "domain": self.domain.value,
            "status": self.status.value,
            "invariants_passed": self.invariants_passed,
            "invariants_failed": self.invariants_failed,
            "invariants_blocked": self.invariants_blocked,
            "notes": self.notes,
        }


@dataclass
class RecoveryMetadata:
    environment: str = "k3d-swiftdrop-local"
    parent_issue: str = "#167"
    governing_issue: str = "#168"
    operator: str = "@Suthankan1"
    application_owner: str = "@Sinthuha-n"
    candidate_sha: str = "7c0cc6275044d708422c1b1f9e49d577d95a4c88"
    test_branch_sha: str = "UNKNOWN"
    backup_set_id: str = "backup-drill-20261028-1145"
    snapshot_cutoff: str = "2026-10-28T05:00:00Z"
    observed_rpo_seconds: int = 300
    observed_rto_seconds: int = 240
    budget_approval_status: str = "NOT APPROVED (Pending ADR)"
    image_digests: Dict[str, str] = field(default_factory=dict)
    restored_databases: List[str] = field(default_factory=lambda: [
        "accounts_db_drill_recovery",
        "pricing_db_drill_recovery",
        "delivery_db_drill_recovery",
        "communications_db_drill_recovery",
        "keycloak_db_drill_recovery",
    ])

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


# -----------------------------------------------------------------------------
# Domain Record Dataclasses
# -----------------------------------------------------------------------------

@dataclass
class AccountProfileRecord:
    id: str
    keycloak_sub: str
    email: str
    full_name: str
    phone_number: str
    created_at: str


@dataclass
class MerchantRecord:
    id: str
    account_profile_id: str
    keycloak_sub: str
    business_name: str
    registration_number: str
    status: str  # PENDING, APPROVED, SUSPENDED, REJECTED
    created_at: str
    updated_at: str


@dataclass
class StaffProfileRecord:
    id: str
    account_profile_id: Optional[str]
    keycloak_sub: str
    staff_id: str
    staff_role: str  # COURIER, DISPATCHER, ADMIN
    department: str
    assigned_zone: Optional[str]
    status: str  # ACTIVE, SUSPENDED, INACTIVE
    created_at: str
    updated_at: str


@dataclass
class KeycloakUserRecord:
    id: str  # keycloak sub
    username: str
    email: str
    roles: List[str] = field(default_factory=list)
    enabled: bool = True


@dataclass
class PricingZoneRecord:
    id: str
    zone_code: str
    name: str
    active: bool = True


@dataclass
class PricingRateVersionRecord:
    id: str
    version_code: str
    currency: str
    status: str  # ACTIVE, DRAFT, SUPERSEDED, ARCHIVED
    effective_from: str
    effective_to: Optional[str] = None


@dataclass
class PricingQuoteRecord:
    id: str
    rate_version_id: str
    rate_version_code: str
    pickup_zone_code: str
    dropoff_zone_code: str
    base_fee_minor_units: int
    weight_surcharge_minor_units: int
    total_amount_minor_units: int
    currency: str
    subject_sub: Optional[str]
    tenant_id: Optional[str]
    is_anonymous: bool
    created_at: str
    expires_at: str


@dataclass
class ParcelRecord:
    parcel_id: str
    tracking_number: str
    sender_sub: str
    merchant_id: Optional[str]
    quote_id: Optional[str]
    rate_version: Optional[str]
    amount_minor_units: Optional[int]
    currency: str
    weight_kg: Optional[float]
    status: str  # CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, FAILED
    version: int
    created_at: str
    updated_at: str
    merchant_reference: Optional[str] = None
    pickup_address: Optional[str] = None
    dropoff_address: Optional[str] = None
    recipient_name: Optional[str] = None
    recipient_phone: Optional[str] = None
    delivery_notes: Optional[str] = None
    quote_snapshot: Optional[Dict[str, Any]] = None


@dataclass
class StatusHistoryRecord:
    history_id: str
    parcel_id: str
    previous_status: Optional[str]
    new_status: str
    reason: Optional[str]
    note: Optional[str]
    proof_id: Optional[str]
    actor_sub: str
    resulting_version: int
    occurred_at: str


@dataclass
class IdempotencyRecord:
    record_id: str
    principal_or_tenant: str
    idempotency_key: str
    request_digest: str
    status: str  # IN_PROGRESS, COMPLETED, FAILED
    stored_result: Optional[Dict[str, Any]]
    created_at: str
    expires_at: str


@dataclass
class DeliveryAssignmentRecord:
    assignment_id: str
    parcel_id: str
    courier_sub: str
    assigned_by: str
    assigned_at: str
    ended_at: Optional[str] = None
    active: bool = True


@dataclass
class OutboxEventRecord:
    event_id: str
    aggregate_type: str  # PARCEL
    aggregate_id: str
    aggregate_version: int
    event_type: str
    schema_version: str
    payload: Dict[str, Any]
    status: str  # PENDING, IN_FLIGHT, PUBLISHED, FAILED, DEAD_LETTER
    created_at: str
    attempt_count: int = 0
    last_attempt_at: Optional[str] = None
    next_attempt_at: Optional[str] = None
    last_error: Optional[str] = None
    acknowledged_at: Optional[str] = None
    claimed_by: Optional[str] = None
    claim_expires_at: Optional[str] = None
    topic: str = "parcel.lifecycle.v1"
    partition_key: str = ""


@dataclass
class ConsumedEventRecord:
    consumer_name: str
    event_id: str
    event_type: str
    version: int
    parcel_id: str
    merchant_id: Optional[str]
    received_at: str
    processed_at: Optional[str] = None
    scheduled_at: Optional[str] = None


@dataclass
class NotificationJobRecord:
    notification_id: str
    event_id: str
    recipient_sub: str
    parcel_id: str
    channel: str  # EMAIL, SMS_MOCK
    destination_ref: str
    template: str
    status: str  # PENDING, PROCESSING, SENT, FAILED, EXHAUSTED
    attempts: int
    next_attempt_at: Optional[str]
    created_at: str
    updated_at: str
    claim_token: Optional[str] = None
    claimed_at: Optional[str] = None
    lease_expires_at: Optional[str] = None
    last_failure_category: Optional[str] = None


@dataclass
class NotificationAttemptRecord:
    attempt_id: str
    notification_id: str
    attempt_number: int
    channel: str
    outcome: str  # SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE, SECURITY_FAILURE, TIMEOUT
    failure_category: Optional[str]
    safe_error_code: Optional[str]
    duration_ms: Optional[int]
    attempted_at: str
    completed_at: Optional[str] = None


@dataclass
class WebhookSubscriptionRecord:
    subscription_id: str
    merchant_id: str
    https_url: str
    allowed_event_types: str
    secret_ref: str
    active: bool = True
    created_at: str = ""
    updated_at: str = ""


@dataclass
class WebhookDeliveryRecord:
    delivery_id: str
    subscription_id: str
    event_id: str
    parcel_id: str
    merchant_id: str
    state_version: int
    signed_payload_digest: Optional[str]
    status: str  # PENDING, PROCESSING, SUCCESS, FAILED, EXHAUSTED
    attempt_count: int
    next_attempt_at: Optional[str]
    created_at: str
    updated_at: str


@dataclass
class WebhookAttemptRecord:
    attempt_id: str
    delivery_id: str
    attempted_at: str
    http_status_code: Optional[int]
    response_time_ms: Optional[int]
    error_summary: Optional[str] = None


@dataclass
class PoisonEventRecord:
    quarantine_id: str
    consumer_group: str
    source_topic: str
    source_partition: int
    source_offset: int
    event_id_if_valid: Optional[str]
    failure_category: str
    status: str  # QUARANTINED, REPLAYED, DISCARDED
    quarantined_at: str


@dataclass
class ProofOfDeliveryRecord:
    proof_id: str
    parcel_id: str
    object_reference: str
    evidence_type: str  # PHOTO, SIGNATURE, PHOTO_OR_SIGNATURE
    content_type: str
    content_length: int
    recipient_name: Optional[str]
    delivery_note: Optional[str]
    uploaded_by_sub: str
    version: int
    created_at: str
    sha256_checksum: Optional[str] = None


@dataclass
class KafkaOffsetRecord:
    topic: str
    partition: int
    consumer_group: str
    committed_offset: int
    high_watermark: int
    timestamp: str


# -----------------------------------------------------------------------------
# Application Snapshot
# -----------------------------------------------------------------------------

@dataclass
class ApplicationSnapshot:
    snapshot_id: str
    captured_at: str
    environment: str
    accounts_profiles: List[AccountProfileRecord] = field(default_factory=list)
    merchants: List[MerchantRecord] = field(default_factory=list)
    staff_profiles: List[StaffProfileRecord] = field(default_factory=list)
    keycloak_users: List[KeycloakUserRecord] = field(default_factory=list)
    pricing_zones: List[PricingZoneRecord] = field(default_factory=list)
    pricing_rate_versions: List[PricingRateVersionRecord] = field(default_factory=list)
    pricing_quotes: List[PricingQuoteRecord] = field(default_factory=list)
    parcels: List[ParcelRecord] = field(default_factory=list)
    status_histories: List[StatusHistoryRecord] = field(default_factory=list)
    idempotency_records: List[IdempotencyRecord] = field(default_factory=list)
    delivery_assignments: List[DeliveryAssignmentRecord] = field(default_factory=list)
    outbox_events: List[OutboxEventRecord] = field(default_factory=list)
    consumed_events: List[ConsumedEventRecord] = field(default_factory=list)
    notifications: List[NotificationJobRecord] = field(default_factory=list)
    notification_attempts: List[NotificationAttemptRecord] = field(default_factory=list)
    webhook_subscriptions: List[WebhookSubscriptionRecord] = field(default_factory=list)
    webhook_deliveries: List[WebhookDeliveryRecord] = field(default_factory=list)
    webhook_attempts: List[WebhookAttemptRecord] = field(default_factory=list)
    poison_events: List[PoisonEventRecord] = field(default_factory=list)
    proof_of_deliveries: List[ProofOfDeliveryRecord] = field(default_factory=list)
    kafka_offsets: List[KafkaOffsetRecord] = field(default_factory=list)


@dataclass
class VerificationReport:
    report_id: str
    generated_at: str
    verification_mode: str  # offline, operator-checkpoint
    overall_status: VerificationStatus
    metadata: RecoveryMetadata
    domains: Dict[str, DomainCheckResult] = field(default_factory=dict)
    invariants: List[InvariantResult] = field(default_factory=list)
    unverified_items: List[str] = field(default_factory=list)
    recommendations: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "report_id": self.report_id,
            "generated_at": self.generated_at,
            "verification_mode": self.verification_mode,
            "overall_status": self.overall_status.value,
            "metadata": self.metadata.to_dict(),
            "domains": {k: v.to_dict() for k, v in self.domains.items()},
            "invariants": [inv.to_dict() for inv in self.invariants],
            "unverified_items": self.unverified_items,
            "recommendations": self.recommendations,
        }
