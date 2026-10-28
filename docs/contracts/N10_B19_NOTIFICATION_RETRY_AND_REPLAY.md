# Contract & Architecture Specification: Bounded Notification Retry, Poison-Event Quarantine, and Inspected Replay

**Milestone:** November 10 Baseline (Architecture Baseline v1.3)  
**Issue:** [Refs #117 (N10-B19)](https://github.com/Suthankan1/swiftdrop/issues/117) — Add Bounded Notification Retry, Poison-Event Handling and Inspected Replay  
**Primary Owner:** `@thuvarahan-t` (Communications Lead)  
**Reviewers:** `@EricPraveen`, `@Vibhushan-2003`, `@Suthankan1`  
**Service:** Communications Service (`services/communications-service`)  
**Database:** `communications_db` (PostgreSQL 16)  

---

## 1. Context & Architecture Invariants

The SwiftDrop Communications microservice ingests parcel lifecycle domain events from Kafka topic `parcel.lifecycle.v1` under consumer group `communications.notifications.v1`. Prior to this delivery, notifications were only queued as durable pending records with a logging adapter.

Issue #117 establishes:
1. **First Real Notification Transport:** Complete EMAIL outbound dispatch using Spring Mail (`JavaMailSender`) connecting to the platform SMTP test sink (`mailpit:1025`) without hardcoding credentials or exposing customer PII.
2. **PostgreSQL-Backed Worker Engine with Leases:** Safe polling and exclusive lease acquisition (`claimJobWithLease`) via atomic SQL updates to prevent duplicate dispatches across multiple horizontally scaled service replicas.
3. **Bounded Exponential Backoff with Full Jitter:** Mathematical backoff calculation with explicit integer overflow protection, safe jitter bounds, and configurable max attempts (default 5, ceiling 300s).
4. **Stalled Worker Recovery:** Safe recovery of jobs stalled in `PROCESSING` status past worker lease expiration back to `PENDING`.
5. **Durable Poison-Event Quarantine:** Invalid or schema-violating Kafka messages are durably isolated in `poison_event_quarantine` with SHA-256 cryptographic payload digests, safe classification, and envelope metadata. Kafka offset is acknowledged **strictly after** durable quarantine persistence, preventing partition head-of-line blocking.
6. **Audited, Inspected Replay:** Dedicated operator endpoints (`/api/v1/internal/...`) restricted to `ROLE_ADMIN` and `ROLE_DISPATCHER` requiring a non-blank reason. Replay re-validates recipient contact eligibility with Accounts Service and event authorization with Delivery Operations before resetting a terminal job (`FAILED`, `EXHAUSTED`) back to `PENDING`.
7. **Bounded Data Retention:** Automated and on-demand retention pruning for terminal jobs (`SENT`, `FAILED`, `EXHAUSTED`) and processed inbox receipts (`consumed_events`) past the configured cutoff (default 30 days), while strictly preserving active `PENDING` and `PROCESSING` jobs.

---

## 2. Policy & Decision Register Status

| Decision ID | Subject | Implementation Status | Policy Approval Note |
|---|---|---|---|
| **DEC-EVT-01** | Lifecycle event schema & topic | Implemented | Approved (contracts/events) |
| **DEC-EVT-02** | Parcel partition key ordering | Implemented | Approved (UUID parcelId key) |
| **DEC-EVT-03** | Event data minimization | Implemented | Approved (Zero PII in events) |
| **DEC-EVT-04** | At-least-once processing & inbox dedup | Implemented | Approved (composite PK in consumed_events) |
| **DEC-COM-05** | Accounts recipient contact lookup | Implemented | Approved (gRPC IF-GRPC-03) |
| **DEC-COM-03** | Notification retry & backoff policy | Implemented (Configurable) | **PROPOSED — AWAITING REVIEW** (Defaults: 5 attempts, 5s base, 300s cap, decorrelated full jitter) |
| **DEC-COM-04** | Dead-letter & quarantine policy | Implemented | **PROPOSED — AWAITING REVIEW** (quarantine table, SHA-256 digest, inspected operator replay) |
| **DEC-RET-01** | Data retention periods | Implemented (Configurable) | **PROPOSED — AWAITING REVIEW** (Default 30 days terminal purge, inbox purge) |

> [!NOTE]
> Per SwiftDrop repository discipline, provisional configuration defaults are supplied in `application.yml` and fully configurable via environment variables (`SWIFTDROP_NOTIFICATIONS_DISPATCH_*`, `SWIFTDROP_NOTIFICATIONS_SCHEDULER_*`). Final business rule parameters will be ratified upon formal ADR review.

---

## 3. Database Schema (Flyway `V4__create_notification_attempts_and_replay_schema.sql`)

```sql
-- 1. Worker Lease Columns on notifications table
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS claim_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_failure_category VARCHAR(50);

-- Partial index for active leases and stalled recovery
CREATE INDEX IF NOT EXISTS idx_notifications_stuck_recovery
    ON notifications (status, lease_expires_at)
    WHERE status = 'PROCESSING';

-- 2. Durable Attempt History
CREATE TABLE IF NOT EXISTS notification_attempts (
    attempt_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(notification_id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    channel VARCHAR(50) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    failure_category VARCHAR(50),
    safe_error_code VARCHAR(100),
    duration_ms INTEGER,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_notification_attempts_job_lookup
    ON notification_attempts (notification_id, attempt_number ASC);

-- 3. Notification Replay Audits
CREATE TABLE IF NOT EXISTS notification_replays (
    replay_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(notification_id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    actor_sub VARCHAR(255) NOT NULL,
    actor_role VARCHAR(50) NOT NULL,
    reason TEXT NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    decision VARCHAR(30) NOT NULL,
    replayed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_notification_replay_prev_status CHECK (previous_status IN ('FAILED', 'EXHAUSTED')),
    CONSTRAINT chk_notification_replay_decision CHECK (decision IN ('ACCEPTED', 'REJECTED'))
);

-- 4. Poison Event Quarantine
CREATE TABLE IF NOT EXISTS poison_event_quarantine (
    quarantine_id UUID PRIMARY KEY,
    consumer_group VARCHAR(100) NOT NULL,
    source_topic VARCHAR(100) NOT NULL,
    source_partition INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    event_id_if_valid UUID,
    event_type_if_valid VARCHAR(100),
    schema_version_if_valid VARCHAR(50),
    failure_category VARCHAR(50) NOT NULL,
    safe_error_summary VARCHAR(500) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    correlation_reference VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'QUARANTINED',
    quarantined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    replayed_at TIMESTAMPTZ,
    replayed_by_actor_sub VARCHAR(255),
    replay_reason TEXT,
    CONSTRAINT uq_poison_event_partition_offset UNIQUE (consumer_group, source_topic, source_partition, source_offset),
    CONSTRAINT chk_poison_quarantine_status CHECK (status IN ('QUARANTINED', 'REPLAYED', 'DISCARDED'))
);
```

---

## 4. Failure Classification Matrix

Outbound delivery errors are mapped to safe, sanitized, low-cardinality categories with zero exposed credentials or customer emails:

| Upstream Error / Exception | Classified Category | Retryable? | Terminal Outcome |
|---|---|---|---|
| `AccountsServiceUnavailableException` | `ACCOUNTS_UNAVAILABLE` | Yes | Transient failure -> Exponential backoff |
| `DeliveryServiceUnavailableException` | `DELIVERY_UNAVAILABLE` | Yes | Transient failure -> Exponential backoff |
| `SocketTimeoutException` / SMTP timeout | `SMTP_CONNECTION_TIMEOUT` | Yes | Transient failure -> Exponential backoff |
| `ConnectException` / SMTP connect fail | `SMTP_CONNECTION_TIMEOUT` | Yes | Transient failure -> Exponential backoff |
| `MailSendException` (HTTP 4xx equivalent) | `SMTP_TEMPORARY_ERROR` | Yes | Transient failure -> Exponential backoff |
| `MailAuthenticationException` | `SMTP_AUTH_FAILED` | No | Fail closed -> `FAILED` |
| `MailSendException` (5xx permanent reject) | `SMTP_PERMANENT_ERROR` | No | Fail closed -> `FAILED` |
| `RecipientNotFoundException` | `RECIPIENT_NOT_FOUND` | No | Fail closed -> `FAILED` |
| `NoEligibleNotificationTargetException` | `RECIPIENT_INELIGIBLE` | No | Fail closed -> `FAILED` |
| `DeliveryAuthorizationException` | `UNAUTHORIZED_RECIPIENT` | No | Fail closed -> `FAILED` |
| `AccountsSecurityException` | `SERVICE_AUTH_FAILED` | No | Security rejection -> `FAILED` |
| `DeliverySecurityException` | `SERVICE_AUTH_FAILED` | No | Security rejection -> `FAILED` |
| Attempts exhausted (`attempts >= maxAttempts`) | `ATTEMPTS_EXHAUSTED` | No | Terminal exhaustion -> `EXHAUSTED` |

---

## 5. Operator REST API Matrix

Base Path: `/api/v1/internal` (Restricted to `ROLE_ADMIN` and `ROLE_DISPATCHER`)

| Method | Path | Description | Required Role | Response |
|---|---|---|---|---|
| `POST` | `/notifications/{id}/replay` | Inspect and schedule replay of FAILED or EXHAUSTED notification | `ADMIN`, `DISPATCHER` | `NotificationJobResponse` (Status: PENDING) |
| `GET` | `/notifications/{id}/attempts` | View durable delivery attempt history and timings | `ADMIN`, `DISPATCHER` | `List<NotificationAttemptResponse>` |
| `GET` | `/quarantine/events` | List quarantined poison events (paginated) | `ADMIN`, `DISPATCHER` | `List<PoisonEventQuarantineResponse>` |
| `POST` | `/quarantine/events/{id}/replay` | Reprocess a quarantined event with corrected payload | `ADMIN`, `DISPATCHER` | `PoisonEventQuarantineResponse` (Status: REPLAYED) |

---

## 6. Observability & Micrometer Metrics

Low-cardinality operational metrics registered under `swiftdrop.notification.*` and `swiftdrop.kafka.*`:

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `swiftdrop.notification.attempts.total` | Counter | `channel`, `outcome` | Number of outbound notification delivery attempts |
| `swiftdrop.notification.attempt.duration` | Timer | `channel` | Latency distribution of transport dispatches |
| `swiftdrop.notification.retry.scheduled.total` | Counter | None | Count of notification retries scheduled with backoff |
| `swiftdrop.notification.exhausted.total` | Counter | None | Count of notifications reaching terminal EXHAUSTED state |
| `swiftdrop.notification.claims.recovered.total` | Counter | None | Count of stuck PROCESSING jobs recovered back to PENDING |
| `swiftdrop.notification.poison.quarantined.total` | Counter | `category` | Count of poison events isolated into quarantine table |
| `swiftdrop.notification.poison.quarantine.failed.total` | Counter | None | Count of failures during poison quarantine persistence |
| `swiftdrop.notification.replay.total` | Counter | `result` (`succeeded`, `rejected`) | Operator replay outcomes |
| `swiftdrop.kafka.consumer.processed.total` | Counter | `consumer_group`, `status` | Kafka consumer processing throughput |
