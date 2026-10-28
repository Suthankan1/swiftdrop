package com.swiftdrop.graphql.resolver;

import com.google.protobuf.Timestamp;
import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.client.TestClients;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatcherDashboardResolverTest {

    private static final String DISPATCHER_SUB = "disp-user-123";
    private static final String ADMIN_SUB = "admin-user-456";
    private static final String DEFAULT_ZONE = "CMB-01";
    private static final String COURIER_1_SUB = "courier-sub-001";
    private static final String COURIER_2_SUB = "courier-sub-002";
    private static final UUID PARCEL_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARCEL_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private String accountsServerName;
    private String deliveryServerName;
    private Server accountsServer;
    private Server deliveryServer;

    private AuthenticatedReadClients accountsClient;
    private AuthenticatedReadClients deliveryClient;
    private DispatcherDashboardResolver resolver;

    // Accounts mock state
    private final AtomicReference<AccountEligibilityResponse> accountsEligibilityResponse = new AtomicReference<>();
    private final AtomicReference<ScopedProfileSummaryResponse> accountsProfileResponse = new AtomicReference<>();
    private final AtomicReference<AvailableCouriersSummaryResponse> accountsCouriersResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();
    private final AtomicReference<AccountEligibilityRequest> lastEligibilityRequest = new AtomicReference<>();
    private final AtomicReference<ScopedProfileSummaryRequest> lastProfileRequest = new AtomicReference<>();
    private final AtomicReference<AvailableCouriersSummaryRequest> lastCouriersRequest = new AtomicReference<>();

    // Delivery mock state
    private final AtomicReference<ListUnassignedParcelsResponse> deliveryQueueResponse = new AtomicReference<>();
    private final AtomicReference<CourierWorkloadSummaryResponse> deliveryWorkloadResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> deliveryQueueError = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> deliveryWorkloadError = new AtomicReference<>();
    private final AtomicReference<ListUnassignedParcelsRequest> lastQueueRequest = new AtomicReference<>();
    private final AtomicReference<CourierWorkloadSummaryRequest> lastWorkloadRequest = new AtomicReference<>();
    private final AtomicInteger workloadCallCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        accountsEligibilityResponse.set(null);
        accountsProfileResponse.set(null);
        accountsCouriersResponse.set(null);
        accountsError.set(null);
        lastEligibilityRequest.set(null);
        lastProfileRequest.set(null);
        lastCouriersRequest.set(null);

        deliveryQueueResponse.set(null);
        deliveryWorkloadResponse.set(null);
        deliveryQueueError.set(null);
        deliveryWorkloadError.set(null);
        lastQueueRequest.set(null);
        lastWorkloadRequest.set(null);
        workloadCallCount.set(0);

        accountsServerName = InProcessServerBuilder.generateName();
        deliveryServerName = InProcessServerBuilder.generateName();

        accountsServer = InProcessServerBuilder.forName(accountsServerName).directExecutor()
                .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                    @Override
                    public void checkAccountEligibility(AccountEligibilityRequest req, StreamObserver<AccountEligibilityResponse> out) {
                        lastEligibilityRequest.set(req);
                        if (accountsError.get() != null) {
                            out.onError(accountsError.get());
                        } else if (accountsEligibilityResponse.get() != null) {
                            out.onNext(accountsEligibilityResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                        }
                    }

                    @Override
                    public void getScopedProfileSummary(ScopedProfileSummaryRequest req, StreamObserver<ScopedProfileSummaryResponse> out) {
                        lastProfileRequest.set(req);
                        if (accountsError.get() != null) {
                            out.onError(accountsError.get());
                        } else if (accountsProfileResponse.get() != null) {
                            out.onNext(accountsProfileResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                        }
                    }

                    @Override
                    public void getAvailableCouriersSummary(AvailableCouriersSummaryRequest req, StreamObserver<AvailableCouriersSummaryResponse> out) {
                        lastCouriersRequest.set(req);
                        if (accountsError.get() != null) {
                            out.onError(accountsError.get());
                        } else if (accountsCouriersResponse.get() != null) {
                            out.onNext(accountsCouriersResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                        }
                    }
                }).build().start();

        deliveryServer = InProcessServerBuilder.forName(deliveryServerName).directExecutor()
                .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                    @Override
                    public void listUnassignedParcels(ListUnassignedParcelsRequest req, StreamObserver<ListUnassignedParcelsResponse> out) {
                        lastQueueRequest.set(req);
                        if (deliveryQueueError.get() != null) {
                            out.onError(deliveryQueueError.get());
                        } else if (deliveryQueueResponse.get() != null) {
                            out.onNext(deliveryQueueResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Delivery queue not configured").asRuntimeException());
                        }
                    }

                    @Override
                    public void getCourierWorkloadSummary(CourierWorkloadSummaryRequest req, StreamObserver<CourierWorkloadSummaryResponse> out) {
                        workloadCallCount.incrementAndGet();
                        lastWorkloadRequest.set(req);
                        if (deliveryWorkloadError.get() != null) {
                            out.onError(deliveryWorkloadError.get());
                        } else if (deliveryWorkloadResponse.get() != null) {
                            out.onNext(deliveryWorkloadResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Delivery workload not configured").asRuntimeException());
                        }
                    }
                }).build().start();

        ManagedChannel accountsChannel = InProcessChannelBuilder.forName(accountsServerName).directExecutor().build();
        ManagedChannel deliveryChannel = InProcessChannelBuilder.forName(deliveryServerName).directExecutor().build();

        accountsClient = TestClients.inProcess(accountsChannel, () -> "test-service-token");
        deliveryClient = TestClients.inProcess(deliveryChannel, () -> "test-service-token");

        resolver = new DispatcherDashboardResolver(accountsClient, deliveryClient, 5);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (accountsClient != null) accountsClient.close();
        if (deliveryClient != null) deliveryClient.close();
        if (accountsServer != null) {
            accountsServer.shutdownNow();
            accountsServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (deliveryServer != null) {
            deliveryServer.shutdownNow();
            deliveryServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private DataFetchingEnvironment createEnvironment(JwtAuthenticationToken actor, Map<String, Object> pagination) {
        GraphQLContext context = GraphQLContext.newContext()
                .of("actor", actor, "correlationId", "test-corr-id")
                .build();
        DataFetchingEnvironmentImpl.Builder builder = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .graphQLContext(context);
        if (pagination != null) {
            builder.arguments(Map.of("pagination", pagination));
        }
        return builder.build();
    }

    private JwtAuthenticationToken createActor(String sub, String role) {
        Jwt jwt = Jwt.withTokenValue("mock.jwt.token")
                .header("alg", "RS256")
                .subject(sub)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), sub);
    }

    private void setupValidDispatcherDefaults() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(DISPATCHER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .setRejectionReason("ELIGIBLE")
                .build());

        accountsProfileResponse.set(ScopedProfileSummaryResponse.newBuilder()
                .setSubject(DISPATCHER_SUB)
                .setFullName("David Dispatcher")
                .setStaffId("STF-DISP-001")
                .setRole("DISPATCHER")
                .setAssignedZone(DEFAULT_ZONE)
                .setStatus("ACTIVE")
                .build());

        accountsCouriersResponse.set(AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(50)
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setFullName("Alice Courier")
                        .setStaffId("STF-CR-001")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .build());

        deliveryWorkloadResponse.set(CourierWorkloadSummaryResponse.newBuilder()
                .addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setActiveAssignmentsCount(2)
                        .build())
                .build());

        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-20261028-0001")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(3.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("MEDIUM")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());
    }

    // =========================================================================
    // 1. BASIC CONTRACT & REGISTRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Root resolver field name is dispatcherDashboard")
    void testFieldName() {
        assertThat(resolver.field()).isEqualTo("dispatcherDashboard");
    }

    @Test
    @DisplayName("Rejects non-positive configured max capacity")
    void testInvalidConfiguredCapacity() {
        assertThatThrownBy(() -> new DispatcherDashboardResolver(accountsClient, deliveryClient, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DispatcherDashboardResolver(accountsClient, deliveryClient, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 2. AUTHORIZATION TESTS
    // =========================================================================

    @Test
    @DisplayName("DISPATCHER role is authorized and resolves dashboard")
    void testDispatcher_Authorized() throws Exception {
        setupValidDispatcherDefaults();
        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        Object result = resolver.resolve(env);

        assertThat(result).isInstanceOf(DispatcherDashboard.class);
        DispatcherDashboard dashboard = (DispatcherDashboard) result;
        assertThat(dashboard.unassignedParcels()).hasSize(1);
        assertThat(dashboard.availableCouriers()).hasSize(1);
        assertThat(dashboard.pageInfo().totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADMIN role is authorized under approved scope")
    void testAdmin_Authorized() throws Exception {
        setupValidDispatcherDefaults();
        accountsProfileResponse.set(ScopedProfileSummaryResponse.newBuilder()
                .setSubject(ADMIN_SUB)
                .setRole("ADMIN")
                .setAssignedZone("") // Platform-wide
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(ADMIN_SUB, "ADMIN"), null);

        Object result = resolver.resolve(env);

        assertThat(result).isInstanceOf(DispatcherDashboard.class);
        assertThat(lastQueueRequest.get().getZone()).isEmpty();
        assertThat(lastCouriersRequest.get().getZoneFilter()).isEmpty();
    }

    @Test
    @DisplayName("CUSTOMER role is rejected with AccessDeniedException")
    void testCustomer_Rejected() {
        setupValidDispatcherDefaults();
        DataFetchingEnvironment env = createEnvironment(createActor("cust-1", "CUSTOMER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(lastEligibilityRequest.get()).isNull();
        assertThat(lastQueueRequest.get()).isNull();
    }

    @Test
    @DisplayName("MERCHANT role is rejected with AccessDeniedException")
    void testMerchant_Rejected() {
        setupValidDispatcherDefaults();
        DataFetchingEnvironment env = createEnvironment(createActor("merch-1", "MERCHANT"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(lastEligibilityRequest.get()).isNull();
    }

    @Test
    @DisplayName("COURIER role is rejected with AccessDeniedException")
    void testCourier_Rejected() {
        setupValidDispatcherDefaults();
        DataFetchingEnvironment env = createEnvironment(createActor("courier-1", "COURIER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(lastEligibilityRequest.get()).isNull();
    }

    @Test
    @DisplayName("Missing JWT actor rejected with UNAUTHENTICATED")
    void testMissingActor_Rejected() {
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .graphQLContext(GraphQLContext.newContext().build())
                .build();

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("Blank actor subject rejected with UNAUTHENTICATED")
    void testBlankSubject_Rejected() {
        DataFetchingEnvironment env = createEnvironment(createActor("   ", "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("Inactive dispatcher in Accounts rejected with PERMISSION_DENIED")
    void testInactiveDispatcher_Rejected() {
        setupValidDispatcherDefaults();
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(DISPATCHER_SUB)
                .setIsEligible(false)
                .setIsActive(false)
                .setStatus("SUSPENDED")
                .setRejectionReason("STAFF_SUSPENDED")
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("Accounts subject mismatch rejected with PERMISSION_DENIED")
    void testAccountsSubjectMismatch_Rejected() {
        setupValidDispatcherDefaults();
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject("forged-subject-999")
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("Cross-scope queue access: Parcel outside dispatcher assigned zone is rejected")
    void testCrossScopeQueue_Rejected() {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-20261028-0001")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(3.5)
                        .setPickupZone("KANDY-01") // Outside CMB-01
                        .setDropoffZone("KANDY-02")
                        .setSizeCategory("MEDIUM")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("Cross-scope courier access: Courier outside dispatcher assigned zone is rejected")
    void testCrossScopeCourier_Rejected() {
        setupValidDispatcherDefaults();
        accountsCouriersResponse.set(AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(50)
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setFullName("Alice Courier")
                        .setAssignedZone("GALLE-01") // Outside CMB-01
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    // =========================================================================
    // 3. UNASSIGNED QUEUE TESTS
    // =========================================================================

    @Test
    @DisplayName("Empty unassigned queue resolves correctly")
    void testEmptyQueue() throws Exception {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(0)
                .setPage(0)
                .setPageSize(20)
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);
        DispatcherDashboard dashboard = (DispatcherDashboard) resolver.resolve(env);

        assertThat(dashboard.unassignedParcels()).isEmpty();
        assertThat(dashboard.pageInfo().totalElements()).isEqualTo(0);
        assertThat(dashboard.pageInfo().totalPages()).isEqualTo(0);
        assertThat(dashboard.pageInfo().hasNext()).isFalse();
    }

    @Test
    @DisplayName("Multiple unassigned parcels resolve in stable order")
    void testMultipleUnassignedParcels() throws Exception {
        setupValidDispatcherDefaults();
        Instant now = Instant.now();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(2)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-001")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(1.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("SMALL")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                        .build())
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_2_ID.toString())
                        .setTrackingNumber("SWD-002")
                        .setStatus("CREATED")
                        .setVersion(2L)
                        .setWeightKg(6.0)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("LARGE")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(now.plusSeconds(10).getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);
        DispatcherDashboard dashboard = (DispatcherDashboard) resolver.resolve(env);

        assertThat(dashboard.unassignedParcels()).hasSize(2);
        assertThat(dashboard.unassignedParcels().get(0).parcelId()).isEqualTo(PARCEL_1_ID.toString());
        assertThat(dashboard.unassignedParcels().get(0).sizeCategory()).isEqualTo("SMALL");
        assertThat(dashboard.unassignedParcels().get(1).parcelId()).isEqualTo(PARCEL_2_ID.toString());
        assertThat(dashboard.unassignedParcels().get(1).sizeCategory()).isEqualTo("LARGE");
        assertThat(dashboard.pageInfo().totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("Custom pagination and hasNext calculation")
    void testCustomPagination() throws Exception {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(45)
                .setPage(1)
                .setPageSize(20)
                .build());

        Map<String, Object> pagination = Map.of("page", 1, "size", 20);
        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), pagination);
        DispatcherDashboard dashboard = (DispatcherDashboard) resolver.resolve(env);

        assertThat(dashboard.pageInfo().currentPage()).isEqualTo(1);
        assertThat(dashboard.pageInfo().pageSize()).isEqualTo(20);
        assertThat(dashboard.pageInfo().totalElements()).isEqualTo(45);
        assertThat(dashboard.pageInfo().totalPages()).isEqualTo(3);
        assertThat(dashboard.pageInfo().hasNext()).isTrue();
    }

    @Test
    @DisplayName("Page size 50 is allowed")
    void testPageSize50_Allowed() throws Exception {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(0)
                .setPage(0)
                .setPageSize(50)
                .build());

        Map<String, Object> pagination = Map.of("page", 0, "size", 50);
        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), pagination);
        DispatcherDashboard dashboard = (DispatcherDashboard) resolver.resolve(env);

        assertThat(dashboard.pageInfo().pageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("Page size 51 is rejected by PaginationPolicy")
    void testPageSize51_Rejected() {
        setupValidDispatcherDefaults();
        Map<String, Object> pagination = Map.of("page", 0, "size", 51);
        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), pagination);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Negative page is rejected by PaginationPolicy")
    void testNegativePage_Rejected() {
        setupValidDispatcherDefaults();
        Map<String, Object> pagination = Map.of("page", -1, "size", 20);
        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), pagination);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Duplicate parcel IDs in queue response rejected with INTERNAL")
    void testDuplicateParcelId_Rejected() {
        setupValidDispatcherDefaults();
        Instant now = Instant.now();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(2)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-001")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(1.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("SMALL")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                        .build())
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString()) // Duplicate
                        .setTrackingNumber("SWD-002")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(2.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("MEDIUM")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Invalid parcel version (< 0) rejected with INTERNAL")
    void testNegativeParcelVersion_Rejected() {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-001")
                        .setStatus("CREATED")
                        .setVersion(-1L)
                        .setWeightKg(1.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("SMALL")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Non-CREATED parcel status rejected with INTERNAL")
    void testNonCreatedParcelStatus_Rejected() {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-001")
                        .setStatus("PICKED_UP")
                        .setVersion(1L)
                        .setWeightKg(1.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("SMALL")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Missing authoritative size category handled safely (fails closed)")
    void testMissingSizeCategory_FailsClosed() {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-001")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(1.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("") // Missing / blank
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Missing zone values handled safely (fails closed)")
    void testMissingZoneValues_FailsClosed() {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-001")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(1.5)
                        .setPickupZone("") // Blank pickup zone
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("SMALL")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Invalid parcel weight rejected with INTERNAL")
    void testInvalidWeight_Rejected() {
        setupValidDispatcherDefaults();
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_1_ID.toString())
                        .setTrackingNumber("SWD-001")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(-0.5) // Negative weight
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("SMALL")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    // =========================================================================
    // 4. COURIER WORKLOAD TESTS
    // =========================================================================

    @Test
    @DisplayName("Multiple couriers resolve with accurate workloads and single batch call")
    void testMultipleCouriers_BatchWorkload() throws Exception {
        setupValidDispatcherDefaults();
        accountsCouriersResponse.set(AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(2)
                .setPage(0)
                .setPageSize(50)
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setFullName("Alice Courier")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_2_SUB)
                        .setFullName("Bob Courier")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .build());

        deliveryWorkloadResponse.set(CourierWorkloadSummaryResponse.newBuilder()
                .addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setActiveAssignmentsCount(0) // Zero workload
                        .build())
                .addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(COURIER_2_SUB)
                        .setActiveAssignmentsCount(4)
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);
        DispatcherDashboard dashboard = (DispatcherDashboard) resolver.resolve(env);

        assertThat(dashboard.availableCouriers()).hasSize(2);
        assertThat(dashboard.availableCouriers().get(0).courierSub()).isEqualTo(COURIER_1_SUB);
        assertThat(dashboard.availableCouriers().get(0).activeAssignmentsCount()).isEqualTo(0);
        assertThat(dashboard.availableCouriers().get(0).maxCapacity()).isEqualTo(5);
        assertThat(dashboard.availableCouriers().get(1).courierSub()).isEqualTo(COURIER_2_SUB);
        assertThat(dashboard.availableCouriers().get(1).activeAssignmentsCount()).isEqualTo(4);

        // Exactly ONE batch Delivery workload call occurred
        assertThat(workloadCallCount.get()).isEqualTo(1);
        assertThat(lastWorkloadRequest.get().getCourierSubsList()).containsExactly(COURIER_1_SUB, COURIER_2_SUB);
    }

    @Test
    @DisplayName("Inactive and ineligible couriers are excluded")
    void testInactiveAndIneligibleCouriers_Excluded() throws Exception {
        setupValidDispatcherDefaults();
        accountsCouriersResponse.set(AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(3)
                .setPage(0)
                .setPageSize(50)
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setFullName("Alice Active")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub("courier-inactive")
                        .setFullName("Inactive Courier")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(false)
                        .setIsEligible(true)
                        .build())
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub("courier-ineligible")
                        .setFullName("Ineligible Courier")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(false)
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);
        DispatcherDashboard dashboard = (DispatcherDashboard) resolver.resolve(env);

        assertThat(dashboard.availableCouriers()).hasSize(1);
        assertThat(dashboard.availableCouriers().get(0).courierSub()).isEqualTo(COURIER_1_SUB);
    }

    @Test
    @DisplayName("Empty courier list does not call Delivery workload")
    void testEmptyCouriers_NoWorkloadCall() throws Exception {
        setupValidDispatcherDefaults();
        accountsCouriersResponse.set(AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(0)
                .setPage(0)
                .setPageSize(50)
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);
        DispatcherDashboard dashboard = (DispatcherDashboard) resolver.resolve(env);

        assertThat(dashboard.availableCouriers()).isEmpty();
        assertThat(workloadCallCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Duplicate courier ID in Accounts response rejected with INTERNAL")
    void testDuplicateCourierId_Rejected() {
        setupValidDispatcherDefaults();
        accountsCouriersResponse.set(AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(2)
                .setPage(0)
                .setPageSize(50)
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setFullName("Alice Courier")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_1_SUB) // Duplicate
                        .setFullName("Alice Imposter")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Unknown courier identity in Delivery workload response rejected with INTERNAL")
    void testUnknownCourierInWorkload_Rejected() {
        setupValidDispatcherDefaults();
        deliveryWorkloadResponse.set(CourierWorkloadSummaryResponse.newBuilder()
                .addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub("unknown-courier-sub")
                        .setActiveAssignmentsCount(1)
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Missing requested courier in Delivery workload response rejected with INTERNAL")
    void testMissingRequestedCourierInWorkload_Rejected() {
        setupValidDispatcherDefaults();
        deliveryWorkloadResponse.set(CourierWorkloadSummaryResponse.newBuilder().build()); // Empty workload response

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Negative workload count in Delivery response rejected with INTERNAL")
    void testNegativeWorkloadCount_Rejected() {
        setupValidDispatcherDefaults();
        deliveryWorkloadResponse.set(CourierWorkloadSummaryResponse.newBuilder()
                .addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setActiveAssignmentsCount(-1)
                        .build())
                .build());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Custom configured maxCapacity maps correctly")
    void testCustomConfiguredCapacity() throws Exception {
        setupValidDispatcherDefaults();
        DispatcherDashboardResolver customResolver = new DispatcherDashboardResolver(accountsClient, deliveryClient, 8);
        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        DispatcherDashboard dashboard = (DispatcherDashboard) customResolver.resolve(env);

        assertThat(dashboard.availableCouriers().get(0).maxCapacity()).isEqualTo(8);
    }

    // =========================================================================
    // 5. PROVIDER FAILURE & DEADLINE TESTS
    // =========================================================================

    @Test
    @DisplayName("Accounts service UNAVAILABLE fails closed without fake dashboard")
    void testAccountsUnavailable_FailsClosed() {
        setupValidDispatcherDefaults();
        accountsError.set(Status.UNAVAILABLE.withDescription("Accounts is down").asRuntimeException());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName("Delivery queue UNAVAILABLE fails closed without fake dashboard")
    void testDeliveryQueueUnavailable_FailsClosed() {
        setupValidDispatcherDefaults();
        deliveryQueueError.set(Status.UNAVAILABLE.withDescription("Delivery is down").asRuntimeException());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName("Delivery workload DEADLINE_EXCEEDED maps safely without fake data")
    void testDeliveryWorkloadTimeout_FailsClosed() {
        setupValidDispatcherDefaults();
        deliveryWorkloadError.set(Status.DEADLINE_EXCEEDED.withDescription("Workload timed out").asRuntimeException());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    }

    @Test
    @DisplayName("Accounts provider DEADLINE_EXCEEDED maps safely")
    void testAccountsTimeout_FailsClosed() {
        setupValidDispatcherDefaults();
        accountsError.set(Status.DEADLINE_EXCEEDED.withDescription("Accounts timed out").asRuntimeException());

        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    }

    // =========================================================================
    // 6. CONCURRENT ASSIGNMENT REFRESH TEST (PHASE 16)
    // =========================================================================

    @Test
    @DisplayName("Concurrent assignment refresh: assigned parcel is removed and courier workload updates on refresh")
    void testConcurrentAssignmentRefresh() throws Exception {
        setupValidDispatcherDefaults();
        DataFetchingEnvironment env = createEnvironment(createActor(DISPATCHER_SUB, "DISPATCHER"), null);

        // 1. Initial dashboard read: Parcel 1 is unassigned, Courier 1 has 2 active assignments
        DispatcherDashboard initial = (DispatcherDashboard) resolver.resolve(env);
        assertThat(initial.unassignedParcels()).hasSize(1);
        assertThat(initial.unassignedParcels().get(0).parcelId()).isEqualTo(PARCEL_1_ID.toString());
        assertThat(initial.availableCouriers().get(0).activeAssignmentsCount()).isEqualTo(2);

        // 2. Simulate concurrent assignment: Parcel 1 is assigned to Courier 1 in Delivery
        // Now unassigned queue is empty, and Courier 1 workload is incremented to 3
        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(0)
                .setPage(0)
                .setPageSize(20)
                .build());

        deliveryWorkloadResponse.set(CourierWorkloadSummaryResponse.newBuilder()
                .addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(COURIER_1_SUB)
                        .setActiveAssignmentsCount(3)
                        .build())
                .build());

        // 3. Dispatcher refreshes dashboard
        DispatcherDashboard refreshed = (DispatcherDashboard) resolver.resolve(env);

        assertThat(refreshed.unassignedParcels()).isEmpty();
        assertThat(refreshed.pageInfo().totalElements()).isEqualTo(0);
        assertThat(refreshed.availableCouriers().get(0).activeAssignmentsCount()).isEqualTo(3);
    }
}
