package com.swiftdrop.grpc;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.pricing.v1.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContractDescriptorAndSerializationTest {

    @Test
    @DisplayName("Protobuf Descriptors: verified non-null and package names conform to specification")
    void testProtobufDescriptors() {
        assertThat(AccountsProto.getDescriptor().getPackage()).isEqualTo("swiftdrop.accounts.v1");
        assertThat(PricingProto.getDescriptor().getPackage()).isEqualTo("swiftdrop.pricing.v1");
        assertThat(DeliveryProto.getDescriptor().getPackage()).isEqualTo("swiftdrop.delivery.v1");

        // Verify Accounts service descriptor
        assertThat(AccountsInternalServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("swiftdrop.accounts.v1.AccountsInternalService");
        assertThat(AccountsInternalServiceGrpc.getServiceDescriptor().getMethods()).hasSize(8);

        // Verify Pricing service descriptor
        assertThat(PricingInternalServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("swiftdrop.pricing.v1.PricingInternalService");
        assertThat(PricingInternalServiceGrpc.getServiceDescriptor().getMethods()).hasSize(2);

        // Verify Delivery service descriptor
        assertThat(DeliveryInternalServiceGrpc.getServiceDescriptor().getName())
                .isEqualTo("swiftdrop.delivery.v1.DeliveryInternalService");
        assertThat(DeliveryInternalServiceGrpc.getServiceDescriptor().getMethods()).hasSize(6);
    }

    @Test
    @DisplayName("Binary Serialization: byte-for-byte serialization and parsing roundtrip")
    void testSerializationRoundtrip() throws Exception {
        Instant now = Instant.now();
        Timestamp ts = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();

        GetParcelInternalResponse original = GetParcelInternalResponse.newBuilder()
                .setParcelId(UUID.randomUUID().toString())
                .setTrackingNumber("SWD-2026-999999")
                .setCurrentStatus("PICKED_UP")
                .setVersion(1L)
                .setAmountMinorUnits(120050L) // 1,200.50 LKR
                .setCurrency("LKR")
                .setCreatedBySub("customer-123")
                .setCreatedAt(ts)
                .build();

        byte[] serialized = original.toByteArray();
        GetParcelInternalResponse deserialized = GetParcelInternalResponse.parseFrom(serialized);

        assertThat(deserialized).isEqualTo(original);
        assertThat(deserialized.getAmountMinorUnits()).isEqualTo(120050L);
        assertThat(deserialized.getCurrency()).isEqualTo("LKR");
        assertThat(deserialized.getCurrentStatus()).isEqualTo("PICKED_UP");
    }

    @Test
    @DisplayName("JSON Serialization: JsonFormat printer and parser preserve schema integrity")
    void testJsonSerializationRoundtrip() throws Exception {
        ValidateQuoteResponse original = ValidateQuoteResponse.newBuilder()
                .setIsValid(true)
                .setQuoteId("quote-uuid-abc")
                .setRateVersion("RATE-2026-V1")
                .setVerifiedAmountMinorUnits(50000L)
                .setCurrency("LKR")
                .setValidationCode("VALID")
                .setExpiresAt(Timestamp.newBuilder().setSeconds(1761400000).build())
                .build();

        String json = JsonFormat.printer().print(original);
        assertThat(json).contains("\"isValid\": true");
        assertThat(json).contains("\"currency\": \"LKR\"");
        assertThat(json).contains("\"verifiedAmountMinorUnits\": \"50000\"");

        ValidateQuoteResponse.Builder builder = ValidateQuoteResponse.newBuilder();
        JsonFormat.parser().merge(json, builder);
        ValidateQuoteResponse parsed = builder.build();

        assertThat(parsed).isEqualTo(original);
    }
}
