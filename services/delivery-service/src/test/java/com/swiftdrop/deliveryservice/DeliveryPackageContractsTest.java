package com.swiftdrop.deliveryservice;

import com.swiftdrop.deliveryservice.booking.BookParcelCommand;
import com.swiftdrop.deliveryservice.booking.BookingResult;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.query.ParcelProjection;
import com.swiftdrop.deliveryservice.status.VersionConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryPackageContractsTest {

    @Test
    @DisplayName("Verify BookParcelCommand construction, validation, and defaults")
    void testBookParcelCommand() {
        UUID merchantId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        BookParcelCommand cmd = new BookParcelCommand(
                "customer-sub-1",
                merchantId,
                quoteId,
                "2026.1",
                250000L,
                null, // should default to LKR
                new BigDecimal("1.250"),
                "idem-key-1"
        );

        assertThat(cmd.senderSub()).isEqualTo("customer-sub-1");
        assertThat(cmd.merchantId()).isEqualTo(merchantId);
        assertThat(cmd.quoteId()).isEqualTo(quoteId);
        assertThat(cmd.rateVersion()).isEqualTo("2026.1");
        assertThat(cmd.amountMinorUnits()).isEqualTo(250000L);
        assertThat(cmd.currency()).isEqualTo("LKR");
        assertThat(cmd.weightKg()).isEqualByComparingTo("1.250");
        assertThat(cmd.idempotencyKey()).isEqualTo("idem-key-1");

        // Blank senderSub is rejected
        assertThatThrownBy(() -> new BookParcelCommand(
                "",
                null,
                null,
                null,
                100L,
                "LKR",
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Verify BookingResult record contracts")
    void testBookingResult() {
        UUID parcelId = UUID.randomUUID();
        Instant now = Instant.now();

        BookingResult result = new BookingResult(
                parcelId,
                "SWD-2026-0001",
                ParcelStatus.CREATED,
                1L,
                now
        );

        assertThat(result.parcelId()).isEqualTo(parcelId);
        assertThat(result.trackingNumber()).isEqualTo("SWD-2026-0001");
        assertThat(result.status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Verify ParcelProjection contract aligns with DeliveryInternalService wire specification")
    void testParcelProjection() {
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();

        ParcelProjection projection = new ParcelProjection(
                parcelId,
                "SWD-2026-0002",
                ParcelStatus.IN_TRANSIT,
                3L,
                180000L,
                "LKR",
                "sender-sub-456",
                merchantId,
                "courier-sub-789",
                now
        );

        assertThat(projection.parcelId()).isEqualTo(parcelId);
        assertThat(projection.trackingNumber()).isEqualTo("SWD-2026-0002");
        assertThat(projection.currentStatus()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(projection.version()).isEqualTo(3L);
        assertThat(projection.amountMinorUnits()).isEqualTo(180000L);
        assertThat(projection.currency()).isEqualTo("LKR");
        assertThat(projection.createdBySub()).isEqualTo("sender-sub-456");
        assertThat(projection.merchantId()).isEqualTo(merchantId);
        assertThat(projection.activeCourierSub()).isEqualTo("courier-sub-789");
        assertThat(projection.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Verify ParcelNotFoundException format and message")
    void testParcelNotFoundException() {
        UUID parcelId = UUID.randomUUID();
        ParcelNotFoundException exId = new ParcelNotFoundException(parcelId);
        assertThat(exId.getMessage()).contains(parcelId.toString());
        assertThat(exId.getIdentifier()).isEqualTo(parcelId.toString());

        ParcelNotFoundException exTrack = new ParcelNotFoundException("SWD-TRACK-01");
        assertThat(exTrack.getMessage()).contains("SWD-TRACK-01");
        assertThat(exTrack.getIdentifier()).isEqualTo("SWD-TRACK-01");
    }

    @Test
    @DisplayName("Verify VersionConflictException captures expected and actual versions")
    void testVersionConflictException() {
        VersionConflictException ex = new VersionConflictException(3L, 4L);
        assertThat(ex.getExpectedVersion()).isEqualTo(3L);
        assertThat(ex.getActualVersion()).isEqualTo(4L);
        assertThat(ex.getMessage()).contains("expected version 3 but was 4");
    }

    @Test
    @DisplayName("Verify UpdateStatusRequest record contracts")
    void testUpdateStatusRequestContract() {
        UUID proofId = UUID.randomUUID();
        com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest req =
                new com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest(
                        ParcelStatus.DELIVERED,
                        3L,
                        "Handed to recipient",
                        null,
                        proofId
                );

        assertThat(req.newStatus()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(req.expectedVersion()).isEqualTo(3L);
        assertThat(req.note()).isEqualTo("Handed to recipient");
        assertThat(req.reason()).isNull();
        assertThat(req.proofId()).isEqualTo(proofId);
    }

    @Test
    @DisplayName("Verify ParcelStatusChangeResponse record contracts")
    void testParcelStatusChangeResponseContract() {
        UUID parcelId = UUID.randomUUID();
        Instant now = Instant.now();
        com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse resp =
                new com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse(
                        parcelId,
                        "SWD-2026-TRK-9",
                        "OUT_FOR_DELIVERY",
                        "DELIVERED",
                        4L,
                        4L,
                        now
                );

        assertThat(resp.parcelId()).isEqualTo(parcelId);
        assertThat(resp.trackingNumber()).isEqualTo("SWD-2026-TRK-9");
        assertThat(resp.previousStatus()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(resp.currentStatus()).isEqualTo("DELIVERED");
        assertThat(resp.stateVersion()).isEqualTo(4L);
        assertThat(resp.updatedParcelVersion()).isEqualTo(4L);
        assertThat(resp.occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Verify AssignCourierRequest record contracts")
    void testAssignCourierRequestContract() {
        UUID parcelId = UUID.randomUUID();
        String courierSub = "courier-sub-123";
        com.swiftdrop.deliveryservice.assignment.dto.AssignCourierRequest req =
                new com.swiftdrop.deliveryservice.assignment.dto.AssignCourierRequest(
                        parcelId,
                        courierSub,
                        1L
                );

        assertThat(req.parcelId()).isEqualTo(parcelId);
        assertThat(req.courierSub()).isEqualTo(courierSub);
        assertThat(req.expectedVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Verify AssignmentResponse record contracts")
    void testAssignmentResponseContract() {
        UUID assignmentId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        Instant now = Instant.now();
        com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse resp =
                new com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse(
                        assignmentId,
                        parcelId,
                        "courier-sub-123",
                        true,
                        now,
                        "dispatcher-sub-999",
                        2L
                );

        assertThat(resp.assignmentId()).isEqualTo(assignmentId);
        assertThat(resp.parcelId()).isEqualTo(parcelId);
        assertThat(resp.courierSub()).isEqualTo("courier-sub-123");
        assertThat(resp.active()).isTrue();
        assertThat(resp.assignedAt()).isEqualTo(now);
        assertThat(resp.assignedBy()).isEqualTo("dispatcher-sub-999");
        assertThat(resp.newParcelVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Verify ParcelDetailResponse contract for Issue #94 REST reads")
    void testParcelDetailResponseContract() {
        UUID parcelId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();

        com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse resp =
                new com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse(
                        parcelId,
                        "SWD-20261025-PST101",
                        "customer-sub-1",
                        merchantId,
                        ParcelStatus.CREATED,
                        1L,
                        2.5,
                        "STANDARD",
                        125000L,
                        "LKR",
                        "RATE-V1",
                        "123 Main St",
                        "456 High St",
                        "courier-sub-1",
                        now
                );

        assertThat(resp.parcelId()).isEqualTo(parcelId);
        assertThat(resp.trackingNumber()).isEqualTo("SWD-20261025-PST101");
        assertThat(resp.createdBySub()).isEqualTo("customer-sub-1");
        assertThat(resp.merchantId()).isEqualTo(merchantId);
        assertThat(resp.currentStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(resp.version()).isEqualTo(1L);
        assertThat(resp.weightKg()).isEqualTo(2.5);
        assertThat(resp.dimensions()).isEqualTo("STANDARD");
        assertThat(resp.amountMinorUnits()).isEqualTo(125000L);
        assertThat(resp.currency()).isEqualTo("LKR");
        assertThat(resp.rateVersion()).isEqualTo("RATE-V1");
        assertThat(resp.pickupAddress()).isEqualTo("123 Main St");
        assertThat(resp.dropoffAddress()).isEqualTo("456 High St");
        assertThat(resp.activeCourierSub()).isEqualTo("courier-sub-1");
        assertThat(resp.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Verify ParcelStatusHistoryResponse contract for Issue #94 append-only history reads")
    void testParcelStatusHistoryResponseContract() {
        UUID historyId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID proofId = UUID.randomUUID();
        Instant now = Instant.now();

        com.swiftdrop.deliveryservice.query.dto.ParcelStatusHistoryResponse resp =
                new com.swiftdrop.deliveryservice.query.dto.ParcelStatusHistoryResponse(
                        historyId,
                        parcelId,
                        ParcelStatus.OUT_FOR_DELIVERY,
                        ParcelStatus.DELIVERED,
                        null,
                        "Signed by recipient",
                        proofId,
                        4L,
                        now
                );

        assertThat(resp.historyId()).isEqualTo(historyId);
        assertThat(resp.parcelId()).isEqualTo(parcelId);
        assertThat(resp.previousStatus()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        assertThat(resp.newStatus()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(resp.reason()).isNull();
        assertThat(resp.note()).isEqualTo("Signed by recipient");
        assertThat(resp.proofId()).isEqualTo(proofId);
        assertThat(resp.resultingVersion()).isEqualTo(4L);
        assertThat(resp.occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Verify PagedResponse pagination wrapper invariants")
    void testPagedResponseContract() {
        com.swiftdrop.deliveryservice.query.dto.PagedResponse<String> paged =
                new com.swiftdrop.deliveryservice.query.dto.PagedResponse<>(
                        java.util.List.of("item1", "item2"),
                        0,
                        2,
                        5L,
                        3,
                        true,
                        false
                );

        assertThat(paged.items()).containsExactly("item1", "item2");
        assertThat(paged.currentPage()).isEqualTo(0);
        assertThat(paged.pageSize()).isEqualTo(2);
        assertThat(paged.totalElements()).isEqualTo(5L);
        assertThat(paged.totalPages()).isEqualTo(3);
        assertThat(paged.hasNext()).isTrue();
        assertThat(paged.hasPrevious()).isFalse();
    }

    @Test
    @DisplayName("Verify ParcelAccessDeniedException invariants")
    void testParcelAccessDeniedException() {
        UUID parcelId = UUID.randomUUID();
        com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException ex =
                new com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException(parcelId);

        assertThat(ex.getParcelId()).isEqualTo(parcelId);
        assertThat(ex.getMessage()).contains(parcelId.toString());
    }
}
