package com.swiftdrop.deliveryservice.tracking;

import tools.jackson.databind.json.JsonMapper;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingResponse;
import com.swiftdrop.deliveryservice.tracking.dto.TrackingTimelineItem;
import com.swiftdrop.deliveryservice.tracking.service.DefaultPublicTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Strict data redaction and security validation tests for public parcel tracking.
 * Asserts every sensitive field, PII token, credential, internal identifier,
 * and proof object reference is absent from the serialized public output.
 */
@ExtendWith(MockitoExtension.class)
class PublicTrackingRedactionAndSecurityTest {

    @Mock
    private ParcelRepository parcelRepository;

    @Mock
    private StatusHistoryRepository statusHistoryRepository;

    private DefaultPublicTrackingService trackingService;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // Distinct synthetic sensitive markers
    private static final String SENSITIVE_SENDER_SUB = "sub-customer-alice-uuid-9999";
    private static final UUID SENSITIVE_MERCHANT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SENSITIVE_QUOTE_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final UUID SENSITIVE_PARCEL_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID SENSITIVE_PROOF_ID = UUID.fromString("deadbeef-feed-cafe-babe-0123456789ab");
    private static final String SENSITIVE_COURIER_SUB = "sub-courier-bob-uuid-8888";
    private static final String SENSITIVE_COURIER_NOTE = "Gate code is 4321, leave behind large plant pot";
    private static final String SENSITIVE_FAILURE_REASON = "Recipient John Doe refused payment of 150000 LKR";
    private static final Long SENSITIVE_AMOUNT = 150000L;
    private static final String SENSITIVE_RATE_VERSION = "PRICING_V2_CONFIDENTIAL_TIER";

    @BeforeEach
    void setUp() {
        trackingService = new DefaultPublicTrackingService(parcelRepository, statusHistoryRepository);
    }

