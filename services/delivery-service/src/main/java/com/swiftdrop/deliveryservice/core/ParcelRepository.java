package com.swiftdrop.deliveryservice.core;

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

    List<Parcel> findByMerchantId(UUID merchantId);

    List<Parcel> findByStatus(ParcelStatus status);

    boolean existsByTrackingNumber(String trackingNumber);
}
