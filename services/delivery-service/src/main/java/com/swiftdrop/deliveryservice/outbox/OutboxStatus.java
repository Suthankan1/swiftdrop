package com.swiftdrop.deliveryservice.outbox;

/**
 * Lifecycle status of an outbox event.
 */
public enum OutboxStatus {
    /**
     * Freshly committed event awaiting relay pickup.
     */
    PENDING,

    /**
     * Claimed by an active relay worker and currently in-flight to Kafka.
     */
    IN_FLIGHT,

    /**
     * Successfully published and acknowledged by Kafka broker.
     */
    PUBLISHED,

    /**
     * Publication attempt failed, scheduled for retry with backoff.
     */
    FAILED,

    /**
     * Maximum retry attempts exceeded (poison event). Moved to dead-letter state.
     */
    DEAD_LETTER
}
