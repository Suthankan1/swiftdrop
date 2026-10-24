package com.swiftdrop.communicationsservice.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findBySubscriptionIdAndEventId(UUID subscriptionId, UUID eventId);

    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqual(WebhookDeliveryStatus status, Instant nextAttemptAt);

    List<WebhookDelivery> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    List<WebhookDelivery> findByParcelIdOrderByCreatedAtDesc(UUID parcelId);

    List<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);
}
