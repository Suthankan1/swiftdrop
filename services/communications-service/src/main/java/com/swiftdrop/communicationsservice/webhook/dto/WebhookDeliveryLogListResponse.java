package com.swiftdrop.communicationsservice.webhook.dto;

import java.util.List;

/**
 * Paginated delivery history response for GET /api/v1/merchants/webhooks/{subscriptionId}/deliveries.
 */
public record WebhookDeliveryLogListResponse(
        long totalElements,
        List<WebhookDeliveryItem> deliveries
) {
}
