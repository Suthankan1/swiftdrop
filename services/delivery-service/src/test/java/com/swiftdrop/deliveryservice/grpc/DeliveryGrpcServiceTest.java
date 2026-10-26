package com.swiftdrop.deliveryservice.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignment;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.outbox.OutboxEventRepository;
import com.swiftdrop.deliveryservice.query.ParcelProjection;
import com.swiftdrop.deliveryservice.query.ParcelQueryService;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.grpc.SwiftDropGrpcServerSecurityInterceptor;
import com.swiftdrop.security.service.JwtServiceTokenValidator;
import com.swiftdrop.security.service.ServiceTokenValidator;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeliveryGrpcServiceTest {

    private final JwtTestHelper jwtHelper = new JwtTestHelper();

    private ParcelRepository parcelRepository;
    private OutboxEventRepository outboxEventRepository;
    private DeliveryAssignmentRepository assignmentRepository;
    private StatusHistoryRepository historyRepository;
    private ParcelQueryService parcelQueryService;

    private Server server;
    private ManagedChannel channel;

    private final String dispatcherSub = "disp-user-001";
    private final String adminSub = "admin-user-001";
    private final String customerSub = "cust-user-001";

    @BeforeEach
    void setUp() throws Exception {
        parcelRepository = mock(ParcelRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        assignmentRepository = mock(DeliveryAssignmentRepository.class);
        historyRepository = mock(StatusHistoryRepository.class);
        parcelQueryService = mock(ParcelQueryService.class);

        DeliveryInternalServiceImpl serviceImpl = new DeliveryInternalServiceImpl(
                parcelRepository,
                outboxEventRepository,
                assignmentRepository,
                historyRepository,
                parcelQueryService
        );

        ServiceTokenValidator serviceValidator = new JwtServiceTokenValidator(
                jwtHelper.createServiceJwtDecoder(),
                JwtTestHelper.DEFAULT_SERVICE_AUDIENCE,
                JwtTestHelper.DEFAULT_ISSUER
        );

        ActorDelegationValidator actorValidator = new ActorDelegationValidator(
                jwtHelper.createUserJwtDecoder()
        );

        SwiftDropGrpcServerSecurityInterceptor securityInterceptor =
                new SwiftDropGrpcServerSecurityInterceptor(serviceValidator, actorValidator, new GrpcSecurityPolicy());

        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(securityInterceptor)
                .addService(serviceImpl)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
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

    private DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub createStub(
            String serviceName, String actorSub, List<String> actorRoles) {
        return createStub(serviceName, actorSub, actorRoles, null);
    }

    private DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub createStub(
            String serviceName, String actorSub, List<String> actorRoles, Map<String, Object> extraUserClaims) {

        Metadata headers = new Metadata();
        if (serviceName != null) {
            String serviceToken = jwtHelper.createSignedServiceToken(
                    serviceName,
                    JwtTestHelper.DEFAULT_SERVICE_AUDIENCE,
                    JwtTestHelper.DEFAULT_ISSUER,
                    Instant.now().plus(1, ChronoUnit.HOURS),
                    actorSub,
                    actorRoles
            );
            headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        }

        if (actorSub != null) {
            headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
            if (actorRoles != null) {
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, String.join(",", actorRoles));
                String userToken = jwtHelper.createSignedUserToken(actorSub, actorRoles, extraUserClaims);
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, userToken);
            }
        }

        return DeliveryInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Test
    @DisplayName("ListUnassignedParcels succeeds for authorized DISPATCHER")
    void testListUnassignedParcels_Success_Dispatcher() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId, "SWD-2026-000101", "sender-1", null, null, "v1", 150000L, "LKR", new BigDecimal("2.500")
        );
        parcel.setCreatedAt(Instant.parse("2026-10-29T10:00:00Z"));

        when(parcelRepository.findUnassignedParcels(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(parcel), PageRequest.of(0, 20), 1));

        ListUnassignedParcelsResponse response = stub.listUnassignedParcels(
                ListUnassignedParcelsRequest.newBuilder().setPage(0).setPageSize(20).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getPage()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(20);
        assertThat(response.getItemsCount()).isEqualTo(1);
        assertThat(response.getItems(0).getParcelId()).isEqualTo(parcelId.toString());
        assertThat(response.getItems(0).getTrackingNumber()).isEqualTo("SWD-2026-000101");
        assertThat(response.getItems(0).getStatus()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("ListUnassignedParcels succeeds for authorized ADMIN")
    void testListUnassignedParcels_Success_Admin() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), adminSub, List.of("ADMIN"));

        when(parcelRepository.findUnassignedParcels(any()))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0));

        ListUnassignedParcelsResponse response = stub.listUnassignedParcels(
                ListUnassignedParcelsRequest.newBuilder().setPage(0).setPageSize(20).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(0);
        assertThat(response.getItemsList()).isEmpty();
    }

    @Test
    @DisplayName("ListUnassignedParcels rejected if service token is missing")
    void testListUnassignedParcels_MissingServiceToken() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(null, dispatcherSub, List.of("DISPATCHER"));

        assertThatThrownBy(() -> stub.listUnassignedParcels(ListUnassignedParcelsRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("ListUnassignedParcels rejected if calling service is unauthorized")
    void testListUnassignedParcels_UnauthorizedCallingService() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.ACCOUNTS_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        assertThatThrownBy(() -> stub.listUnassignedParcels(ListUnassignedParcelsRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("ListUnassignedParcels rejected if actor delegation is missing")
    void testListUnassignedParcels_MissingActorDelegation() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), null, null);

        assertThatThrownBy(() -> stub.listUnassignedParcels(ListUnassignedParcelsRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("ListUnassignedParcels rejected if actor role is CUSTOMER")
    void testListUnassignedParcels_UnauthorizedCustomerRole() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        assertThatThrownBy(() -> stub.listUnassignedParcels(ListUnassignedParcelsRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("ListUnassignedParcels rejects negative page index")
    void testListUnassignedParcels_NegativePageIndex() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        assertThatThrownBy(() -> stub.listUnassignedParcels(
                ListUnassignedParcelsRequest.newBuilder().setPage(-1).setPageSize(20).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("ListUnassignedParcels rejects excessive page size (>50)")
    void testListUnassignedParcels_ExcessivePageSize() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        assertThatThrownBy(() -> stub.listUnassignedParcels(
                ListUnassignedParcelsRequest.newBuilder().setPage(0).setPageSize(51).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("GetCourierWorkloadSummary returns batch counts and 0 for couriers without assignments")
    void testGetCourierWorkloadSummary_Success() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        List<Object[]> queryResults = List.of(
                new Object[]{"courier-1", 3L},
                new Object[]{"courier-2", 1L}
        );
        when(assignmentRepository.countActiveAssignmentsByCourierSubs(any())).thenReturn(queryResults);

        CourierWorkloadSummaryResponse response = stub.getCourierWorkloadSummary(
                CourierWorkloadSummaryRequest.newBuilder()
                        .addAllCourierSubs(List.of("courier-1", "courier-2", "courier-3"))
                        .build()
        );

        assertThat(response.getWorkloadsCount()).isEqualTo(3);
        assertThat(response.getWorkloads(0).getCourierSub()).isEqualTo("courier-1");
        assertThat(response.getWorkloads(0).getActiveAssignmentsCount()).isEqualTo(3);
        assertThat(response.getWorkloads(1).getCourierSub()).isEqualTo("courier-2");
        assertThat(response.getWorkloads(1).getActiveAssignmentsCount()).isEqualTo(1);
        assertThat(response.getWorkloads(2).getCourierSub()).isEqualTo("courier-3");
        assertThat(response.getWorkloads(2).getActiveAssignmentsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("GetCourierWorkloadSummary rejects batch size exceeding 50 couriers")
    void testGetCourierWorkloadSummary_ExcessiveBatch() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        List<String> largeBatch = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            largeBatch.add("courier-" + i);
        }

        assertThatThrownBy(() -> stub.getCourierWorkloadSummary(
                CourierWorkloadSummaryRequest.newBuilder().addAllCourierSubs(largeBatch).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("GetDeliveryStatsInternal computes aggregates and average duration")
    void testGetDeliveryStatsInternal_Success() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        Instant start = Instant.parse("2026-10-29T00:00:00Z");
        Instant end = Instant.parse("2026-10-30T00:00:00Z");

        when(parcelRepository.countCreatedBetween(start, end)).thenReturn(10L);
        when(historyRepository.countDeliveredBetween(start, end)).thenReturn(5L);
        when(historyRepository.countFailedBetween(start, end)).thenReturn(1L);
        when(parcelRepository.countInTransitBeforeOrAt(end)).thenReturn(4L);

        List<Object[]> durations = List.of(
                new Object[]{Instant.parse("2026-10-29T02:00:00Z"), Instant.parse("2026-10-29T04:00:00Z")}, // 2 hours
                new Object[]{Instant.parse("2026-10-29T03:00:00Z"), Instant.parse("2026-10-29T06:00:00Z")}  // 3 hours -> avg 2.5 hours
        );
        when(historyRepository.findDeliveredParcelDurations(start, end)).thenReturn(durations);

        GetDeliveryStatsResponse response = stub.getDeliveryStatsInternal(
                GetDeliveryStatsRequest.newBuilder()
                        .setStartTime(Timestamp.newBuilder().setSeconds(start.getEpochSecond()).build())
                        .setEndTime(Timestamp.newBuilder().setSeconds(end.getEpochSecond()).build())
                        .build()
        );

        assertThat(response.getCreatedCount()).isEqualTo(10L);
        assertThat(response.getDeliveredCount()).isEqualTo(5L);
        assertThat(response.getFailedCount()).isEqualTo(1L);
        assertThat(response.getInTransitCount()).isEqualTo(4L);
        assertThat(response.getTotalCount()).isEqualTo(20L);
        assertThat(response.hasAverageDeliveryHours()).isTrue();
        assertThat(response.getAverageDeliveryHours()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("GetDeliveryStatsInternal rejects reversed date range")
    void testGetDeliveryStatsInternal_ReversedRange() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        Instant start = Instant.parse("2026-10-30T00:00:00Z");
        Instant end = Instant.parse("2026-10-29T00:00:00Z");

        assertThatThrownBy(() -> stub.getDeliveryStatsInternal(
                GetDeliveryStatsRequest.newBuilder()
                        .setStartTime(Timestamp.newBuilder().setSeconds(start.getEpochSecond()).build())
                        .setEndTime(Timestamp.newBuilder().setSeconds(end.getEpochSecond()).build())
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("GetDeliveryStatsInternal rejects date range > 90 days")
    void testGetDeliveryStatsInternal_ExcessiveRange() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-01T00:00:00Z"); // > 90 days

        assertThatThrownBy(() -> stub.getDeliveryStatsInternal(
                GetDeliveryStatsRequest.newBuilder()
                        .setStartTime(Timestamp.newBuilder().setSeconds(start.getEpochSecond()).build())
                        .setEndTime(Timestamp.newBuilder().setSeconds(end.getEpochSecond()).build())
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("GetParcelInternal returns parcel projection for existing parcel")
    void testGetParcelInternal_Found() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID parcelId = UUID.randomUUID();
        ParcelProjection projection = new ParcelProjection(
                parcelId, "SWD-2026-000001", ParcelStatus.CREATED, 1L, 120000L, "LKR", "sender-1", null, null, Instant.now()
        );
        when(parcelQueryService.findParcelById(parcelId)).thenReturn(Optional.of(projection));

        GetParcelInternalResponse response = stub.getParcelInternal(
                GetParcelInternalRequest.newBuilder().setParcelId(parcelId.toString()).build()
        );

        assertThat(response.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(response.getTrackingNumber()).isEqualTo("SWD-2026-000001");
        assertThat(response.getCurrentStatus()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("GetParcelInternal returns NOT_FOUND for non-existent parcel")
    void testGetParcelInternal_NotFound() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID parcelId = UUID.randomUUID();
        when(parcelQueryService.findParcelById(parcelId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stub.getParcelInternal(
                GetParcelInternalRequest.newBuilder().setParcelId(parcelId.toString()).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    @DisplayName("GetCourierWorkload returns active assignment count for courier")
    void testGetCourierWorkload_Success() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        when(assignmentRepository.countByCourierSubAndActiveTrue("courier-kamal")).thenReturn(2);

        CourierWorkloadResponse response = stub.getCourierWorkload(
                CourierWorkloadRequest.newBuilder().setCourierSub("courier-kamal").build()
        );

        assertThat(response.getCourierSub()).isEqualTo("courier-kamal");
        assertThat(response.getActiveAssignmentsCount()).isEqualTo(2);
    }

    // =========================================================================
    // Issue #112: Authorized Customer Owned-Parcel Read Tests
    // =========================================================================

    @Test
    @DisplayName("ListCustomerParcels: succeeds for authorized CUSTOMER and returns owned summaries")
    void testListCustomerParcels_Success_Customer() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(parcelId, "SWD-2026-CUST-01", customerSub, null, null, "v1", 120000L, "LKR", new BigDecimal("1.500"));
        parcel.setCreatedAt(Instant.parse("2026-10-25T10:00:00Z"));
        parcel.setUpdatedAt(Instant.parse("2026-10-25T11:00:00Z"));

        when(parcelRepository.countActiveBySenderSub(customerSub)).thenReturn(1);
        when(parcelRepository.countCompletedBySenderSub(customerSub)).thenReturn(2);
        when(parcelRepository.findBySenderSubOrderByCreatedAtDesc(eq(customerSub), any()))
                .thenReturn(new PageImpl<>(List.of(parcel), PageRequest.of(0, 20), 3));

        ListCustomerParcelsResponse response = stub.listCustomerParcels(
                ListCustomerParcelsRequest.newBuilder().setPage(0).setPageSize(20).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(3);
        assertThat(response.getActiveCount()).isEqualTo(1);
        assertThat(response.getCompletedCount()).isEqualTo(2);
        assertThat(response.getParcelsCount()).isEqualTo(1);
        CustomerParcelSummary item = response.getParcels(0);
        assertThat(item.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(item.getTrackingNumber()).isEqualTo("SWD-2026-CUST-01");
        assertThat(item.getCurrentStatus()).isEqualTo("CREATED");
        assertThat(item.getAmountMinorUnits()).isEqualTo(120000L);
    }

    @Test
    @DisplayName("ListCustomerParcels: cross-customer read is rejected with PERMISSION_DENIED")
    void testListCustomerParcels_CrossCustomerDenied() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        assertThatThrownBy(() -> stub.listCustomerParcels(
                ListCustomerParcelsRequest.newBuilder().setCustomerSub("other-customer-sub").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("ListCustomerParcels: staff can query specific customer_sub")
    void testListCustomerParcels_Success_Staff() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, List.of("DISPATCHER"));

        String targetCustomer = "cust-target-123";
        when(parcelRepository.countActiveBySenderSub(targetCustomer)).thenReturn(0);
        when(parcelRepository.countCompletedBySenderSub(targetCustomer)).thenReturn(1);
        when(parcelRepository.findBySenderSubOrderByCreatedAtDesc(eq(targetCustomer), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 1));

        ListCustomerParcelsResponse response = stub.listCustomerParcels(
                ListCustomerParcelsRequest.newBuilder().setCustomerSub(targetCustomer).setPage(0).setPageSize(20).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getCompletedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ListCustomerParcels: batch query filters strictly to customer's parcels")
    void testListCustomerParcels_BatchQuery_OmitUnauthorized() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID ownedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        Parcel ownedParcel = new Parcel(ownedId, "SWD-2026-OWNED-01", customerSub, null, null, "v1", 100000L, "LKR", new BigDecimal("1.0"));
        when(parcelRepository.countActiveBySenderSub(customerSub)).thenReturn(1);
        when(parcelRepository.countCompletedBySenderSub(customerSub)).thenReturn(0);
        when(parcelRepository.findBySenderSubAndParcelIdIn(eq(customerSub), any()))
                .thenReturn(List.of(ownedParcel));

        ListCustomerParcelsResponse response = stub.listCustomerParcels(
                ListCustomerParcelsRequest.newBuilder()
                        .addParcelIds(ownedId.toString())
                        .addParcelIds(otherId.toString())
                        .build()
        );

        assertThat(response.getParcelsCount()).isEqualTo(1);
        assertThat(response.getParcels(0).getParcelId()).isEqualTo(ownedId.toString());
    }

    // =========================================================================
    // Issue #112: Authorized Merchant Tenant-Order Read Tests
    // =========================================================================

    @Test
    @DisplayName("ListMerchantOrders: succeeds for authorized MERCHANT with verified tenant claim")
    void testListMerchantOrders_Success_Merchant() {
        UUID merchantId = UUID.randomUUID();
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), "merchant-actor-01", List.of("MERCHANT"),
                        Map.of("merchant_id", merchantId.toString(), "merchant_status", "APPROVED"));

        UUID parcelId = UUID.randomUUID();
        Parcel order = new Parcel(parcelId, "SWD-2026-MERCH-01", "sender-sub", merchantId, null, "v1", 250000L, "LKR", new BigDecimal("3.000"));

        when(parcelRepository.countActiveByMerchantId(merchantId)).thenReturn(1);
        when(parcelRepository.countDeliveredByMerchantId(merchantId)).thenReturn(5);
        when(parcelRepository.countFailedByMerchantId(merchantId)).thenReturn(0);
        when(parcelRepository.countByMerchantId(merchantId)).thenReturn(6);
        when(parcelRepository.findByMerchantIdOrderByCreatedAtDesc(eq(merchantId), any()))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 6));

        ListMerchantOrdersResponse response = stub.listMerchantOrders(
                ListMerchantOrdersRequest.newBuilder().setMerchantId(merchantId.toString()).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(6);
        assertThat(response.getActiveCount()).isEqualTo(1);
        assertThat(response.getDeliveredCount()).isEqualTo(5);
        assertThat(response.getOrdersCount()).isEqualTo(1);
        MerchantOrderSummary summary = response.getOrders(0);
        assertThat(summary.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(summary.getFeeMinorUnits()).isEqualTo(250000L);
    }

    @Test
    @DisplayName("ListMerchantOrders: cross-tenant read attempt is rejected with PERMISSION_DENIED")
    void testListMerchantOrders_CrossTenantDenied() {
        UUID actorMerchantId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();

        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), "merchant-actor-01", List.of("MERCHANT"),
                        Map.of("merchant_id", actorMerchantId.toString(), "merchant_status", "APPROVED"));

        assertThatThrownBy(() -> stub.listMerchantOrders(
                ListMerchantOrdersRequest.newBuilder().setMerchantId(otherMerchantId.toString()).build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("ListMerchantOrders: merchant actor without tenant_id claim is denied")
    void testListMerchantOrders_MissingMerchantTenantDenied() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), "merchant-actor-02", List.of("MERCHANT"),
                        Collections.emptyMap());

        assertThatThrownBy(() -> stub.listMerchantOrders(
                ListMerchantOrdersRequest.newBuilder().build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    // =========================================================================
    // Issue #112: Authorized Courier Current-Assignment Read Tests
    // =========================================================================

    @Test
    @DisplayName("ListCourierAssignments: succeeds for authorized COURIER and returns assignments")
    void testListCourierAssignments_Success_Courier() {
        String courierSub = "courier-nimal";
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), courierSub, List.of("COURIER"));

        UUID parcelId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        DeliveryAssignment assignment = new DeliveryAssignment(assignmentId, parcelId, courierSub, "dispatcher-1", Instant.now(), null, true);
        Parcel parcel = new Parcel(parcelId, "SWD-2026-COUR-01", "sender-sub", null, null, "v1", 100000L, "LKR", new BigDecimal("1.0"));

        when(assignmentRepository.countByCourierSubAndActiveTrue(courierSub)).thenReturn(1);
        when(historyRepository.countCourierDeliveredBetween(eq(courierSub), any(), any())).thenReturn(4);
        when(historyRepository.countCourierFailedBetween(eq(courierSub), any(), any())).thenReturn(1);
        when(assignmentRepository.findActiveByCourierSub(eq(courierSub), any()))
                .thenReturn(new PageImpl<>(List.of(assignment), PageRequest.of(0, 20), 1));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        ListCourierAssignmentsResponse response = stub.listCourierAssignments(
                ListCourierAssignmentsRequest.newBuilder().setActiveOnly(true).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getActiveCount()).isEqualTo(1);
        assertThat(response.getTodayDeliveredCount()).isEqualTo(4);
        assertThat(response.getTodayFailedCount()).isEqualTo(1);
        assertThat(response.getAssignmentsCount()).isEqualTo(1);
        CourierAssignmentSummary item = response.getAssignments(0);
        assertThat(item.getAssignmentId()).isEqualTo(assignmentId.toString());
        assertThat(item.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(item.getTrackingNumber()).isEqualTo("SWD-2026-COUR-01");
        assertThat(item.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("ListCourierAssignments: cross-courier query attempt is rejected with PERMISSION_DENIED")
    void testListCourierAssignments_CrossCourierDenied() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), "courier-sunil", List.of("COURIER"));

        assertThatThrownBy(() -> stub.listCourierAssignments(
                ListCourierAssignmentsRequest.newBuilder().setCourierSub("courier-other").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    // =========================================================================
    // Issue #112: Authorized Delivery History & Latest Status Read Tests
    // =========================================================================

    @Test
    @DisplayName("GetDeliveryHistory: customer retrieves history and latest status for owned parcel")
    void testGetDeliveryHistory_Success_Customer() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(parcelId, "SWD-2026-HIST-01", customerSub, null, null, "v1", 100000L, "LKR", new BigDecimal("1.0"));
        parcel.setStatus(ParcelStatus.IN_TRANSIT);

        StatusHistory h1 = new StatusHistory(UUID.randomUUID(), parcelId, null, ParcelStatus.CREATED, "BOOKING", "Note", null, customerSub, 1L, Instant.now().minusSeconds(3600));
        StatusHistory h2 = new StatusHistory(UUID.randomUUID(), parcelId, ParcelStatus.CREATED, ParcelStatus.IN_TRANSIT, "DISPATCH", "Note", null, "courier-sub", 2L, Instant.now());

        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(historyRepository.findByParcelIdOrderByVersionAsc(eq(parcelId), any()))
                .thenReturn(new PageImpl<>(List.of(h1, h2), PageRequest.of(0, 20), 2));

        GetDeliveryHistoryResponse response = stub.getDeliveryHistory(
                GetDeliveryHistoryRequest.newBuilder().setParcelId(parcelId.toString()).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(response.getTrackingNumber()).isEqualTo("SWD-2026-HIST-01");
        assertThat(response.getCurrentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(response.getItemsCount()).isEqualTo(2);
        assertThat(response.getItems(0).getNewStatus()).isEqualTo("CREATED");
        assertThat(response.getItems(1).getNewStatus()).isEqualTo("IN_TRANSIT");
    }

    @Test
    @DisplayName("GetDeliveryHistory: customer denied access to another customer's parcel history")
    void testGetDeliveryHistory_CrossCustomerDenied() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID parcelId = UUID.randomUUID();
        Parcel otherParcel = new Parcel(parcelId, "SWD-2026-OTHER-01", "other-customer", null, null, "v1", 100000L, "LKR", new BigDecimal("1.0"));

        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(otherParcel));

        assertThatThrownBy(() -> stub.getDeliveryHistory(
                GetDeliveryHistoryRequest.newBuilder().setParcelId(parcelId.toString()).build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("GetDeliveryHistory: returns NOT_FOUND if parcel does not exist")
    void testGetDeliveryHistory_NotFound() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID missingId = UUID.randomUUID();
        when(parcelRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stub.getDeliveryHistory(
                GetDeliveryHistoryRequest.newBuilder().setParcelId(missingId.toString()).build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    @DisplayName("GetDeliveryHistory: batch summaries return only authorized parcels without leaking unauthorized")
    void testGetDeliveryHistory_BatchSummaries_FiltersUnauthorized() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, List.of("CUSTOMER"));

        UUID ownedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        Parcel ownedParcel = new Parcel(ownedId, "SWD-2026-OWN-01", customerSub, null, null, "v1", 100000L, "LKR", new BigDecimal("1.0"));
        Parcel otherParcel = new Parcel(otherId, "SWD-2026-OTH-01", "other-cust", null, null, "v1", 100000L, "LKR", new BigDecimal("1.0"));

        when(parcelRepository.findById(ownedId)).thenReturn(Optional.of(ownedParcel));
        when(parcelRepository.findById(otherId)).thenReturn(Optional.of(otherParcel));
        when(historyRepository.findByParcelIdOrderByResultingVersionAsc(ownedId)).thenReturn(Collections.emptyList());

        GetDeliveryHistoryResponse response = stub.getDeliveryHistory(
                GetDeliveryHistoryRequest.newBuilder()
                        .addParcelIds(ownedId.toString())
                        .addParcelIds(otherId.toString())
                        .build()
        );

        assertThat(response.getSummariesCount()).isEqualTo(1);
        assertThat(response.getSummaries(0).getParcelId()).isEqualTo(ownedId.toString());
    }
}
