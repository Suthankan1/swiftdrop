CREATE TABLE identity_reconciliation_audit (
    id UUID PRIMARY KEY,
    target_sub VARCHAR(255) NOT NULL,
    target_role VARCHAR(50) NOT NULL,
    action VARCHAR(50) NOT NULL,
    status_before VARCHAR(50),
    status_after VARCHAR(50),
    outcome VARCHAR(50) NOT NULL,
    initiated_by VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255),
    attempt_count INT NOT NULL DEFAULT 1,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'NO_OP', 'FAILED', 'PENDING_RETRY', 'DENIED')),
    CONSTRAINT ck_audit_action CHECK (action IN ('GRANT_ROLE', 'REVOKE_ROLE', 'STATUS_SYNC', 'PROVISION_STAFF', 'APPROVE_MERCHANT', 'SUSPEND_MERCHANT', 'RETRY_RECONCILIATION'))
);

CREATE INDEX idx_reconciliation_audit_target_sub ON identity_reconciliation_audit(target_sub);
CREATE INDEX idx_reconciliation_audit_outcome ON identity_reconciliation_audit(outcome);
CREATE INDEX idx_reconciliation_audit_created_at ON identity_reconciliation_audit(created_at);
