package com.swiftdrop.communicationsservice.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookReplayAuditRepository extends JpaRepository<WebhookReplayAudit, UUID> {

    List<WebhookReplayAudit> findByDeliveryIdOrderByReplayedAtDesc(UUID deliveryId);

    List<WebhookReplayAudit> findBySubscriptionIdOrderByReplayedAtDesc(UUID subscriptionId);

    long countByDeliveryId(UUID deliveryId);
}
