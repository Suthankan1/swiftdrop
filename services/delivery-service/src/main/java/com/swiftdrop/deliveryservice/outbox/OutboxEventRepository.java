package com.swiftdrop.deliveryservice.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for managing OutboxEvent records.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    Optional<OutboxEvent> findByAggregateIdAndAggregateVersion(UUID aggregateId, Long aggregateVersion);

    List<OutboxEvent> findByAggregateIdOrderByAggregateVersionAsc(UUID aggregateId);

    long countByStatus(OutboxStatus status);

    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    Optional<Instant> findOldestCreatedAtByStatus(@Param("status") OutboxStatus status);

    /**
     * Atomically locks and returns candidate events for relaying.
     * Uses PostgreSQL native FOR UPDATE SKIP LOCKED to prevent concurrency contention
     * and double-processing across multiple workers.
     *
     * Orders by aggregate_id, aggregate_version to preserve per-parcel sequence.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'IN_FLIGHT' AND claim_expires_at <= :now)
               OR (status = 'FAILED' AND next_attempt_at <= :now AND attempt_count < :maxAttempts)
            ORDER BY aggregate_id, aggregate_version ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimNextBatch(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize
    );
}
