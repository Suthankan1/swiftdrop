package com.swiftdrop.deliveryservice.proof;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProofCleanupTaskRepository extends JpaRepository<ProofCleanupTask, UUID> {

    List<ProofCleanupTask> findByStatus(String status);

    List<ProofCleanupTask> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ProofCleanupTask t WHERE t.taskId = :taskId")
    Optional<ProofCleanupTask> findByIdForUpdate(@Param("taskId") UUID taskId);
}
