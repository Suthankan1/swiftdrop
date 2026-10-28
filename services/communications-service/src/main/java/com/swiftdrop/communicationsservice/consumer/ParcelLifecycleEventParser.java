package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedPayload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates and parses incoming Kafka domain events adhering strictly to
 * contracts/events/parcel.status.changed.v1.schema.json.
 */
@Component
public class ParcelLifecycleEventParser {

    public static final String EXPECTED_EVENT_TYPE = "parcel.status.changed.v1";
    public static final String EXPECTED_AGGREGATE_TYPE = "PARCEL";
    public static final String EXPECTED_PRODUCER = "delivery-operations";
    private static final Pattern SCHEMA_VERSION_PATTERN = Pattern.compile("^1\\.[0-9]+\\.[0-9]+$");
    private static final Pattern TRACKING_NUMBER_PATTERN = Pattern.compile("^SWD-[0-9]{8}-[A-Z0-9]{4,8}$");

    private static final Set<String> VALID_OLD_STATUSES = Set.of(
            "CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY"
    );

    private static final Set<String> VALID_NEW_STATUSES = Set.of(
            "CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"
    );

    private static final Set<String> VALID_FAILURE_REASONS = Set.of(
            "RECIPIENT_UNAVAILABLE",
            "INCORRECT_ADDRESS",
            "REFUSED_BY_RECIPIENT",
            "PARCEL_DAMAGED",
            "FORCE_MAJEURE",
            "CANCELLED_BY_SENDER",
            "PICKUP_FAILED_SENDER_ABSENT"
    );

    private final JsonMapper jsonMapper;

    public ParcelLifecycleEventParser() {
        this.jsonMapper = JsonMapper.builder().build();
    }

    public ParcelLifecycleEventParser(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
    }

    /**
     * Parses and validates a Kafka record value and verifies partition key alignment.
     *
     * @param recordKey the Kafka message key (partition key)
     * @param payloadJson the Kafka message value (JSON string)
     * @return validated domain event model
     * @throws EventValidationException if validation fails
     */
    public ParcelStatusChangedEvent parseAndValidate(String recordKey, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new EventValidationException("Event payload must not be null or blank");
        }

        JsonNode root;
        try {
            root = jsonMapper.readTree(payloadJson);
        } catch (Exception ex) {
            throw new EventValidationException("Failed to parse event JSON payload: " + ex.getMessage(), ex);
        }

        if (!root.isObject()) {
            throw new EventValidationException("Event payload root must be a JSON object");
        }

        // 1. Validate envelope fields
        UUID eventId = parseUuid(root, "eventId", "eventId");
        String eventType = requireNonBlankText(root, "eventType", "eventType");
        if (!EXPECTED_EVENT_TYPE.equals(eventType)) {
            throw new EventValidationException("Unsupported eventType: " + eventType + " (expected: " + EXPECTED_EVENT_TYPE + ")");
        }

        String schemaVersion = requireNonBlankText(root, "schemaVersion", "schemaVersion");
        if (!SCHEMA_VERSION_PATTERN.matcher(schemaVersion).matches()) {
            throw new EventValidationException("Invalid schemaVersion: " + schemaVersion + " (expected major version 1 matching pattern ^1\\.[0-9]+\\.[0-9]+$)");
        }

        UUID aggregateId = parseUuid(root, "aggregateId", "aggregateId");
        String aggregateType = requireNonBlankText(root, "aggregateType", "aggregateType");
        if (!EXPECTED_AGGREGATE_TYPE.equals(aggregateType)) {
            throw new EventValidationException("Invalid aggregateType: " + aggregateType + " (expected: " + EXPECTED_AGGREGATE_TYPE + ")");
        }

        if (!root.hasNonNull("aggregateVersion") || !root.get("aggregateVersion").isIntegralNumber()) {
            throw new EventValidationException("Missing or invalid aggregateVersion: must be an integer >= 1");
        }
        long aggregateVersion = root.get("aggregateVersion").asLong();
        if (aggregateVersion < 1) {
            throw new EventValidationException("aggregateVersion must be >= 1 (got: " + aggregateVersion + ")");
        }

