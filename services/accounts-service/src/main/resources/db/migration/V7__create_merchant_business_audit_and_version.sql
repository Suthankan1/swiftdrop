ALTER TABLE merchants
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_merchants_admin_listing
    ON merchants (created_at ASC, id ASC);

CREATE INDEX idx_merchants_status_created
    ON merchants (status, created_at ASC, id ASC);

CREATE TABLE merchant_business_audit (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    actor_sub VARCHAR(255) NOT NULL,
    action VARCHAR(50) NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    new_status VARCHAR(30) NOT NULL,
    reason TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(255),
    merchant_version BIGINT NOT NULL,
    outcome VARCHAR(50) NOT NULL,
    reconciliation_outcome VARCHAR(50),
    reconciliation_message TEXT,
    CONSTRAINT ck_merchant_audit_action CHECK (action IN ('APPROVE', 'SUSPEND')),
    CONSTRAINT ck_merchant_audit_outcome CHECK (outcome IN ('SUCCESS', 'CONFLICT', 'DENIED'))
);

CREATE INDEX idx_merchant_audit_merchant_id ON merchant_business_audit(merchant_id, occurred_at DESC);
CREATE INDEX idx_merchant_audit_actor_sub ON merchant_business_audit(actor_sub);
CREATE INDEX idx_merchant_audit_occurred_at ON merchant_business_audit(occurred_at DESC);
