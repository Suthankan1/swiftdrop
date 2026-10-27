-- SwiftDrop Communications Service - Issue #155 Schema Migration
-- Milestone: November 10 Baseline (Architecture v1.3)
-- Schema Ownership: communications_db
-- Webhook Delivery Replay Audit Records (DEC-COM-01 / DEC-COM-04)

CREATE TABLE webhook_replays (
    replay_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL REFERENCES webhook_deliveries(delivery_id) ON DELETE RESTRICT,
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(subscription_id) ON DELETE RESTRICT,
    merchant_id UUID NOT NULL,
    actor_sub VARCHAR(255) NOT NULL,
    actor_role VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    replayed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_webhook_replays_actor_sub CHECK (LENGTH(TRIM(actor_sub)) > 0),
    CONSTRAINT chk_webhook_replays_reason CHECK (LENGTH(TRIM(reason)) > 0),
    CONSTRAINT chk_webhook_replays_prev_status CHECK (previous_status IN ('FAILED', 'EXHAUSTED'))
);

CREATE INDEX idx_webhook_replays_delivery_id ON webhook_replays (delivery_id);
CREATE INDEX idx_webhook_replays_subscription_id ON webhook_replays (subscription_id);
CREATE INDEX idx_webhook_replays_merchant_id ON webhook_replays (merchant_id);
