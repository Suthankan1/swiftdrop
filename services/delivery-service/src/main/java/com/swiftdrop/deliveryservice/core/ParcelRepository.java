package com.swiftdrop.deliveryservice.core;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for the Delivery aggregate root (Parcel).
 */
@Repository
public interface ParcelRepository extends JpaRepository<Parcel, UUID> {

    Optional<Parcel> findByTrackingNumber(String trackingNumber);

    List<Parcel> findBySenderSub(String senderSub);

    Page<Parcel> findBySenderSubOrderByCreatedAtDesc(String senderSub, Pageable pageable);

    List<Parcel> findByMerchantId(UUID merchantId);

    Page<Parcel> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<Parcel> findByMerchantIdOrSenderSubOrderByCreatedAtDesc(UUID merchantId, String senderSub, Pageable pageable);

    List<Parcel> findByStatus(ParcelStatus status);

    boolean existsByTrackingNumber(String trackingNumber);

    boolean existsByQuoteId(UUID quoteId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p FROM Parcel p
        WHERE p.status = com.swiftdrop.deliveryservice.core.ParcelStatus.CREATED
        AND NOT EXISTS (
            SELECT 1 FROM DeliveryAssignment da
            WHERE da.parcelId = p.parcelId AND da.active = true
        )
        ORDER BY p.createdAt ASC, p.parcelId ASC
    """)
    Page<Parcel> findUnassignedParcels(Pageable pageable);

    /**
     * Unassigned queue filtered to parcels whose pickup or drop-off zone code (from the verified quote snapshot
     * captured at booking) matches {@code zone}. Filtering happens in the database so paging stays correct.
     */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT p.* FROM parcels p
        WHERE p.status = 'CREATED'
        AND (p.quote_snapshot ->> 'pickupZone' = :zone OR p.quote_snapshot ->> 'dropoffZone' = :zone)
        AND NOT EXISTS (SELECT 1 FROM delivery_assignments da WHERE da.parcel_id = p.parcel_id AND da.active = true)
        ORDER BY p.created_at ASC, p.parcel_id ASC
        """, countQuery = """
        SELECT COUNT(*) FROM parcels p
        WHERE p.status = 'CREATED'
        AND (p.quote_snapshot ->> 'pickupZone' = :zone OR p.quote_snapshot ->> 'dropoffZone' = :zone)
        AND NOT EXISTS (SELECT 1 FROM delivery_assignments da WHERE da.parcel_id = p.parcel_id AND da.active = true)
        """, nativeQuery = true)
    Page<Parcel> findUnassignedParcelsInZone(@org.springframework.data.repository.query.Param("zone") String zone,
                                             Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(p) FROM Parcel p
        WHERE p.createdAt >= :startTime AND p.createdAt < :endTime
    """)
    long countCreatedBetween(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(p) FROM Parcel p
        WHERE p.status IN (
            com.swiftdrop.deliveryservice.core.ParcelStatus.PICKED_UP,
            com.swiftdrop.deliveryservice.core.ParcelStatus.IN_TRANSIT,
            com.swiftdrop.deliveryservice.core.ParcelStatus.OUT_FOR_DELIVERY
        )
        AND p.createdAt <= :endTime
    """)
    long countInTransitBeforeOrAt(
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime);

