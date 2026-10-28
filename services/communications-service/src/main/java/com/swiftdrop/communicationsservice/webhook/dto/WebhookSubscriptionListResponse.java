package com.swiftdrop.communicationsservice.webhook.dto;

import java.util.List;

/**
 * List response for GET /api/v1/merchants/webhooks.
 */
public record WebhookSubscriptionListResponse(
        List<WebhookSubscriptionResponse> subscriptions
) {
}
