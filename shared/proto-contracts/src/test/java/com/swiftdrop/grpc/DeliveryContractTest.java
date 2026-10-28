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
    @DisplayName("AuthorizeEventRecipient: verifies event-to-recipient authorization contract roundtrip")
    void testAuthorizeEventRecipientSuccess() {
        String eventId = UUID.randomUUID().toString();
        String parcelId = UUID.randomUUID().toString();
        String recipientSub = UUID.randomUUID().toString();

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId)
                .setParcelId(parcelId)
                .setRecipientSub(recipientSub)
                .setAllowStale(false)
                .build();

        AuthorizeEventRecipientResponse response = blockingStub.authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isTrue();
        assertThat(response.getEventId()).isEqualTo(eventId);
        assertThat(response.getParcelId()).isEqualTo(parcelId);
        assertThat(response.getRecipientSub()).isEqualTo(recipientSub);
        assertThat(response.getTrackingNumber()).isEqualTo("SWD-2026-000001");
        assertThat(response.getCurrentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(response.getEventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(response.getEventVersion()).isEqualTo(3L);
        assertThat(response.getCurrentVersion()).isEqualTo(3L);
        assertThat(response.getIsTerminal()).isFalse();
        assertThat(response.getIsStale()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("AUTHORIZED");
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

    @Test
    @DisplayName("ListCustomerParcels: returns bounded customer parcel summaries and counts")
    void testListCustomerParcelsSuccess() {
        String customerSub = UUID.randomUUID().toString();
        ListCustomerParcelsRequest request = ListCustomerParcelsRequest.newBuilder()
                .setCustomerSub(customerSub)
                .setPage(0)
                .setPageSize(20)
                .build();

        ListCustomerParcelsResponse response = blockingStub.listCustomerParcels(request);

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getActiveCount()).isEqualTo(1);
        assertThat(response.getCompletedCount()).isEqualTo(0);
        assertThat(response.getParcelsList()).hasSize(1);
        CustomerParcelSummary parcel = response.getParcels(0);
        assertThat(parcel.getTrackingNumber()).isEqualTo("SWD-2026-CUST-01");
        assertThat(parcel.getCurrentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(parcel.getAmountMinorUnits()).isEqualTo(150000L);
    }

    @Test
    @DisplayName("ListMerchantOrders: returns bounded merchant tenant orders and outcome counts")
    void testListMerchantOrdersSuccess() {
        String merchantId = UUID.randomUUID().toString();
        ListMerchantOrdersRequest request = ListMerchantOrdersRequest.newBuilder()
                .setMerchantId(merchantId)
                .setPage(0)
                .setPageSize(20)
                .build();

        ListMerchantOrdersResponse response = blockingStub.listMerchantOrders(request);

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getActiveCount()).isEqualTo(1);
        assertThat(response.getDeliveredCount()).isEqualTo(0);
        assertThat(response.getFailedCount()).isEqualTo(0);
        assertThat(response.getOrdersList()).hasSize(1);
        MerchantOrderSummary order = response.getOrders(0);
        assertThat(order.getTrackingNumber()).isEqualTo("SWD-2026-MERCH-01");
        assertThat(order.getFeeMinorUnits()).isEqualTo(200000L);
    }

    @Test
    @DisplayName("ListCourierAssignments: returns bounded courier assignments and outcome counts")
    void testListCourierAssignmentsSuccess() {
        String courierSub = UUID.randomUUID().toString();
        ListCourierAssignmentsRequest request = ListCourierAssignmentsRequest.newBuilder()
                .setCourierSub(courierSub)
                .setPage(0)
                .setPageSize(20)
                .setActiveOnly(true)
                .build();

        ListCourierAssignmentsResponse response = blockingStub.listCourierAssignments(request);

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getActiveCount()).isEqualTo(1);
        assertThat(response.getTodayDeliveredCount()).isEqualTo(3);
        assertThat(response.getTodayFailedCount()).isEqualTo(0);
        assertThat(response.getAssignmentsList()).hasSize(1);
        CourierAssignmentSummary item = response.getAssignments(0);
        assertThat(item.getTrackingNumber()).isEqualTo("SWD-2026-COUR-01");
        assertThat(item.getCurrentStatus()).isEqualTo("OUT_FOR_DELIVERY");
    }

    @Test
    @DisplayName("GetDeliveryHistory: returns parcel delivery history timeline and latest status")
    void testGetDeliveryHistorySuccess() {
        String parcelId = UUID.randomUUID().toString();
        GetDeliveryHistoryRequest request = GetDeliveryHistoryRequest.newBuilder()
                .setParcelId(parcelId)
                .setPage(0)
                .setPageSize(20)
                .build();

        GetDeliveryHistoryResponse response = blockingStub.getDeliveryHistory(request);

        assertThat(response.getParcelId()).isEqualTo(parcelId);
        assertThat(response.getCurrentStatus()).isEqualTo("DELIVERED");
        assertThat(response.getCurrentVersion()).isEqualTo(4L);
        assertThat(response.getItemsList()).hasSize(2);
        assertThat(response.getItems(0).getNewStatus()).isEqualTo("CREATED");
        assertThat(response.getItems(1).getNewStatus()).isEqualTo("DELIVERED");
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

        @Override
        public void authorizeEventRecipient(AuthorizeEventRecipientRequest request,
                                            StreamObserver<AuthorizeEventRecipientResponse> responseObserver) {
            AuthorizeEventRecipientResponse response = AuthorizeEventRecipientResponse.newBuilder()
                    .setIsAuthorized(true)
                    .setEventId(request.getEventId())
                    .setParcelId(request.getParcelId())
                    .setRecipientSub(request.getRecipientSub())
                    .setTrackingNumber("SWD-2026-000001")
                    .setCurrentStatus("IN_TRANSIT")
                    .setEventType("parcel.status.changed.v1")
                    .setEventVersion(3L)
                    .setCurrentVersion(3L)
                    .setIsTerminal(false)
                    .setIsStale(false)
                    .setAuthorizationReason("AUTHORIZED")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void listCustomerParcels(ListCustomerParcelsRequest request,
                                        StreamObserver<ListCustomerParcelsResponse> responseObserver) {
            Instant now = Instant.now();
            Timestamp ts = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();

            CustomerParcelSummary parcel = CustomerParcelSummary.newBuilder()
                    .setParcelId(UUID.randomUUID().toString())
                    .setTrackingNumber("SWD-2026-CUST-01")
                    .setCurrentStatus("IN_TRANSIT")
                    .setAmountMinorUnits(150000L)
                    .setCurrency("LKR")
                    .setRecipientCity("Colombo")
                    .setCreatedAt(ts)
                    .setUpdatedAt(ts)
                    .setVersion(2L)
                    .build();

            ListCustomerParcelsResponse response = ListCustomerParcelsResponse.newBuilder()
                    .setTotalCount(1)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize())
                    .setActiveCount(1)
                    .setCompletedCount(0)
                    .addParcels(parcel)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void listMerchantOrders(ListMerchantOrdersRequest request,
                                       StreamObserver<ListMerchantOrdersResponse> responseObserver) {
            Instant now = Instant.now();
            Timestamp ts = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();

            MerchantOrderSummary order = MerchantOrderSummary.newBuilder()
                    .setParcelId(UUID.randomUUID().toString())
                    .setTrackingNumber("SWD-2026-MERCH-01")
                    .setCurrentStatus("CREATED")
                    .setWeightKg(2.5)
                    .setFeeMinorUnits(200000L)
                    .setCurrency("LKR")
                    .setCreatedAt(ts)
                    .setLastStatusUpdate(ts)
                    .setVersion(1L)
                    .build();

            ListMerchantOrdersResponse response = ListMerchantOrdersResponse.newBuilder()
                    .setTotalCount(1)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize())
                    .setActiveCount(1)
                    .setDeliveredCount(0)
                    .setFailedCount(0)
                    .addOrders(order)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void listCourierAssignments(ListCourierAssignmentsRequest request,
                                           StreamObserver<ListCourierAssignmentsResponse> responseObserver) {
            Instant now = Instant.now();
            Timestamp ts = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();

            CourierAssignmentSummary assignment = CourierAssignmentSummary.newBuilder()
                    .setAssignmentId(UUID.randomUUID().toString())
                    .setParcelId(UUID.randomUUID().toString())
                    .setTrackingNumber("SWD-2026-COUR-01")
                    .setCurrentStatus("OUT_FOR_DELIVERY")
                    .setPickupAddress("123 Galle Rd, Colombo 03")
                    .setDropoffAddress("456 Kandy Rd, Kelaniya")
                    .setRecipientName("Bob Silva")
                    .setRecipientPhone("+94771234567")
                    .setAssignedAt(ts)
                    .setVersion(2L)
                    .setIsActive(true)
                    .build();

            ListCourierAssignmentsResponse response = ListCourierAssignmentsResponse.newBuilder()
                    .setTotalCount(1)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize())
                    .setActiveCount(1)
                    .setTodayDeliveredCount(3)
                    .setTodayFailedCount(0)
                    .addAssignments(assignment)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getDeliveryHistory(GetDeliveryHistoryRequest request,
                                       StreamObserver<GetDeliveryHistoryResponse> responseObserver) {
            Instant now = Instant.now();
            Timestamp ts1 = Timestamp.newBuilder().setSeconds(now.minusSeconds(3600).getEpochSecond()).build();
            Timestamp ts2 = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();

            DeliveryStatusHistoryItem item1 = DeliveryStatusHistoryItem.newBuilder()
                    .setHistoryId(UUID.randomUUID().toString())
                    .setParcelId(request.getParcelId())
                    .setNewStatus("CREATED")
                    .setReason("BOOKING_CONFIRMED")
                    .setResultingVersion(1L)
                    .setOccurredAt(ts1)
                    .build();

            DeliveryStatusHistoryItem item2 = DeliveryStatusHistoryItem.newBuilder()
                    .setHistoryId(UUID.randomUUID().toString())
                    .setParcelId(request.getParcelId())
                    .setPreviousStatus("OUT_FOR_DELIVERY")
                    .setNewStatus("DELIVERED")
                    .setReason("DELIVERY_CONFIRMED")
                    .setResultingVersion(4L)
                    .setOccurredAt(ts2)
                    .build();

            GetDeliveryHistoryResponse response = GetDeliveryHistoryResponse.newBuilder()
                    .setParcelId(request.getParcelId())
                    .setTrackingNumber("SWD-2026-000001")
                    .setCurrentStatus("DELIVERED")
                    .setCurrentVersion(4L)
                    .setLatestStatusAt(ts2)
                    .setTotalCount(2)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize())
                    .addItems(item1)
                    .addItems(item2)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