    /** Zone-scoped variant of {@link #countCreatedBetween}: pickup or drop-off zone from the quote snapshot. */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT COUNT(*) FROM parcels p
        WHERE p.created_at >= :startTime AND p.created_at < :endTime
        AND (p.quote_snapshot ->> 'pickupZone' = :zone OR p.quote_snapshot ->> 'dropoffZone' = :zone)
        """, nativeQuery = true)
    long countCreatedBetweenInZone(
            @org.springframework.data.repository.query.Param("startTime") java.time.Instant startTime,
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime,
            @org.springframework.data.repository.query.Param("zone") String zone);

    /** Zone-scoped variant of {@link #countInTransitBeforeOrAt}. */
    @org.springframework.data.jpa.repository.Query(value = """
        SELECT COUNT(*) FROM parcels p
        WHERE p.status IN ('PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY')
        AND p.created_at <= :endTime
        AND (p.quote_snapshot ->> 'pickupZone' = :zone OR p.quote_snapshot ->> 'dropoffZone' = :zone)
        """, nativeQuery = true)
    long countInTransitBeforeOrAtInZone(
            @org.springframework.data.repository.query.Param("endTime") java.time.Instant endTime,
            @org.springframework.data.repository.query.Param("zone") String zone);

    List<Parcel> findBySenderSubAndParcelIdIn(String senderSub, java.util.Collection<UUID> parcelIds);

    List<Parcel> findByMerchantIdAndParcelIdIn(UUID merchantId, java.util.Collection<UUID> parcelIds);

    int countBySenderSub(String senderSub);

    int countByMerchantId(UUID merchantId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(p) FROM Parcel p
        WHERE p.senderSub = :senderSub
        AND p.status NOT IN (com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED, com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED)
    """)
    int countActiveBySenderSub(@org.springframework.data.repository.query.Param("senderSub") String senderSub);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(p) FROM Parcel p
        WHERE p.senderSub = :senderSub
        AND p.status IN (com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED, com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED)
    """)
    int countCompletedBySenderSub(@org.springframework.data.repository.query.Param("senderSub") String senderSub);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(p) FROM Parcel p
        WHERE p.merchantId = :merchantId
        AND p.status NOT IN (com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED, com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED)
    """)
    int countActiveByMerchantId(@org.springframework.data.repository.query.Param("merchantId") UUID merchantId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(p) FROM Parcel p
        WHERE p.merchantId = :merchantId
        AND p.status = com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED
    """)
    int countDeliveredByMerchantId(@org.springframework.data.repository.query.Param("merchantId") UUID merchantId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(p) FROM Parcel p
        WHERE p.merchantId = :merchantId
        AND p.status = com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED
    """)
    int countFailedByMerchantId(@org.springframework.data.repository.query.Param("merchantId") UUID merchantId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p FROM Parcel p
        WHERE p.senderSub = :senderSub
        AND p.status NOT IN (com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED, com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED)
        ORDER BY p.createdAt DESC, p.parcelId ASC
    """)
    Page<Parcel> findActiveBySenderSub(
            @org.springframework.data.repository.query.Param("senderSub") String senderSub,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p FROM Parcel p
        WHERE p.senderSub = :senderSub
        AND p.status IN (com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED, com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED)
        ORDER BY p.createdAt DESC, p.parcelId ASC
    """)
    Page<Parcel> findCompletedBySenderSub(
            @org.springframework.data.repository.query.Param("senderSub") String senderSub,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p FROM Parcel p
        WHERE p.senderSub = :senderSub
        AND p.status = :status
        ORDER BY p.createdAt DESC, p.parcelId ASC
    """)
    Page<Parcel> findBySenderSubAndStatus(
            @org.springframework.data.repository.query.Param("senderSub") String senderSub,
            @org.springframework.data.repository.query.Param("status") ParcelStatus status,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p FROM Parcel p
        WHERE p.merchantId = :merchantId
        AND p.status NOT IN (com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED, com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED)
        ORDER BY p.createdAt DESC, p.parcelId ASC
    """)
    Page<Parcel> findActiveByMerchantId(
            @org.springframework.data.repository.query.Param("merchantId") UUID merchantId,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT p FROM Parcel p
        WHERE p.merchantId = :merchantId
        AND p.status = :status
        ORDER BY p.createdAt DESC, p.parcelId ASC
    """)
    Page<Parcel> findByMerchantIdAndStatus(
            @org.springframework.data.repository.query.Param("merchantId") UUID merchantId,
            @org.springframework.data.repository.query.Param("status") ParcelStatus status,
            Pageable pageable);
}
