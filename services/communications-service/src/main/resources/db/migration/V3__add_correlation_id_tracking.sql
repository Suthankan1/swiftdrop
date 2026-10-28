-- V3__add_correlation_id_tracking.sql
-- Add correlation_id tracking to consumed events inbox and downstream delivery jobs (Issue #158)

ALTER TABLE consumed_events
    ADD COLUMN correlation_id VARCHAR(64);

ALTER TABLE notifications
    ADD COLUMN correlation_id VARCHAR(64);

ALTER TABLE webhook_deliveries
    ADD COLUMN correlation_id VARCHAR(64);

CREATE INDEX idx_consumed_events_correlation_id ON consumed_events (correlation_id);
CREATE INDEX idx_notifications_correlation_id ON notifications (correlation_id);
CREATE INDEX idx_webhook_deliveries_correlation_id ON webhook_deliveries (correlation_id);
