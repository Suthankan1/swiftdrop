package com.swiftdrop.communicationsservice.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByMerchantIdAndActiveTrue(UUID merchantId);

    List<WebhookSubscription> findByActiveTrue();

    List<WebhookSubscription> findByMerchantId(UUID merchantId);

    List<WebhookSubscription> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    Optional<WebhookSubscription> findBySubscriptionIdAndMerchantId(UUID subscriptionId, UUID merchantId);
}
