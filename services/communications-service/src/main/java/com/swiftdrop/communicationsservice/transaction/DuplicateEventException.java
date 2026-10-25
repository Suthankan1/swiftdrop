package com.swiftdrop.communicationsservice.transaction;

import java.util.UUID;

public class DuplicateEventException extends RuntimeException {
    private final String consumerName;
    private final UUID eventId;

    public DuplicateEventException(String consumerName, UUID eventId) {
        super(String.format("Event %s has already been consumed by consumer '%s'", eventId, consumerName));
        this.consumerName = consumerName;
        this.eventId = eventId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public UUID getEventId() {
        return eventId;
    }
}
