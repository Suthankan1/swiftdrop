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

    List<Parcel> findByStatus(ParcelStatus status);

    boolean existsByTrackingNumber(String trackingNumber);

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
}
