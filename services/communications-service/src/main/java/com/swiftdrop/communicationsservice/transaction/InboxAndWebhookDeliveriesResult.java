package com.swiftdrop.communicationsservice.transaction;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import java.util.List;

public record InboxAndWebhookDeliveriesResult(
    ConsumedEvent consumedEvent,
    List<WebhookDelivery> webhookDeliveries,
    boolean newlyPersisted
) {}
