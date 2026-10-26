package com.swiftdrop.deliveryservice.status;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for immutable status history audit records.
 */
@Repository
public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {

    List<StatusHistory> findByParcelIdOrderByResultingVersionAsc(UUID parcelId);

    Page<StatusHistory> findByParcelIdOrderByResultingVersionAsc(UUID parcelId, Pageable pageable);

    List<StatusHistory> findByParcelIdOrderByOccurredAtDesc(UUID parcelId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(DISTINCT s.parcelId) FROM StatusHistory s
        WHERE s.newStatus = com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED
        AND s.occurredAt >= :startTime AND s.occurredAt < :endTime
    """)
    long countDeliveredBetween(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(DISTINCT s.parcelId) FROM StatusHistory s
        WHERE s.newStatus = com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED
        AND s.occurredAt >= :startTime AND s.occurredAt < :endTime
    """)
    long countFailedBetween(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p.createdAt, MIN(s.occurredAt)
        FROM StatusHistory s, Parcel p
        WHERE s.parcelId = p.parcelId
        AND s.newStatus = com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED
        AND s.occurredAt >= :startTime AND s.occurredAt < :endTime
        GROUP BY p.parcelId, p.createdAt
    """)
    List<Object[]> findDeliveredParcelDurations(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);
}
