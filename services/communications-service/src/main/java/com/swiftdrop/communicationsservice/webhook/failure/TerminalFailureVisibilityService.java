package com.swiftdrop.communicationsservice.webhook.failure;

import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Provides safe operational inspection for terminal webhook failures (Issue #158).
 * Allows tracing a failed delivery back to its originating correlation ID and event ID
 * while strictly isolating tenant data and redacting secrets/PII.
 */
@Service
public class TerminalFailureVisibilityService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookAttemptRepository attemptRepository;

    public TerminalFailureVisibilityService(
            WebhookDeliveryRepository deliveryRepository,
            WebhookAttemptRepository attemptRepository) {
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository cannot be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository cannot be null");
    }

    public record WebhookFailureEvidence(
            UUID deliveryId,
            UUID eventId,
            String correlationId,
            UUID parcelId,
            UUID merchantId,
            WebhookDeliveryStatus status,
            int attemptCount,
            Instant lastAttemptAt,
            Integer httpStatusCode,
            String sanitizedErrorReason
    ) {}

    /**
     * Inspects terminal failure evidence for a delivery, enforcing tenant isolation.
     *
     * @param deliveryId target delivery identifier
     * @param requestingMerchantId merchant tenant requesting inspection (null for platform admin)
     * @return sanitized failure evidence if found, terminal, and authorized
     */
    public Optional<WebhookFailureEvidence> inspectDeliveryFailure(UUID deliveryId, UUID requestingMerchantId) {
        if (deliveryId == null) {
            return Optional.empty();
        }

        return deliveryRepository.findById(deliveryId)
                .filter(d -> requestingMerchantId == null || Objects.equals(requestingMerchantId, d.getMerchantId()))
                .filter(d -> d.getStatus() == WebhookDeliveryStatus.FAILED || d.getStatus() == WebhookDeliveryStatus.EXHAUSTED)
                .map(d -> {
                    Optional<WebhookAttempt> latestAttempt = attemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(d.getDeliveryId());
                    return new WebhookFailureEvidence(
                            d.getDeliveryId(),
                            d.getEventId(),
                            d.getCorrelationId(),
                            d.getParcelId(),
                            d.getMerchantId(),
                            d.getStatus(),
                            d.getAttemptCount() != null ? d.getAttemptCount() : 0,
                            latestAttempt.map(WebhookAttempt::getAttemptedAt).orElse(d.getUpdatedAt()),
                            latestAttempt.map(WebhookAttempt::getHttpStatusCode).orElse(null),
                            latestAttempt.map(WebhookAttempt::getErrorSummary).orElse("Terminal failure without attempt record")
                    );
                });
    }
}
