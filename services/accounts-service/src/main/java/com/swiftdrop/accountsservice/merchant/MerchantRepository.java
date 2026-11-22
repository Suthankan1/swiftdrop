package com.swiftdrop.accountsservice.merchant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for Merchant entities in accounts_db.
 */
public interface MerchantRepository extends Repository<Merchant, UUID> {

    /** Serializes credential issuance per tenant, including when no keys exist yet. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Merchant m where m.id = :id")
    Optional<Merchant> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<Merchant> findById(UUID id);

    Optional<Merchant> findByKeycloakSub(String keycloakSub);

    Optional<Merchant> findByAccountProfileId(UUID accountProfileId);

    Optional<Merchant> findByRegistrationNumber(String registrationNumber);

    boolean existsByKeycloakSub(String keycloakSub);

    boolean existsByRegistrationNumber(String registrationNumber);

    boolean existsByIdAndStatus(UUID id, MerchantStatus status);

    java.util.List<Merchant> findByStatusOrderByCreatedAtAsc(MerchantStatus status);

    Page<Merchant> findAll(Pageable pageable);

    Page<Merchant> findByStatus(MerchantStatus status, Pageable pageable);

    long countByStatus(MerchantStatus status);

    Merchant save(Merchant merchant);

    default Merchant saveAndFlush(Merchant merchant) {
        return save(merchant);
    }
}
