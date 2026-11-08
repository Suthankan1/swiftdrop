package com.swiftdrop.deliveryservice.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for idempotency records.
 */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    @Query("SELECT r FROM IdempotencyRecord r WHERE r.principalOrTenant = :principalOrTenant AND r.idempotencyKey = :idempotencyKey")
    Optional<IdempotencyRecord> findByPrincipalOrTenantAndIdempotencyKey(
            @Param("principalOrTenant") String principalOrTenant,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Query("SELECT count(r) > 0 FROM IdempotencyRecord r WHERE r.principalOrTenant = :principalOrTenant AND r.idempotencyKey = :idempotencyKey")
    boolean existsByPrincipalOrTenantAndIdempotencyKey(
            @Param("principalOrTenant") String principalOrTenant,
            @Param("idempotencyKey") String idempotencyKey
    );

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
