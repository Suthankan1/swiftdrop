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

    @Test
    @DisplayName("ListUnassignedParcels: returns paginated unassigned parcel summaries")
    void testListUnassignedParcelsSuccess() {
        ListUnassignedParcelsRequest request = ListUnassignedParcelsRequest.newBuilder()
                .setPage(0)
                .setPageSize(20)
                .setZone("CMB-01")
                .build();

        ListUnassignedParcelsResponse response = blockingStub.listUnassignedParcels(request);

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getPage()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(20);
        assertThat(response.getItemsList()).hasSize(1);
        UnassignedParcelSummary item = response.getItems(0);
        assertThat(item.getStatus()).isEqualTo("CREATED");
        assertThat(item.getTrackingNumber()).isEqualTo("SWD-2026-UNASSIGNED-01");
        assertThat(item.getPickupZone()).isEqualTo("CMB-01");
    }

    @Test
    @DisplayName("GetCourierWorkloadSummary: returns batch courier workload summaries")
    void testGetCourierWorkloadSummarySuccess() {
        String courier1 = UUID.randomUUID().toString();
        String courier2 = UUID.randomUUID().toString();
        CourierWorkloadSummaryRequest request = CourierWorkloadSummaryRequest.newBuilder()
                .addCourierSubs(courier1)
                .addCourierSubs(courier2)
                .build();

        CourierWorkloadSummaryResponse response = blockingStub.getCourierWorkloadSummary(request);

        assertThat(response.getWorkloadsList()).hasSize(2);
        assertThat(response.getWorkloads(0).getCourierSub()).isEqualTo(courier1);
        assertThat(response.getWorkloads(0).getActiveAssignmentsCount()).isEqualTo(2);
        assertThat(response.getWorkloads(1).getCourierSub()).isEqualTo(courier2);
        assertThat(response.getWorkloads(1).getActiveAssignmentsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("GetDeliveryStatsInternal: returns delivery outcome counts and average delivery hours")
    void testGetDeliveryStatsInternalSuccess() {
        Instant now = Instant.now();
        Instant start = now.minusSeconds(86400);
        GetDeliveryStatsRequest request = GetDeliveryStatsRequest.newBuilder()
                .setStartTime(Timestamp.newBuilder().setSeconds(start.getEpochSecond()).build())
                .setEndTime(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                .setZone("CMB-01")
                .setStatus("DELIVERED")
                .addAuthorizedZones("CMB-01")
                .build();

        GetDeliveryStatsResponse response = blockingStub.getDeliveryStatsInternal(request);

        assertThat(response.getTotalCount()).isEqualTo(42L);
        assertThat(response.getDeliveredCount()).isEqualTo(42L);
        assertThat(response.getFailedCount()).isEqualTo(3L);
        assertThat(response.getInTransitCount()).isEqualTo(10L);
        assertThat(response.getCreatedCount()).isEqualTo(55L);
        assertThat(response.hasAverageDeliveryHours()).isTrue();
        assertThat(response.getAverageDeliveryHours()).isEqualTo(3.5);
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

        @Override
        public void listUnassignedParcels(ListUnassignedParcelsRequest request,
                                          StreamObserver<ListUnassignedParcelsResponse> responseObserver) {
            Instant now = Instant.now();
            Timestamp ts = Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .setNanos(now.getNano())
                    .build();

            UnassignedParcelSummary item = UnassignedParcelSummary.newBuilder()
                    .setParcelId(UUID.randomUUID().toString())
                    .setTrackingNumber("SWD-2026-UNASSIGNED-01")
                    .setStatus("CREATED")
                    .setVersion(1L)
                    .setAmountMinorUnits(150000L)
                    .setCurrency("LKR")
                    .setWeightKg(1.25)
                    .setCreatedAt(ts)
                    .setSenderSub(UUID.randomUUID().toString())
                    .setPickupZone(request.getZone().isBlank() ? "CMB-01" : request.getZone())
                    .setDropoffZone("CMB-02")
                    .build();

            ListUnassignedParcelsResponse response = ListUnassignedParcelsResponse.newBuilder()
                    .setTotalCount(1)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize())
                    .addItems(item)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getCourierWorkloadSummary(CourierWorkloadSummaryRequest request,
                                             StreamObserver<CourierWorkloadSummaryResponse> responseObserver) {
            CourierWorkloadSummaryResponse.Builder builder = CourierWorkloadSummaryResponse.newBuilder();
            int count = 2;
            for (String sub : request.getCourierSubsList()) {
                builder.addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(sub)
                        .setActiveAssignmentsCount(count)
                        .build());
                count = 0;
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        }

        @Override
        public void getDeliveryStatsInternal(GetDeliveryStatsRequest request,
                                             StreamObserver<GetDeliveryStatsResponse> responseObserver) {
            GetDeliveryStatsResponse response = GetDeliveryStatsResponse.newBuilder()
                    .setTotalCount(42L)
                    .setDeliveredCount(42L)
                    .setFailedCount(3L)
                    .setInTransitCount(10L)
                    .setCreatedCount(55L)
                    .setAverageDeliveryHours(3.5)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
