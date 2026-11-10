-- Merchant rejection (REJECT) and admin user enable/disable are audited like the existing decisions.
ALTER TABLE merchant_business_audit DROP CONSTRAINT ck_merchant_audit_action;
ALTER TABLE merchant_business_audit ADD CONSTRAINT ck_merchant_audit_action
    CHECK (action IN ('APPROVE', 'SUSPEND', 'REJECT'));

ALTER TABLE identity_reconciliation_audit DROP CONSTRAINT ck_audit_action;
ALTER TABLE identity_reconciliation_audit ADD CONSTRAINT ck_audit_action
    CHECK (action IN ('GRANT_ROLE', 'REVOKE_ROLE', 'STATUS_SYNC', 'PROVISION_STAFF', 'APPROVE_MERCHANT',
                      'SUSPEND_MERCHANT', 'REJECT_MERCHANT', 'RETRY_RECONCILIATION', 'ENABLE_USER', 'DISABLE_USER'));
