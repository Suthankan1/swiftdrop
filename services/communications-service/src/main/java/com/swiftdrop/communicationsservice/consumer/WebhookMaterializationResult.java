package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain value object representing the outcome of webhook delivery job materialization.
 */
public record WebhookMaterializationResult(
        UUID eventId,
        boolean newlyPersisted,
        boolean isDuplicate,
        int jobsCreatedCount,
        List<WebhookDelivery> jobs,
        String summary
) {

    public WebhookMaterializationResult {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        jobs = jobs != null ? Collections.unmodifiableList(jobs) : List.of();
    }

    public static WebhookMaterializationResult duplicate(UUID eventId) {
        return new WebhookMaterializationResult(
                eventId,
                false,
                true,
                0,
                List.of(),
                "DUPLICATE_EVENT_DETECTED"
        );
    }

    public static WebhookMaterializationResult retail(UUID eventId) {
        return new WebhookMaterializationResult(
                eventId,
                true,
                false,
                0,
                List.of(),
                "RETAIL_PARCEL_NO_MERCHANT"
        );
    }

    public static WebhookMaterializationResult noActiveSubscriptions(UUID eventId, UUID merchantId) {
        return new WebhookMaterializationResult(
                eventId,
                true,
                false,
                0,
                List.of(),
                "NO_ACTIVE_SUBSCRIPTIONS_FOR_MERCHANT_" + merchantId
        );
    }

    public static WebhookMaterializationResult stale(UUID eventId, UUID parcelId) {
        return new WebhookMaterializationResult(
                eventId,
                true,
                false,
                0,
                List.of(),
                "SUPERSEDED_STALE_EVENT_FOR_PARCEL_" + parcelId
        );
    }

    public static WebhookMaterializationResult persisted(UUID eventId, List<WebhookDelivery> jobs) {
        int count = jobs != null ? jobs.size() : 0;
        return new WebhookMaterializationResult(
                eventId,
                true,
                false,
                count,
                jobs,
                "MATERIALIZED_" + count + "_WEBHOOK_JOBS"
        );
    }
}
