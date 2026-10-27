package com.swiftdrop.communicationsservice.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findBySubscriptionIdAndEventId(UUID subscriptionId, UUID eventId);

    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqual(WebhookDeliveryStatus status, Instant nextAttemptAt);

    @Query("""
        SELECT d FROM WebhookDelivery d
        WHERE d.status = :status AND d.nextAttemptAt <= :now
        ORDER BY d.nextAttemptAt ASC
    """)
    List<WebhookDelivery> findDueDeliveries(
        @Param("status") WebhookDeliveryStatus status,
        @Param("now") Instant now,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE WebhookDelivery d
        SET d.status = :processingStatus, d.updatedAt = :now
        WHERE d.deliveryId = :deliveryId AND d.status = :pendingStatus
    """)
    int claimDelivery(
        @Param("deliveryId") UUID deliveryId,
        @Param("pendingStatus") WebhookDeliveryStatus pendingStatus,
        @Param("processingStatus") WebhookDeliveryStatus processingStatus,
        @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE WebhookDelivery d
        SET d.status = :processingStatus, d.updatedAt = :now
        WHERE d.deliveryId = :deliveryId
          AND (d.status = :pendingStatus OR (d.status = :processingStatus AND d.updatedAt <= :leaseCutoff))
    """)
    int claimDeliveryWithLease(
        @Param("deliveryId") UUID deliveryId,
        @Param("pendingStatus") WebhookDeliveryStatus pendingStatus,
        @Param("processingStatus") WebhookDeliveryStatus processingStatus,
        @Param("now") Instant now,
        @Param("leaseCutoff") Instant leaseCutoff
    );

    List<WebhookDelivery> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    List<WebhookDelivery> findByParcelIdOrderByCreatedAtDesc(UUID parcelId);

    List<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);

    long countBySubscriptionId(UUID subscriptionId);

    Optional<WebhookDelivery> findTopBySubscriptionIdAndParcelIdOrderByStateVersionDesc(UUID subscriptionId, UUID parcelId);

    List<WebhookDelivery> findBySubscriptionIdAndParcelId(UUID subscriptionId, UUID parcelId);

    List<WebhookDelivery> findByEventId(UUID eventId);

    List<WebhookDelivery> findByStatusAndUpdatedAtLessThanEqual(WebhookDeliveryStatus status, Instant cutoff);
}
