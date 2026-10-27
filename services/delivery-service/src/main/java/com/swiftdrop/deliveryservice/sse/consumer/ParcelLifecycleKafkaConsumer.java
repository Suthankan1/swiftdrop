package com.swiftdrop.deliveryservice.sse.consumer;

import com.swiftdrop.deliveryservice.sse.model.ParcelLifecycleSseEvent;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Kafka consumer bridging authoritative lifecycle events from 'parcel.lifecycle.v1'
 * to active in-memory SSE subscribers on this Delivery replica.
 *
 * Implements strict schema validation, partition key consistency, version checks,
 * sanitized logging, and drops malformed/stale records safely.
 */
@Component
@ConditionalOnProperty(name = "swiftdrop.sse.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ParcelLifecycleKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ParcelLifecycleKafkaConsumer.class);

    private final ParcelSseConnectionManager connectionManager;
    private final JsonMapper jsonMapper;

    public ParcelLifecycleKafkaConsumer(ParcelSseConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @KafkaListener(
            topics = "${swiftdrop.kafka.topics.parcel-lifecycle:parcel.lifecycle.v1}",
            groupId = "${spring.kafka.consumer.group-id:delivery-service-sse}",
            containerFactory = "sseKafkaListenerContainerFactory"
    )
    public void onLifecycleEvent(
            @Payload String rawMessage,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String partitionKey) {

        if (rawMessage == null || rawMessage.isBlank()) {
            log.warn("Received empty or blank Kafka lifecycle message. Dropping.");
            return;
        }

        try {
            JsonNode root = jsonMapper.readTree(rawMessage);

            // 1. Envelope validation
            JsonNode eventTypeNode = root.get("eventType");
            if (eventTypeNode == null || !"parcel.status.changed.v1".equals(eventTypeNode.asText())) {
                log.debug("Ignoring unsupported or missing eventType: {}", eventTypeNode != null ? eventTypeNode.asText() : "null");
                return;
            }

            JsonNode eventIdNode = root.get("eventId");
            JsonNode aggregateIdNode = root.get("aggregateId");
            JsonNode aggregateVersionNode = root.get("aggregateVersion");
            JsonNode occurredAtNode = root.get("occurredAt");
            JsonNode payloadNode = root.get("payload");

            if (eventIdNode == null || aggregateIdNode == null || aggregateVersionNode == null || payloadNode == null) {
                log.warn("Malformed event envelope received: missing required fields. Dropping event.");
                return;
            }

            UUID eventId = UUID.fromString(eventIdNode.asText());
            UUID aggregateId = UUID.fromString(aggregateIdNode.asText());
            long aggregateVersion = aggregateVersionNode.asLong();
            Instant occurredAt = occurredAtNode != null ? Instant.parse(occurredAtNode.asText()) : Instant.now();

            // 2. Partition key consistency check
            if (partitionKey != null && !partitionKey.equalsIgnoreCase(aggregateId.toString())) {
                log.warn("Partition key mismatch: record key '{}' does not match aggregateId '{}'. Dropping.",
                        partitionKey, aggregateId);
                return;
            }

            // 3. Payload validation
            JsonNode parcelIdNode = payloadNode.get("parcelId");
            JsonNode trackingNumberNode = payloadNode.get("trackingNumber");
            JsonNode newStatusNode = payloadNode.get("newStatus");
            JsonNode oldStatusNode = payloadNode.get("oldStatus");
            JsonNode reasonNode = payloadNode.get("reason");

            if (parcelIdNode == null || trackingNumberNode == null || newStatusNode == null) {
                log.warn("Malformed payload for event {}: missing parcelId, trackingNumber, or newStatus. Dropping.", eventId);
                return;
            }

            UUID parcelId = UUID.fromString(parcelIdNode.asText());
            if (!parcelId.equals(aggregateId)) {
                log.warn("Payload parcelId '{}' differs from aggregateId '{}'. Dropping.", parcelId, aggregateId);
                return;
            }

            String trackingNumber = trackingNumberNode.asText();
            String newStatus = newStatusNode.asText();
            String oldStatus = (oldStatusNode != null && !oldStatusNode.isNull()) ? oldStatusNode.asText() : null;
            String reason = (reasonNode != null && !reasonNode.isNull()) ? reasonNode.asText() : null;

            ParcelLifecycleSseEvent sseEvent = new ParcelLifecycleSseEvent(
                    eventId,
                    parcelId,
                    trackingNumber,
                    aggregateVersion,
                    oldStatus,
                    newStatus,
                    reason,
                    occurredAt
            );

            // 4. Dispatch to connection manager
            connectionManager.broadcastToParcel(parcelId, sseEvent);

        } catch (Exception ex) {
            log.error("Failed to process Kafka lifecycle message: {}", sanitize(ex.getMessage()));
        }
    }

    private String sanitize(String msg) {
        if (msg == null) return "unknown error";
        return msg.replaceAll("[\r\n]", " ").substring(0, Math.min(200, msg.length()));
    }
}
