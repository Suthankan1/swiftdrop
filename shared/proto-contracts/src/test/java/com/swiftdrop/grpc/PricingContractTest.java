package com.swiftdrop.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.pricing.v1.PricingInternalServiceGrpc;
import com.swiftdrop.pricing.v1.ValidateQuoteRequest;
import com.swiftdrop.pricing.v1.ValidateQuoteResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PricingContractTest {

    private Server server;
    private ManagedChannel channel;
    private PricingInternalServiceGrpc.PricingInternalServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new MockPricingService())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        blockingStub = PricingInternalServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("ValidateQuote: valid quote returns valid status with verified minor units and additive snapshot")
    void testValidateQuoteSuccess() {
        String quoteId = UUID.randomUUID().toString();
        ValidateQuoteRequest request = ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId)
                .setExpectedRateVersion("RATE-2026-V1")
                .setExpectedAmountMinorUnits(150000) // 1,500.00 LKR
                .setExpectedCurrency("LKR")
                .build();

        ValidateQuoteResponse response = blockingStub.validateQuote(request);

        assertThat(response.getIsValid()).isTrue();
        assertThat(response.getQuoteId()).isEqualTo(quoteId);
        assertThat(response.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(response.getVerifiedAmountMinorUnits()).isEqualTo(150000L);
        assertThat(response.getCurrency()).isEqualTo("LKR");
        assertThat(response.getValidationCode()).isEqualTo("VALID");
        assertThat(response.getExpiresAt().getSeconds()).isGreaterThan(0);
        assertThat(response.getPickupZone()).isEqualTo("ZONE_1");
        assertThat(response.getDropoffZone()).isEqualTo("ZONE_2");
        assertThat(response.getSubjectSub()).isEqualTo("user-alice-123");
        assertThat(response.getBaseFeeMinorUnits()).isEqualTo(100000L);
        assertThat(response.getWeightSurchargeMinorUnits()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("ValidateQuote: amount mismatch returns invalid code")
    void testValidateQuoteAmountMismatch() {
        String quoteId = UUID.randomUUID().toString();
        ValidateQuoteRequest request = ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId)
                .setExpectedRateVersion("RATE-2026-V1")
                .setExpectedAmountMinorUnits(999999) // Tampered amount
                .setExpectedCurrency("LKR")
                .build();

        ValidateQuoteResponse response = blockingStub.validateQuote(request);

        assertThat(response.getIsValid()).isFalse();
        assertThat(response.getValidationCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("GetPricingAdminSummary: returns bounded zones and rate versions")
    void testGetPricingAdminSummary() {
        com.swiftdrop.pricing.v1.PricingAdminSummaryRequest request =
                com.swiftdrop.pricing.v1.PricingAdminSummaryRequest.newBuilder()
                        .setActiveOnly(true)
                        .setPage(0)
                        .setPageSize(20)
                        .build();

        com.swiftdrop.pricing.v1.PricingAdminSummaryResponse response = blockingStub.getPricingAdminSummary(request);

        assertThat(response.getZonesCount()).isEqualTo(1);
        assertThat(response.getZones(0).getZoneCode()).isEqualTo("ZONE_1");
        assertThat(response.getRateVersionsCount()).isEqualTo(1);
        assertThat(response.getRateVersions(0).getVersionCode()).isEqualTo("RATE-2026-V1");
        assertThat(response.getTotalZones()).isEqualTo(1);
        assertThat(response.getTotalRateVersions()).isEqualTo(1);
    }

    private static class MockPricingService extends PricingInternalServiceGrpc.PricingInternalServiceImplBase {
        @Override
        public void validateQuote(ValidateQuoteRequest request,
                                  StreamObserver<ValidateQuoteResponse> responseObserver) {
            boolean matchesAmount = (request.getExpectedAmountMinorUnits() == 150000L);
            Instant expiry = Instant.now().plus(15, ChronoUnit.MINUTES);
            Timestamp ts = Timestamp.newBuilder()
                    .setSeconds(expiry.getEpochSecond())
                    .setNanos(expiry.getNano())
                    .build();

            ValidateQuoteResponse.Builder builder = ValidateQuoteResponse.newBuilder()
                    .setQuoteId(request.getQuoteId())
                    .setRateVersion(request.getExpectedRateVersion())
                    .setVerifiedAmountMinorUnits(150000L)
                    .setCurrency("LKR")
                    .setExpiresAt(ts)
                    .setPickupZone("ZONE_1")
                    .setDropoffZone("ZONE_2")
                    .setSubjectSub("user-alice-123")
                    .setBaseFeeMinorUnits(100000L)
                    .setWeightSurchargeMinorUnits(50000L);

            if (matchesAmount) {
                builder.setIsValid(true).setValidationCode("VALID");
            } else {
                builder.setIsValid(false).setValidationCode("AMOUNT_MISMATCH");
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        }

        @Override
        public void getPricingAdminSummary(com.swiftdrop.pricing.v1.PricingAdminSummaryRequest request,
                                           StreamObserver<com.swiftdrop.pricing.v1.PricingAdminSummaryResponse> responseObserver) {
            com.swiftdrop.pricing.v1.PricingZoneSummaryItem zone =
                    com.swiftdrop.pricing.v1.PricingZoneSummaryItem.newBuilder()
                            .setZoneId(UUID.randomUUID().toString())
                            .setZoneCode("ZONE_1")
                            .setName("Colombo Municipal")
                            .setIsActive(true)
                            .build();

            com.swiftdrop.pricing.v1.RateVersionSummaryItem rate =
                    com.swiftdrop.pricing.v1.RateVersionSummaryItem.newBuilder()
                            .setRateVersionId(UUID.randomUUID().toString())
                            .setVersionCode("RATE-2026-V1")
                            .setCurrency("LKR")
                            .setStatus("ACTIVE")
                            .setRuleCount(6)
                            .build();

            com.swiftdrop.pricing.v1.PricingAdminSummaryResponse response =
                    com.swiftdrop.pricing.v1.PricingAdminSummaryResponse.newBuilder()
                            .addZones(zone)
                            .addRateVersions(rate)
                            .setTotalZones(1)
                            .setTotalRateVersions(1)
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
