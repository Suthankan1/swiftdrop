-- Admin edits to a user's name or phone number are audited alongside enable/disable.
ALTER TABLE identity_reconciliation_audit DROP CONSTRAINT ck_audit_action;
ALTER TABLE identity_reconciliation_audit ADD CONSTRAINT ck_audit_action
    CHECK (action IN ('GRANT_ROLE', 'REVOKE_ROLE', 'STATUS_SYNC', 'PROVISION_STAFF', 'APPROVE_MERCHANT',
                      'SUSPEND_MERCHANT', 'REJECT_MERCHANT', 'RETRY_RECONCILIATION', 'ENABLE_USER', 'DISABLE_USER',
                      'UPDATE_USER'));