        String occurredAtStr = requireNonBlankText(root, "occurredAt", "occurredAt");
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(occurredAtStr);
        } catch (DateTimeParseException ex) {
            throw new EventValidationException("Invalid occurredAt ISO-8601 timestamp: " + occurredAtStr, ex);
        }

        String correlationId = requireNonBlankText(root, "correlationId", "correlationId");
        String producer = requireNonBlankText(root, "producer", "producer");
        if (!EXPECTED_PRODUCER.equals(producer)) {
            throw new EventValidationException("Invalid producer: " + producer + " (expected: " + EXPECTED_PRODUCER + ")");
        }

        // 2. Validate payload node
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw new EventValidationException("Missing required payload object in event");
        }

        UUID parcelId = parseUuid(payloadNode, "parcelId", "payload.parcelId");
        if (!aggregateId.equals(parcelId)) {
            throw new EventValidationException("aggregateId [" + aggregateId + "] does not match payload.parcelId [" + parcelId + "]");
        }

        // 3. Validate Kafka record key matches parcel partition key
        if (recordKey == null || !recordKey.trim().equals(parcelId.toString())) {
            throw new EventValidationException("Kafka record key [" + recordKey + "] does not match parcel partition key [" + parcelId + "]");
        }

        String trackingNumber = requireNonBlankText(payloadNode, "trackingNumber", "payload.trackingNumber");
        if (!TRACKING_NUMBER_PATTERN.matcher(trackingNumber).matches()) {
            throw new EventValidationException("Invalid trackingNumber format: " + trackingNumber + " (expected pattern: ^SWD-[0-9]{8}-[A-Z0-9]{4,8}$)");
        }

        String oldStatus = null;
        if (payloadNode.hasNonNull("oldStatus")) {
            oldStatus = payloadNode.get("oldStatus").asText();
            if (!VALID_OLD_STATUSES.contains(oldStatus)) {
                throw new EventValidationException("Invalid oldStatus: " + oldStatus + " (allowed: " + VALID_OLD_STATUSES + ")");
            }
        }

        String newStatus = requireNonBlankText(payloadNode, "newStatus", "payload.newStatus");
        if (!VALID_NEW_STATUSES.contains(newStatus)) {
            throw new EventValidationException("Invalid newStatus: " + newStatus + " (allowed: " + VALID_NEW_STATUSES + ")");
        }

        String reason = null;
        if (payloadNode.hasNonNull("reason")) {
            reason = payloadNode.get("reason").asText();
            if (!VALID_FAILURE_REASONS.contains(reason)) {
                throw new EventValidationException("Invalid failure reason: " + reason + " (allowed: " + VALID_FAILURE_REASONS + ")");
            }
        }

        if ("FAILED".equals(newStatus)) {
            if (reason == null) {
                throw new EventValidationException("reason code is mandatory when newStatus is FAILED");
            }
        } else {
            if (reason != null) {
                throw new EventValidationException("reason code must be null when newStatus is not FAILED (got: " + reason + ")");
            }
        }

        UUID merchantId = null;
        if (payloadNode.hasNonNull("merchantId")) {
            merchantId = parseUuid(payloadNode, "merchantId", "payload.merchantId");
        }

        String courierSub = payloadNode.hasNonNull("courierSub") ? payloadNode.get("courierSub").asText() : null;
        String cityOrZone = payloadNode.hasNonNull("cityOrZone") ? payloadNode.get("cityOrZone").asText() : null;

        ParcelStatusChangedPayload payload = new ParcelStatusChangedPayload(
                parcelId,
                trackingNumber,
                oldStatus,
                newStatus,
                reason,
                merchantId,
                courierSub,
                cityOrZone
        );

        return new ParcelStatusChangedEvent(
                eventId,
                eventType,
                schemaVersion,
                aggregateId,
                aggregateType,
                aggregateVersion,
                occurredAt,
                correlationId,
                producer,
                payload
        );
    }

    private UUID parseUuid(JsonNode node, String fieldName, String pathDescription) {
        if (!node.hasNonNull(fieldName)) {
            throw new EventValidationException("Missing required UUID field: " + pathDescription);
        }
        String text = node.get(fieldName).asText();
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException ex) {
            throw new EventValidationException("Invalid UUID format for " + pathDescription + ": " + text, ex);
        }
    }

    private String requireNonBlankText(JsonNode node, String fieldName, String pathDescription) {
        if (!node.hasNonNull(fieldName)) {
            throw new EventValidationException("Missing required field: " + pathDescription);
        }
        String text = node.get(fieldName).asText();
        if (text == null || text.trim().isEmpty()) {
            throw new EventValidationException("Field must not be empty or blank: " + pathDescription);
        }
        return text.trim();
    }
}
