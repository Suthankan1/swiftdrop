-- Keep the lifecycle event that created each job so emails and webhooks report real parcel facts.
ALTER TABLE notifications ADD COLUMN event_snapshot TEXT;
ALTER TABLE webhook_deliveries ADD COLUMN event_snapshot TEXT;
