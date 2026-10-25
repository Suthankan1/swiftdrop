package com.swiftdrop.accountsservice.merchant;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for Merchant entities in accounts_db.
 */
public interface MerchantRepository extends Repository<Merchant, UUID> {

    Optional<Merchant> findById(UUID id);

    Optional<Merchant> findByKeycloakSub(String keycloakSub);

    Optional<Merchant> findByAccountProfileId(UUID accountProfileId);

    boolean existsByIdAndStatus(UUID id, MerchantStatus status);

    java.util.List<Merchant> findByStatusOrderByCreatedAtAsc(MerchantStatus status);

    long countByStatus(MerchantStatus status);

    Merchant save(Merchant merchant);
}
