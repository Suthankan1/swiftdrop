package com.swiftdrop.communicationsservice.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ConsumedEventId implements Serializable {

    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    public ConsumedEventId() {
    }

    public ConsumedEventId(String consumerName, UUID eventId) {
        this.consumerName = consumerName;
        this.eventId = eventId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public void setConsumerName(String consumerName) {
        this.consumerName = consumerName;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConsumedEventId that = (ConsumedEventId) o;
        return Objects.equals(consumerName, that.consumerName) && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consumerName, eventId);
    }

    @Override
    public String toString() {
        return "ConsumedEventId{" +
                "consumerName='" + consumerName + '\'' +
                ", eventId=" + eventId +
                '}';
    }
}
