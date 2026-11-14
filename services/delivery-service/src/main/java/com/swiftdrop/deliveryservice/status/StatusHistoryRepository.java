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
        WHERE s.occurredAt >= :startTime AND s.occurredAt < :endTime
    """)
    long countDistinctParcelsWithActivityBetween(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);

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

    /** Zone-scoped variant of {@link #countDistinctParcelsWithActivityBetween}: pickup or drop-off zone from the parcel's quote snapshot. */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT COUNT(DISTINCT s.parcel_id) FROM status_history s JOIN parcels p ON p.parcel_id = s.parcel_id
        WHERE s.occurred_at >= :startTime AND s.occurred_at < :endTime
        AND (
            UPPER(p.quote_snapshot ->> 'pickupZone') = UPPER(:zone)
            OR UPPER(p.quote_snapshot ->> 'dropoffZone') = UPPER(:zone)
            OR UPPER(REPLACE(COALESCE(p.quote_snapshot ->> 'pickupZone', ''), '-', '_')) = UPPER(REPLACE(:zone, '-', '_'))
            OR UPPER(REPLACE(COALESCE(p.quote_snapshot ->> 'dropoffZone', ''), '-', '_')) = UPPER(REPLACE(:zone, '-', '_'))
            OR UPPER(p.quote_snapshot ->> 'pickupZoneName') = UPPER(:zone)
            OR UPPER(p.quote_snapshot ->> 'dropoffZoneName') = UPPER(:zone)
            OR (UPPER(:zone) LIKE '%COLOMBO%' AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_1'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_1'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%COLOMBO%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%COLOMBO%'
            ))
            OR (UPPER(:zone) LIKE '%WESTERN%' AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_2'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_2'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%WESTERN%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%WESTERN%'
            ))
            OR ((UPPER(:zone) LIKE '%NATIONAL%' OR UPPER(:zone) LIKE '%ISLAND%') AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_3'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_3'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%ISLAND%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%ISLAND%'
            ))
        )
        """, nativeQuery = true)
    long countDistinctParcelsWithActivityBetweenInZone(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime,
            @org.springframework.data.repository.query.Param("zone") String zone);

    /** Zone-scoped variant of {@link #countDeliveredBetween}: pickup or drop-off zone from the parcel's quote snapshot. */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT COUNT(DISTINCT s.parcel_id) FROM status_history s JOIN parcels p ON p.parcel_id = s.parcel_id
        WHERE s.occurred_at >= :startTime AND s.occurred_at < :endTime
        AND s.new_status = 'DELIVERED'
        AND (
            UPPER(p.quote_snapshot ->> 'pickupZone') = UPPER(:zone)
            OR UPPER(p.quote_snapshot ->> 'dropoffZone') = UPPER(:zone)
            OR UPPER(REPLACE(COALESCE(p.quote_snapshot ->> 'pickupZone', ''), '-', '_')) = UPPER(REPLACE(:zone, '-', '_'))
            OR UPPER(REPLACE(COALESCE(p.quote_snapshot ->> 'dropoffZone', ''), '-', '_')) = UPPER(REPLACE(:zone, '-', '_'))
            OR UPPER(p.quote_snapshot ->> 'pickupZoneName') = UPPER(:zone)
            OR UPPER(p.quote_snapshot ->> 'dropoffZoneName') = UPPER(:zone)
            OR (UPPER(:zone) LIKE '%COLOMBO%' AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_1'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_1'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%COLOMBO%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%COLOMBO%'
            ))
            OR (UPPER(:zone) LIKE '%WESTERN%' AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_2'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_2'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%WESTERN%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%WESTERN%'
            ))
            OR ((UPPER(:zone) LIKE '%NATIONAL%' OR UPPER(:zone) LIKE '%ISLAND%') AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_3'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_3'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%ISLAND%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%ISLAND%'
            ))
        )
        """, nativeQuery = true)
    long countDeliveredBetweenInZone(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime,
            @org.springframework.data.repository.query.Param("zone") String zone);

    /** Zone-scoped variant of {@link #countFailedBetween}: pickup or drop-off zone from the parcel's quote snapshot. */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT COUNT(DISTINCT s.parcel_id) FROM status_history s JOIN parcels p ON p.parcel_id = s.parcel_id
        WHERE s.occurred_at >= :startTime AND s.occurred_at < :endTime
        AND s.new_status = 'FAILED'
        AND (
            UPPER(p.quote_snapshot ->> 'pickupZone') = UPPER(:zone)
            OR UPPER(p.quote_snapshot ->> 'dropoffZone') = UPPER(:zone)
            OR UPPER(REPLACE(COALESCE(p.quote_snapshot ->> 'pickupZone', ''), '-', '_')) = UPPER(REPLACE(:zone, '-', '_'))
            OR UPPER(REPLACE(COALESCE(p.quote_snapshot ->> 'dropoffZone', ''), '-', '_')) = UPPER(REPLACE(:zone, '-', '_'))
            OR UPPER(p.quote_snapshot ->> 'pickupZoneName') = UPPER(:zone)
            OR UPPER(p.quote_snapshot ->> 'dropoffZoneName') = UPPER(:zone)
            OR (UPPER(:zone) LIKE '%COLOMBO%' AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_1'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_1'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%COLOMBO%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%COLOMBO%'
            ))
            OR (UPPER(:zone) LIKE '%WESTERN%' AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_2'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_2'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%WESTERN%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%WESTERN%'
            ))
            OR ((UPPER(:zone) LIKE '%NATIONAL%' OR UPPER(:zone) LIKE '%ISLAND%') AND (
                UPPER(COALESCE(p.quote_snapshot ->> 'pickupZone', '')) = 'ZONE_3'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZone', '')) = 'ZONE_3'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'pickupZoneName', '')) LIKE '%ISLAND%'
                OR UPPER(COALESCE(p.quote_snapshot ->> 'dropoffZoneName', '')) LIKE '%ISLAND%'
            ))
        )
        """, nativeQuery = true)
    long countFailedBetweenInZone(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime,
            @org.springframework.data.repository.query.Param("zone") String zone);

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

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(s) FROM StatusHistory s
        WHERE s.actorSub = :courierSub
        AND s.newStatus = com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED
        AND s.occurredAt >= :startTime AND s.occurredAt < :endTime
    """)
    int countCourierDeliveredBetween(
            @org.springframework.data.repository.query.Param("courierSub") String courierSub,
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(s) FROM StatusHistory s
        WHERE s.actorSub = :courierSub
        AND s.newStatus = com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED
        AND s.occurredAt >= :startTime AND s.occurredAt < :endTime
    """)
    int countCourierFailedBetween(
            @org.springframework.data.repository.query.Param("courierSub") String courierSub,
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);

    List<StatusHistory> findByParcelIdInOrderByResultingVersionAsc(java.util.Collection<UUID> parcelIds);

    @org.springframework.data.jpa.repository.Query("""
        SELECT s FROM StatusHistory s
        WHERE s.parcelId = :parcelId
        ORDER BY s.resultingVersion ASC, s.historyId ASC
    """)
    Page<StatusHistory> findByParcelIdOrderByVersionAsc(
            @org.springframework.data.repository.query.Param("parcelId") UUID parcelId,
            Pageable pageable);
}
