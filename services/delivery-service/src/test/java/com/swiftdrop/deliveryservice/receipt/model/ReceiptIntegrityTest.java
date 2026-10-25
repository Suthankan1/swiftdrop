package com.swiftdrop.deliveryservice.receipt.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptIntegrityTest {

    private final UUID parcelId = UUID.randomUUID();
    private final String trackingNumber = "SWD-20261025-A1B2C3";
    private final String sub = "auth0|user-123";
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Unconfirmed payment cannot be represented as paid receipt")
    void unconfirmedPaymentFailsClosed() {
        assertThatThrownBy(() -> new ReceiptData(
                "RCP-1",
                parcelId,
                trackingNumber,
                sub,
                null,
                "Recipient",
                "+94771234567",
                "Pickup",
                "Dropoff",
                "DELIVERED",
                now,
                now,
                "RATE-V1",
                "PAY-TX-1",
                false, // Payment NOT confirmed
                "LKR",
                50000L,
                List.of(new ReceiptLineItem("Delivery", 50000L, "LKR"))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Receipt cannot be issued for unconfirmed payment");
    }

    @Test
    @DisplayName("Non-completed parcel status (e.g. CREATED, IN_TRANSIT) cannot generate receipt")
    void nonCompletedStatusFailsClosed() {
        for (String invalidStatus : List.of("CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "FAILED")) {
            assertThatThrownBy(() -> new ReceiptData(
                    "RCP-1",
                    parcelId,
                    trackingNumber,
                    sub,
                    null,
                    "Recipient",
                    "+94771234567",
                    "Pickup",
                    "Dropoff",
                    invalidStatus,
                    now,
                    now,
                    "RATE-V1",
                    "PAY-TX-1",
                    true,
                    "LKR",
                    50000L,
                    List.of(new ReceiptLineItem("Delivery", 50000L, "LKR"))
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Receipt is only available for completed/delivered parcels");
        }
    }

    @Test
    @DisplayName("Empty line items list is rejected")
    void emptyLineItemsRejected() {
        assertThatThrownBy(() -> new ReceiptData(
                "RCP-1",
                parcelId,
                trackingNumber,
                sub,
                null,
                "Recipient",
                "+94771234567",
                "Pickup",
                "Dropoff",
                "DELIVERED",
                now,
                now,
                "RATE-V1",
                "PAY-TX-1",
                true,
                "LKR",
                50000L,
                Collections.emptyList()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain at least one line item");
    }

    @Test
    @DisplayName("Negative financial amounts are strictly rejected")
    void negativeAmountRejected() {
        assertThatThrownBy(() -> new ReceiptData(
                "RCP-1",
                parcelId,
                trackingNumber,
                sub,
                null,
                "Recipient",
                "+94771234567",
                "Pickup",
                "Dropoff",
                "DELIVERED",
                now,
                now,
                "RATE-V1",
                "PAY-TX-1",
                true,
                "LKR",
                -100L,
                List.of(new ReceiptLineItem("Delivery", 50000L, "LKR"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountMinorUnits cannot be negative");

        assertThatThrownBy(() -> new ReceiptLineItem("Delivery", -500L, "LKR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    @DisplayName("Invalid currency codes are rejected")
    void invalidCurrencyRejected() {
        assertThatThrownBy(() -> new ReceiptData(
                "RCP-1",
                parcelId,
                trackingNumber,
                sub,
                null,
                "Recipient",
                "+94771234567",
                "Pickup",
                "Dropoff",
                "DELIVERED",
                now,
                now,
                "RATE-V1",
                "PAY-TX-1",
                true,
                "INVALID_CURRENCY",
                50000L,
                List.of(new ReceiptLineItem("Delivery", 50000L, "LKR"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency must be an ISO-4217 3-letter code");
    }

    @Test
    @DisplayName("Invalid tracking number format is rejected")
    void invalidTrackingNumberRejected() {
        assertThatThrownBy(() -> new ReceiptData(
                "RCP-1",
                parcelId,
                "MALFORMED-TRACKING",
                sub,
                null,
                "Recipient",
                "+94771234567",
                "Pickup",
                "Dropoff",
                "DELIVERED",
                now,
                now,
                "RATE-V1",
                "PAY-TX-1",
                true,
                "LKR",
                50000L,
                List.of(new ReceiptLineItem("Delivery", 50000L, "LKR"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid tracking number format");
    }
}
