package com.swiftdrop.accountsservice.merchant;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for durable MerchantBusinessAudit records in accounts_db.
 */
public interface MerchantBusinessAuditRepository extends Repository<MerchantBusinessAudit, UUID> {

    MerchantBusinessAudit save(MerchantBusinessAudit audit);

    Optional<MerchantBusinessAudit> findById(UUID id);

    List<MerchantBusinessAudit> findByMerchantIdOrderByOccurredAtDesc(UUID merchantId);

    List<MerchantBusinessAudit> findByActorSubOrderByOccurredAtDesc(String actorSub);

    long count();
}
