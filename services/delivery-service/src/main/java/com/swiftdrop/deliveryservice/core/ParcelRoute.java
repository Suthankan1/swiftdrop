package com.swiftdrop.deliveryservice.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

/**
 * Coarse route facts captured from the verified pricing quote at booking time: zone names, chargeable
 * weight, size category and dimensions. Zone names are the only location data exposed publicly; street addresses
 * never leave the parcel record.
 */
public record ParcelRoute(String pickupZoneName, String dropoffZoneName, String sizeCategory, BigDecimal weightKg,
                          String dimensionsCm, String pickupZone, String dropoffZone) {

    public static final ParcelRoute UNKNOWN = new ParcelRoute(null, null, null, null, null, null, null);

    /** Name-only route (zone codes unknown), kept for existing callers. */
    public ParcelRoute(String pickupZoneName, String dropoffZoneName, String sizeCategory, BigDecimal weightKg,
                       String dimensionsCm) {
        this(pickupZoneName, dropoffZoneName, sizeCategory, weightKg, dimensionsCm, null, null);
    }
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Parses the parcel's stored quote snapshot. Older parcels without one get {@link #UNKNOWN}. */
    public static ParcelRoute fromSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return UNKNOWN;
        }
        try {
            JsonNode node = JSON.readTree(snapshotJson);
            BigDecimal weight = null;
            JsonNode w = node.get("weightKg");
            if (w != null && !w.isNull() && !w.asText().isBlank()) {
                weight = new BigDecimal(w.asText());
            }
            return new ParcelRoute(text(node, "pickupZoneName"), text(node, "dropoffZoneName"),
                    text(node, "sizeCategory"), weight, text(node, "dimensionsCm"),
                    text(node, "pickupZone"), text(node, "dropoffZone"));
        } catch (RuntimeException ex) {
            return UNKNOWN;
        }
    }

    public static ParcelRoute of(Parcel parcel) {
        return parcel == null ? UNKNOWN : fromSnapshot(parcel.getQuoteSnapshot());
    }

    /** Coarse public location for a lifecycle stage, or null when the route is unknown. */
    public String locationFor(ParcelStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case CREATED, PICKED_UP -> pickupZoneName;
            case IN_TRANSIT -> dropoffZoneName == null ? null : "En route to " + dropoffZoneName;
            case OUT_FOR_DELIVERY, DELIVERED, FAILED -> dropoffZoneName;
        };
    }

    public String locationFor(String status) {
        try {
            return status == null ? null : locationFor(ParcelStatus.valueOf(status));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
