package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParcelLifecycleEventParserTest {

    private ParcelLifecycleEventParser parser;
    private UUID parcelId;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        parser = new ParcelLifecycleEventParser();
        parcelId = UUID.randomUUID();
        eventId = UUID.randomUUID();
    }

    private String createValidEventJson(String newStatus, String oldStatus, String reason) {
        String reasonField = reason != null ? "\"reason\": \"" + reason + "\"," : "";
        String oldStatusField = oldStatus != null ? "\"oldStatus\": \"" + oldStatus + "\"," : "\"oldStatus\": null,";
        return """
                {
                  "eventId": "%s",
                  "eventType": "parcel.status.changed.v1",
                  "schemaVersion": "1.0.0",
                  "aggregateId": "%s",
                  "aggregateType": "PARCEL",
                  "aggregateVersion": 2,
                  "occurredAt": "2026-10-26T12:00:00Z",
                  "correlationId": "corr-12345",
                  "producer": "delivery-operations",
                  "payload": {
                    "parcelId": "%s",
                    "trackingNumber": "SWD-20261026-A1B2",
                    %s
                    "newStatus": "%s",
                    %s
                    "merchantId": null,
                    "courierSub": "courier-sub-1",
                    "cityOrZone": "Colombo-03"
                  }
                }
                """.formatted(eventId, parcelId, parcelId, oldStatusField, newStatus, reasonField);
    }

    @Test
    @DisplayName("Successfully parse valid parcel.status.changed.v1 event")
    void testParseAndValidate_ValidEvent() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null);

        ParcelStatusChangedEvent event = parser.parseAndValidate(parcelId.toString(), json);

        assertThat(event).isNotNull();
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.eventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(event.schemaVersion()).isEqualTo("1.0.0");
        assertThat(event.aggregateId()).isEqualTo(parcelId);
        assertThat(event.aggregateType()).isEqualTo("PARCEL");
        assertThat(event.aggregateVersion()).isEqualTo(2L);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-26T12:00:00Z"));
        assertThat(event.correlationId()).isEqualTo("corr-12345");
        assertThat(event.producer()).isEqualTo("delivery-operations");

        assertThat(event.payload()).isNotNull();
        assertThat(event.payload().parcelId()).isEqualTo(parcelId);
        assertThat(event.payload().trackingNumber()).isEqualTo("SWD-20261026-A1B2");
        assertThat(event.payload().oldStatus()).isEqualTo("PICKED_UP");
        assertThat(event.payload().newStatus()).isEqualTo("IN_TRANSIT");
        assertThat(event.payload().reason()).isNull();
        assertThat(event.payload().courierSub()).isEqualTo("courier-sub-1");
        assertThat(event.payload().cityOrZone()).isEqualTo("Colombo-03");
    }

    @Test
    @DisplayName("Successfully parse valid FAILED event with mandatory failure reason")
    void testParseAndValidate_ValidFailedEvent() {
        String json = createValidEventJson("FAILED", "OUT_FOR_DELIVERY", "RECIPIENT_UNAVAILABLE");

        ParcelStatusChangedEvent event = parser.parseAndValidate(parcelId.toString(), json);

        assertThat(event.payload().newStatus()).isEqualTo("FAILED");
        assertThat(event.payload().reason()).isEqualTo("RECIPIENT_UNAVAILABLE");
    }

    @Test
    @DisplayName("Reject null or blank event payload")
    void testParseAndValidate_NullOrBlankPayload() {
        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), null))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("payload must not be null or blank");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), "   "))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("payload must not be null or blank");
    }

    @Test
    @DisplayName("Reject non-JSON or malformed payload")
    void testParseAndValidate_MalformedJson() {
        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), "{not valid json"))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Failed to parse event JSON payload");
    }

    @Test
    @DisplayName("Reject payload that is not a JSON object")
    void testParseAndValidate_NotJsonObject() {
        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), "[\"an\", \"array\"]"))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    @DisplayName("Reject missing eventId")
    void testParseAndValidate_MissingEventId() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace("\"eventId\": \"" + eventId + "\",", "");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Missing required UUID field: eventId");
    }

    @Test
    @DisplayName("Reject invalid UUID eventId")
    void testParseAndValidate_InvalidUuidEventId() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace(eventId.toString(), "not-a-uuid");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Invalid UUID format for eventId");
    }

    @Test
    @DisplayName("Reject unsupported eventType")
    void testParseAndValidate_UnsupportedEventType() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace("parcel.status.changed.v1", "parcel.created.v1");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Unsupported eventType");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.0.0", "0.9.0", "1.0", "v1.0.0", "beta"})
    @DisplayName("Reject unapproved schemaVersion")
    void testParseAndValidate_UnapprovedSchemaVersion(String version) {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace("\"schemaVersion\": \"1.0.0\"", "\"schemaVersion\": \"" + version + "\"");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Invalid schemaVersion");
    }

    @Test
    @DisplayName("Reject unapproved aggregateType")
    void testParseAndValidate_UnapprovedAggregateType() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace("\"aggregateType\": \"PARCEL\"", "\"aggregateType\": \"SHIPMENT\"");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Invalid aggregateType");
    }

    @Test
    @DisplayName("Reject aggregateVersion less than 1")
    void testParseAndValidate_InvalidAggregateVersion() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace("\"aggregateVersion\": 2", "\"aggregateVersion\": 0");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("aggregateVersion must be >= 1");
    }

    @Test
    @DisplayName("Reject unapproved producer")
    void testParseAndValidate_UnapprovedProducer() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace("delivery-operations", "unauthorized-service");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Invalid producer");
    }

    @Test
    @DisplayName("Reject aggregateId mismatch with payload.parcelId")
    void testParseAndValidate_AggregateIdMismatch() {
        UUID otherId = UUID.randomUUID();
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replaceFirst("\"aggregateId\": \"" + parcelId + "\"", "\"aggregateId\": \"" + otherId + "\"");

        assertThatThrownBy(() -> parser.parseAndValidate(otherId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    @DisplayName("Reject partition key mismatch with parcelId")
    void testParseAndValidate_PartitionKeyMismatch() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null);
        String wrongKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> parser.parseAndValidate(wrongKey, json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Kafka record key");
    }

    @Test
    @DisplayName("Reject invalid trackingNumber format")
    void testParseAndValidate_InvalidTrackingNumber() {
        String json = createValidEventJson("IN_TRANSIT", "PICKED_UP", null)
                .replace("SWD-20261026-A1B2", "INVALID-TRACKING");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Invalid trackingNumber format");
    }

    @Test
    @DisplayName("Reject unknown newStatus")
    void testParseAndValidate_UnknownNewStatus() {
        String json = createValidEventJson("UNKNOWN_STATUS", "PICKED_UP", null);

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("Invalid newStatus");
    }

    @Test
    @DisplayName("Reject FAILED newStatus without mandatory reason")
    void testParseAndValidate_FailedStatusWithoutReason() {
        String json = createValidEventJson("FAILED", "OUT_FOR_DELIVERY", null);

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("reason code is mandatory when newStatus is FAILED");
    }

    @Test
    @DisplayName("Reject non-FAILED status with reason present")
    void testParseAndValidate_NonFailedStatusWithReason() {
        String json = createValidEventJson("DELIVERED", "OUT_FOR_DELIVERY", "RECIPIENT_UNAVAILABLE");

        assertThatThrownBy(() -> parser.parseAndValidate(parcelId.toString(), json))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("reason code must be null when newStatus is not FAILED");
    }
}
