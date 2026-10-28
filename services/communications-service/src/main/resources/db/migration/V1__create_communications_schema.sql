-- SwiftDrop Communications Service - Initial Schema
-- Milestone: November 10 Baseline (Architecture v1.3)
-- Schema Ownership: communications_db

-- Table 1: Consumed Events Inbox (Per-Consumer-Group Durable Deduplication)
CREATE TABLE consumed_events (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    parcel_id UUID NOT NULL,
    merchant_id UUID,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    scheduled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_consumed_events PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT chk_consumed_events_consumer_name CHECK (LENGTH(TRIM(consumer_name)) > 0),
    CONSTRAINT chk_consumed_events_event_type CHECK (LENGTH(TRIM(event_type)) > 0)
);

CREATE INDEX idx_consumed_events_parcel_id ON consumed_events (parcel_id);
CREATE INDEX idx_consumed_events_merchant_id ON consumed_events (merchant_id);

-- Table 2: Notification Jobs
CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    recipient_sub VARCHAR(255) NOT NULL,
    parcel_id UUID NOT NULL,
    channel VARCHAR(50) NOT NULL,
    destination_ref VARCHAR(255) NOT NULL,
    template VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notifications_event_recipient_channel UNIQUE (event_id, recipient_sub, channel),
    CONSTRAINT chk_notifications_channel CHECK (channel IN ('EMAIL', 'SMS_MOCK')),
    CONSTRAINT chk_notifications_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'EXHAUSTED')),
    CONSTRAINT chk_notifications_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_notifications_recipient_sub CHECK (LENGTH(TRIM(recipient_sub)) > 0),
    CONSTRAINT chk_notifications_destination_ref CHECK (LENGTH(TRIM(destination_ref)) > 0)
);

CREATE INDEX idx_notifications_pending ON notifications (next_attempt_at, status) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_notifications_parcel_id ON notifications (parcel_id);
CREATE INDEX idx_notifications_recipient_sub ON notifications (recipient_sub);

-- Table 3: Merchant Webhook Subscriptions
CREATE TABLE webhook_subscriptions (
    subscription_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    https_url VARCHAR(1000) NOT NULL,
    allowed_event_types VARCHAR(255) NOT NULL,
    secret_ref VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_webhook_subscriptions_https CHECK (https_url ~* '^https://'),
    CONSTRAINT chk_webhook_subscriptions_secret_ref CHECK (LENGTH(TRIM(secret_ref)) > 0)
);

CREATE INDEX idx_webhook_subscriptions_merchant_id ON webhook_subscriptions (merchant_id);

-- Table 4: Durable Webhook Delivery Jobs
CREATE TABLE webhook_deliveries (
    delivery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(subscription_id) ON DELETE RESTRICT,
    event_id UUID NOT NULL,
    parcel_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    state_version INT NOT NULL,
    signed_payload_digest VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_webhook_deliveries_subscription_event UNIQUE (subscription_id, event_id),
    CONSTRAINT chk_webhook_deliveries_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'EXHAUSTED')),
    CONSTRAINT chk_webhook_deliveries_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_webhook_deliveries_pending ON webhook_deliveries (next_attempt_at, status) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX idx_webhook_deliveries_merchant_id ON webhook_deliveries (merchant_id);
CREATE INDEX idx_webhook_deliveries_parcel_id ON webhook_deliveries (parcel_id);

-- Table 5: Webhook Delivery Attempt History
CREATE TABLE webhook_attempts (
    attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL REFERENCES webhook_deliveries(delivery_id) ON DELETE CASCADE,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    http_status_code INT,
    response_time_ms INT,
    error_summary VARCHAR(500)
);

CREATE INDEX idx_webhook_attempts_delivery_id ON webhook_attempts (delivery_id);
