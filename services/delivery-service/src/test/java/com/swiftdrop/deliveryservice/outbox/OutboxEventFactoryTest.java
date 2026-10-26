package com.swiftdrop.deliveryservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventFactoryTest {

    private OutboxEventFactory factory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Parcel parcel;
    private UUID parcelId;
    private UUID merchantId;
    private final String trackingNumber = "SWD-20261025-ABCD";
    private final String courierSub = "courier-sub-123";

    @BeforeEach
    void setUp() {
        factory = new OutboxEventFactory();
        parcelId = UUID.randomUUID();
        merchantId = UUID.randomUUID();

        parcel = new Parcel(
                parcelId,
                trackingNumber,
                "sender-customer-1",
                merchantId,
                UUID.randomUUID(),
                "2026.1",
                250000L,
                "LKR",
                new BigDecimal("1.500")
        );
        // Initially CREATED, version 1
    }

    @Test
    @DisplayName("Should create valid parcel.status.changed.v1 outbox event matching contract schema")
    void testCreateParcelStatusChangedEvent_validContract() throws Exception {
        // Transition: CREATED -> PICKED_UP, version becomes 2
        parcel.setStatus(ParcelStatus.PICKED_UP);
        parcel.setVersion(2L);

        OutboxEvent outboxEvent = factory.createParcelStatusChangedEvent(
                parcel,
                ParcelStatus.CREATED,
                ParcelStatus.PICKED_UP,
                "Courier picked up package",
                courierSub
        );

        assertThat(outboxEvent).isNotNull();
        assertThat(outboxEvent.getEventId()).isNotNull();
        assertThat(outboxEvent.getAggregateId()).isEqualTo(parcelId);
        assertThat(outboxEvent.getAggregateType()).isEqualTo("PARCEL");
        assertThat(outboxEvent.getAggregateVersion()).isEqualTo(2L);
        assertThat(outboxEvent.getEventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(outboxEvent.getSchemaVersion()).isEqualTo("1.0.0");
        assertThat(outboxEvent.getTopic()).isEqualTo("parcel.lifecycle.v1");
        assertThat(outboxEvent.getPartitionKey()).isEqualTo(parcelId.toString());
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEvent.getAttemptCount()).isEqualTo(0);
        assertThat(outboxEvent.getNextAttemptAt()).isNotNull();
        assertThat(outboxEvent.getCreatedAt()).isNotNull();

        // Parse JSON payload
        JsonNode root = objectMapper.readTree(outboxEvent.getPayload());
        assertThat(root.get("eventId").asText()).isEqualTo(outboxEvent.getEventId().toString());
        assertThat(root.get("eventType").asText()).isEqualTo("parcel.status.changed.v1");
        assertThat(root.get("schemaVersion").asText()).isEqualTo("1.0.0");
        assertThat(root.get("aggregateId").asText()).isEqualTo(parcelId.toString());
        assertThat(root.get("aggregateType").asText()).isEqualTo("PARCEL");
        assertThat(root.get("aggregateVersion").asLong()).isEqualTo(2L);
        assertThat(root.get("producer").asText()).isEqualTo("delivery-operations");
        assertThat(root.hasNonNull("occurredAt")).isTrue();
        assertThat(root.hasNonNull("correlationId")).isTrue();

        JsonNode payload = root.get("payload");
        assertThat(payload.get("parcelId").asText()).isEqualTo(parcelId.toString());
        assertThat(payload.get("trackingNumber").asText()).isEqualTo(trackingNumber);
        assertThat(payload.get("oldStatus").asText()).isEqualTo("CREATED");
        assertThat(payload.get("newStatus").asText()).isEqualTo("PICKED_UP");
        assertThat(payload.get("reason").asText()).isEqualTo("Courier picked up package");
        assertThat(payload.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(payload.get("courierSub").asText()).isEqualTo(courierSub);
    }

    @Test
    @DisplayName("Should strictly enforce privacy invariants with ZERO customer PII in payload")
    void testZeroCustomerPII() throws Exception {
        parcel.setStatus(ParcelStatus.PICKED_UP);
        parcel.setVersion(2L);

        OutboxEvent outboxEvent = factory.createParcelStatusChangedEvent(
                parcel,
                ParcelStatus.CREATED,
                ParcelStatus.PICKED_UP,
                null,
                courierSub
        );

        JsonNode root = objectMapper.readTree(outboxEvent.getPayload());
        JsonNode payload = root.get("payload");

        // Forbidden PII fields per DEC-EVT-03
        assertThat(payload.has("recipientPhone")).isFalse();
        assertThat(payload.has("recipientEmail")).isFalse();
        assertThat(payload.has("streetAddress")).isFalse();
        assertThat(payload.has("bearerToken")).isFalse();
        assertThat(payload.has("authorization")).isFalse();
        assertThat(payload.has("token")).isFalse();
        assertThat(payload.has("proofUrl")).isFalse();
        assertThat(payload.has("photoUrl")).isFalse();
        assertThat(payload.has("signatureUrl")).isFalse();

        // Allowed envelope fields only
        assertThat(root.has("unapprovedHeader")).isFalse();
    }

    @Test
    @DisplayName("Should generate unique eventId and correlationId for consecutive events")
    void testUniqueEventAndCorrelationIds() throws Exception {
        OutboxEvent event1 = factory.createParcelStatusChangedEvent(
                parcel, null, ParcelStatus.CREATED, null, courierSub);
        OutboxEvent event2 = factory.createParcelStatusChangedEvent(
                parcel, ParcelStatus.CREATED, ParcelStatus.PICKED_UP, null, courierSub);

        assertThat(event1.getEventId()).isNotEqualTo(event2.getEventId());

        JsonNode root1 = objectMapper.readTree(event1.getPayload());
        JsonNode root2 = objectMapper.readTree(event2.getPayload());
        assertThat(root1.get("correlationId").asText()).isNotEqualTo(root2.get("correlationId").asText());
    }

    @Test
    @DisplayName("Partition key strictly equals aggregateId (parcelId)")
    void testPartitionKeyConsistency() {
        OutboxEvent event = factory.createParcelStatusChangedEvent(
                parcel, null, ParcelStatus.CREATED, null, courierSub);
        assertThat(event.getPartitionKey()).isEqualTo(parcel.getParcelId().toString());
        assertThat(event.getAggregateId()).isEqualTo(parcel.getParcelId());
    }
}
