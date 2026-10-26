package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.proof.retention.RetentionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProofOfDeliveryRepository extends JpaRepository<ProofOfDelivery, UUID> {

    Optional<ProofOfDelivery> findByParcelId(UUID parcelId);

    @Query("SELECT p FROM ProofOfDelivery p WHERE p.retentionStatus = :status AND p.objectReference IS NOT NULL AND p.createdAt <= :olderThan ORDER BY p.createdAt ASC")
    List<ProofOfDelivery> findCandidatesForRetention(
            @Param("status") RetentionStatus status,
            @Param("olderThan") Instant olderThan,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProofOfDelivery p WHERE p.proofId = :proofId")
    Optional<ProofOfDelivery> findByIdForUpdate(@Param("proofId") UUID proofId);
}
