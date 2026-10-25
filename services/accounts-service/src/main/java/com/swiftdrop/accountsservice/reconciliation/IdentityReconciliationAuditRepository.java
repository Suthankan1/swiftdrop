package com.swiftdrop.accountsservice.reconciliation;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for IdentityReconciliationAudit in accounts_db.
 */
public interface IdentityReconciliationAuditRepository extends Repository<IdentityReconciliationAudit, UUID> {

    Optional<IdentityReconciliationAudit> findById(UUID id);

    List<IdentityReconciliationAudit> findByTargetSub(String targetSub);

    List<IdentityReconciliationAudit> findByTargetSubOrderByCreatedAtDesc(String targetSub);

    IdentityReconciliationAudit save(IdentityReconciliationAudit audit);
}
