package com.swiftdrop.deliveryservice.outbox;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Factory for constructing schema-compliant OutboxEvent records adhering strictly to
 * contracts/events/parcel.status.changed.v1.schema.json and DEC-EVT-01..04.
 */
@Component
public class OutboxEventFactory {

    private final JsonMapper jsonMapper;

    public OutboxEventFactory() {
        this.jsonMapper = JsonMapper.builder().build();
    }

    public OutboxEventFactory(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
    }

    /**
     * Constructs an OutboxEvent for an authoritative parcel status transition with defaults for timestamp and version.
     */
    public OutboxEvent createParcelStatusChangedEvent(
            Parcel parcel,
            ParcelStatus previousStatus,
            ParcelStatus newStatus,
            String reason,
            String courierSub) {
        return createParcelStatusChangedEvent(
                parcel,
                previousStatus,
                newStatus,
                reason,
                courierSub,
                null,
                Instant.now(),
                parcel.getVersion() != null ? parcel.getVersion() : 1L
        );
    }

    /**
     * Constructs an OutboxEvent for an authoritative parcel status transition.
     * Enforces zero customer PII (no phone, email, full address, or bearer tokens).
     */
    public OutboxEvent createParcelStatusChangedEvent(
            Parcel parcel,
            ParcelStatus previousStatus,
            ParcelStatus newStatus,
            String reason,
            String courierSub,
            String correlationId,
            Instant occurredAt,
            Long aggregateVersion) {

        Objects.requireNonNull(parcel, "parcel cannot be null");
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        Objects.requireNonNull(aggregateVersion, "aggregateVersion cannot be null");

        Instant timestamp = occurredAt != null ? occurredAt : Instant.now();
        UUID eventId = UUID.randomUUID();
        String corrId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : "corr-" + UUID.randomUUID();

        // 1. Construct non-sensitive inner payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parcelId", parcel.getParcelId().toString());
        payload.put("trackingNumber", parcel.getTrackingNumber());
        payload.put("oldStatus", previousStatus != null ? previousStatus.name() : null);
        payload.put("newStatus", newStatus.name());
        payload.put("reason", reason);
        payload.put("merchantId", parcel.getMerchantId() != null ? parcel.getMerchantId().toString() : null);
        payload.put("courierSub", courierSub);
        payload.put("cityOrZone", null); // Strictly zero street-level PII per DEC-EVT-03

        // 2. Construct canonical event envelope
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "parcel.status.changed.v1");
        envelope.put("schemaVersion", "1.0.0");
        envelope.put("aggregateId", parcel.getParcelId().toString());
        envelope.put("aggregateType", "PARCEL");
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("occurredAt", timestamp.toString());
        envelope.put("correlationId", corrId);
        envelope.put("producer", "delivery-operations");
        envelope.put("payload", payload);

        try {
            String jsonPayload = jsonMapper.writeValueAsString(envelope);
            return new OutboxEvent(
                    eventId,
                    "PARCEL",
                    parcel.getParcelId(),
                    aggregateVersion,
                    "parcel.status.changed.v1",
                    "1.0.0",
                    jsonPayload,
                    OutboxStatus.PENDING,
                    timestamp,
                    0,
                    null,
                    timestamp,
                    null,
                    null,
                    null,
                    null,
                    "parcel.lifecycle.v1",
                    parcel.getParcelId().toString()
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event payload to JSON", e);
        }
    }
}
