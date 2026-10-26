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
}
