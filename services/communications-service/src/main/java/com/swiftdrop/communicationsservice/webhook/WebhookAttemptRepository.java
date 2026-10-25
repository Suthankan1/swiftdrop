package com.swiftdrop.communicationsservice.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookAttemptRepository extends JpaRepository<WebhookAttempt, UUID> {

    List<WebhookAttempt> findByDeliveryIdOrderByAttemptedAtDesc(UUID deliveryId);

    long countByDeliveryId(UUID deliveryId);
}