    @Test
    @DisplayName("Positive: Valid public tracking reference returns allowlisted milestones with coarse location")
    void validTrackingReference_ReturnsApprovedProjection() {
        String trackingNumber = "SWD-20261023-A1B2C3";
        Instant t0 = Instant.parse("2026-10-23T08:00:00Z");
        Instant t1 = Instant.parse("2026-10-23T10:30:00Z");
        Instant t2 = Instant.parse("2026-10-23T14:15:00Z");

        Parcel parcel = new Parcel(
                SENSITIVE_PARCEL_ID,
                trackingNumber,
                SENSITIVE_SENDER_SUB,
                SENSITIVE_MERCHANT_ID,
                SENSITIVE_QUOTE_ID,
                SENSITIVE_RATE_VERSION,
                SENSITIVE_AMOUNT,
                "LKR",
                new BigDecimal("2.500")
        );
        parcel.setStatus(ParcelStatus.IN_TRANSIT);

        List<StatusHistory> history = List.of(
                new StatusHistory(UUID.randomUUID(), SENSITIVE_PARCEL_ID, null, ParcelStatus.CREATED,
                        "Initial booking", null, null, SENSITIVE_SENDER_SUB, 1L, t0),
                new StatusHistory(UUID.randomUUID(), SENSITIVE_PARCEL_ID, ParcelStatus.CREATED, ParcelStatus.PICKED_UP,
                        "Picked up from sender", SENSITIVE_COURIER_NOTE, null, SENSITIVE_COURIER_SUB, 2L, t1),
                new StatusHistory(UUID.randomUUID(), SENSITIVE_PARCEL_ID, ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT,
                        "Transferred to sorting hub", null, null, "dispatcher-sub", 3L, t2)
        );

        when(parcelRepository.findByTrackingNumber(trackingNumber)).thenReturn(Optional.of(parcel));
        when(statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(SENSITIVE_PARCEL_ID)).thenReturn(history);

        PublicTrackingResponse response = trackingService.getPublicTracking(trackingNumber);

        assertThat(response).isNotNull();
        assertThat(response.trackingNumber()).isEqualTo(trackingNumber);
        assertThat(response.currentStatus()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(response.originCity()).isEqualTo("Colombo");
        assertThat(response.destinationCity()).isEqualTo("Gampaha");
        assertThat(response.timeline()).hasSize(3);

        // Verify ordering and coarse checkpoints
        assertThat(response.timeline().get(0).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(response.timeline().get(0).occurredAt()).isEqualTo(t0);
        assertThat(response.timeline().get(1).status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(response.timeline().get(1).occurredAt()).isEqualTo(t1);
        assertThat(response.timeline().get(2).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(response.timeline().get(2).occurredAt()).isEqualTo(t2);

        // Verify read-only contract: zero mutation invocations
        verify(parcelRepository, never()).save(any());
        verify(parcelRepository, never()).saveAndFlush(any());
        verify(statusHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Redaction: Synthetic sensitive fields are strictly absent from serialized JSON output")
    void serializedOutput_StrictlyRedactsAllSensitiveInformation() throws Exception {
        String trackingNumber = "SWD-20261023-XYZ9876";
        Instant now = Instant.now();

        Parcel parcel = new Parcel(
                SENSITIVE_PARCEL_ID,
                trackingNumber,
                SENSITIVE_SENDER_SUB,
                SENSITIVE_MERCHANT_ID,
                SENSITIVE_QUOTE_ID,
                SENSITIVE_RATE_VERSION,
                SENSITIVE_AMOUNT,
                "LKR",
                new BigDecimal("5.000")
        );
        parcel.setStatus(ParcelStatus.FAILED);

        List<StatusHistory> history = List.of(
                new StatusHistory(UUID.randomUUID(), SENSITIVE_PARCEL_ID, null, ParcelStatus.CREATED,
                        "Created by " + SENSITIVE_SENDER_SUB, null, null, SENSITIVE_SENDER_SUB, 1L, now.minusSeconds(3600)),
                new StatusHistory(UUID.randomUUID(), SENSITIVE_PARCEL_ID, ParcelStatus.CREATED, ParcelStatus.FAILED,
                        SENSITIVE_FAILURE_REASON, SENSITIVE_COURIER_NOTE, SENSITIVE_PROOF_ID, SENSITIVE_COURIER_SUB, 2L, now)
        );

        when(parcelRepository.findByTrackingNumber(trackingNumber)).thenReturn(Optional.of(parcel));
        when(statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(SENSITIVE_PARCEL_ID)).thenReturn(history);

        PublicTrackingResponse response = trackingService.getPublicTracking(trackingNumber);
        String json = jsonMapper.writeValueAsString(response);

        // Assert strictly forbidden identifiers and fields are entirely absent from the JSON string
        assertThat(json).doesNotContain(SENSITIVE_PARCEL_ID.toString());
        assertThat(json).doesNotContain(SENSITIVE_SENDER_SUB);
        assertThat(json).doesNotContain(SENSITIVE_MERCHANT_ID.toString());
        assertThat(json).doesNotContain(SENSITIVE_QUOTE_ID.toString());
        assertThat(json).doesNotContain(SENSITIVE_PROOF_ID.toString());
        assertThat(json).doesNotContain(SENSITIVE_COURIER_SUB);
        assertThat(json).doesNotContain(SENSITIVE_COURIER_NOTE);
        assertThat(json).doesNotContain(SENSITIVE_FAILURE_REASON);
        assertThat(json).doesNotContain(SENSITIVE_RATE_VERSION);
        assertThat(json).doesNotContain(String.valueOf(SENSITIVE_AMOUNT));
        assertThat(json).doesNotContain("amountMinorUnits");
        assertThat(json).doesNotContain("senderSub");
        assertThat(json).doesNotContain("merchantId");
        assertThat(json).doesNotContain("quoteId");
        assertThat(json).doesNotContain("proofId");
        assertThat(json).doesNotContain("actorSub");
        assertThat(json).doesNotContain("proofUrl");
        assertThat(json).doesNotContain("receiptUrl");
        assertThat(json).doesNotContain("pickupAddress");
        assertThat(json).doesNotContain("dropoffAddress");
        assertThat(json).doesNotContain("recipientName");
        assertThat(json).doesNotContain("recipientPhone");
        assertThat(json).doesNotContain("deliveryNotes");
        assertThat(json).doesNotContain("latitude");
        assertThat(json).doesNotContain("longitude");

        // Assert only approved allowlisted fields are present
        assertThat(json).contains("\"trackingNumber\":\"" + trackingNumber + "\"");
        assertThat(json).contains("\"currentStatus\":\"FAILED\"");
        assertThat(json).contains("\"timeline\"");
        assertThat(json).contains("\"occurredAt\"");
        assertThat(json).contains("\"cityOrZone\"");
    }

    @Test
    @DisplayName("Timeline: Single CREATED timeline is provided if status_history table is empty")
    void fallbackTimeline_WhenNoHistoryRecordsExist() {
        String trackingNumber = "SWD-20261023-EMPTYHST";
        Instant createdAt = Instant.parse("2026-10-23T09:00:00Z");

        Parcel parcel = new Parcel(
                SENSITIVE_PARCEL_ID,
                trackingNumber,
                SENSITIVE_SENDER_SUB,
                null,
                null,
                null,
                null,
                "LKR",
                null
        );
        parcel.setStatus(ParcelStatus.CREATED);

        when(parcelRepository.findByTrackingNumber(trackingNumber)).thenReturn(Optional.of(parcel));
        when(statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(SENSITIVE_PARCEL_ID)).thenReturn(List.of());

        PublicTrackingResponse response = trackingService.getPublicTracking(trackingNumber);

        assertThat(response.timeline()).hasSize(1);
        TrackingTimelineItem item = response.timeline().get(0);
        assertThat(item.status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(item.cityOrZone()).contains("Order Confirmed");
    }
}
