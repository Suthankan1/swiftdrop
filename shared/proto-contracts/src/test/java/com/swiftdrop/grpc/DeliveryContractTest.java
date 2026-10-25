package com.swiftdrop.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.delivery.v1.*;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryContractTest {

    private Server server;
    private ManagedChannel channel;
    private DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new MockDeliveryService())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        blockingStub = DeliveryInternalServiceGrpc.newBlockingStub(channel);
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
    @DisplayName("GetParcelInternal: resolves internal parcel projection with minor units and UTC timestamp")
    void testGetParcelInternalSuccess() {
        String parcelId = UUID.randomUUID().toString();
        GetParcelInternalRequest request = GetParcelInternalRequest.newBuilder()
                .setParcelId(parcelId)
                .build();

        GetParcelInternalResponse response = blockingStub.getParcelInternal(request);

        assertThat(response.getParcelId()).isEqualTo(parcelId);
        assertThat(response.getTrackingNumber()).isEqualTo("SWD-2026-000001");
        assertThat(response.getCurrentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(response.getVersion()).isEqualTo(3L);
        assertThat(response.getAmountMinorUnits()).isEqualTo(250000L); // 2,500.00 LKR
        assertThat(response.getCurrency()).isEqualTo("LKR");
        assertThat(response.getActiveCourierSub()).isNotEmpty();
        assertThat(response.getCreatedAt().getSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("GetCourierWorkload: returns current active assignments count")
    void testGetCourierWorkloadSuccess() {
        String courierSub = UUID.randomUUID().toString();
        CourierWorkloadRequest request = CourierWorkloadRequest.newBuilder()
                .setCourierSub(courierSub)
                .build();

        CourierWorkloadResponse response = blockingStub.getCourierWorkload(request);

        assertThat(response.getCourierSub()).isEqualTo(courierSub);
        assertThat(response.getActiveAssignmentsCount()).isEqualTo(3);
    }

    private static class MockDeliveryService extends DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase {
        @Override
        public void getParcelInternal(GetParcelInternalRequest request,
                                      StreamObserver<GetParcelInternalResponse> responseObserver) {
            Instant now = Instant.now();
            Timestamp ts = Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .setNanos(now.getNano())
                    .build();

            GetParcelInternalResponse response = GetParcelInternalResponse.newBuilder()
                    .setParcelId(request.getParcelId())
                    .setTrackingNumber("SWD-2026-000001")
                    .setCurrentStatus("IN_TRANSIT")
                    .setVersion(3L)
                    .setAmountMinorUnits(250000L)
                    .setCurrency("LKR")
                    .setCreatedBySub(UUID.randomUUID().toString())
                    .setMerchantId(UUID.randomUUID().toString())
                    .setActiveCourierSub(UUID.randomUUID().toString())
                    .setCreatedAt(ts)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getCourierWorkload(CourierWorkloadRequest request,
                                       StreamObserver<CourierWorkloadResponse> responseObserver) {
            CourierWorkloadResponse response = CourierWorkloadResponse.newBuilder()
                    .setCourierSub(request.getCourierSub())
                    .setActiveAssignmentsCount(3)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
