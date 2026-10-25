package com.swiftdrop.deliveryservice.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for idempotency records.
 */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByPrincipalOrTenantAndIdempotencyKey(String principalOrTenant, String idempotencyKey);

    boolean existsByPrincipalOrTenantAndIdempotencyKey(String principalOrTenant, String idempotencyKey);
}
