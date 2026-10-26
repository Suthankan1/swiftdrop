package com.swiftdrop.communicationsservice.webhook.validation;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidEventFilterException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates domain event subscriptions against the frozen OpenAPI v1.0.0 specification.
 * Currently supported events: "parcel.status.changed.v1".
 */
@Component
public class WebhookEventFilterValidator {

    public static final String PARCEL_STATUS_CHANGED_V1 = "parcel.status.changed.v1";
    public static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(PARCEL_STATUS_CHANGED_V1);
    public static final int MAX_SERIALIZED_LENGTH = 255;

    /**
     * Validates and normalizes allowed event types.
     *
     * @param allowedEventTypes list of event type strings
     * @return normalized list of verified event types
     */
    public List<String> validate(List<String> allowedEventTypes) {
        if (allowedEventTypes == null || allowedEventTypes.isEmpty()) {
            throw new InvalidEventFilterException("At least one supported event type must be specified.");
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String eventType : allowedEventTypes) {
            if (eventType == null || eventType.isBlank()) {
                throw new InvalidEventFilterException("Event type filter must not be null or blank.");
            }
            String trimmed = eventType.trim();
            if (!SUPPORTED_EVENT_TYPES.contains(trimmed)) {
                throw new InvalidEventFilterException(
                        "Unsupported event type: '" + trimmed + "'. Supported types: " + SUPPORTED_EVENT_TYPES);
            }
            normalized.add(trimmed);
        }

        String serialized = serialize(normalized.stream().toList());
        if (serialized.length() > MAX_SERIALIZED_LENGTH) {
            throw new InvalidEventFilterException(
                    "Serialized event filters exceed database limit of " + MAX_SERIALIZED_LENGTH + " characters.");
        }

        return normalized.stream().toList();
    }

    /**
     * Serializes event types into deterministic string for database storage.
     */
    public String serialize(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return "";
        }
        return String.join(",", eventTypes);
    }

    /**
     * Deserializes stored database string into list of event types.
     */
    public List<String> deserialize(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split(","));
    }
}
