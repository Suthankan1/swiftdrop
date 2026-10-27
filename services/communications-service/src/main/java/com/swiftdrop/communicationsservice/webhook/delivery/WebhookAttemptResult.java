package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;

import java.util.UUID;

/**
 * Value object summarizing the completed outcome of a webhook delivery attempt execution.
 */
public record WebhookAttemptResult(
        UUID deliveryId,
        UUID attemptId,
        WebhookDeliveryStatus deliveryStatus,
        int attemptCount,
        WebhookResponseClassification classification,
        WebhookAttempt attempt
) {
    public boolean isSuccess() {
        return classification != null && classification.isSuccess();
    }

    public boolean isRetryable() {
        return classification != null && classification.isRetryable();
    }
}
