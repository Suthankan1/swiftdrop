package com.swiftdrop.graphql.resolver.admin;

import com.google.protobuf.Timestamp;
import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.PendingMerchantsSummaryRequest;
import com.swiftdrop.accounts.v1.PendingMerchantsSummaryResponse;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.delivery.v1.GetDeliveryStatsRequest;
import com.swiftdrop.delivery.v1.GetDeliveryStatsResponse;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.client.TestClients;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminDashboardResolverTest {

    private static final String ADMIN_SUB = "admin-sub-12345";
    private static final String CORRELATION_ID = "corr-admin-test-101";
    private static final ZoneId COLOMBO_ZONE = ZoneId.of("Asia/Colombo");

    private String accountsServerName;
    private String deliveryServerName;
    private Server accountsServer;
    private Server deliveryServer;

    private AuthenticatedReadClients accountsClient;
    private AuthenticatedReadClients deliveryClient;
    private AdminDashboardResolver resolver;

    private final AtomicReference<PendingMerchantsSummaryResponse> accountsResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();
    private final AtomicReference<PendingMerchantsSummaryRequest> lastAccountsRequest = new AtomicReference<>();

    private final AtomicReference<GetDeliveryStatsResponse> deliveryResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> deliveryError = new AtomicReference<>();
    private final AtomicReference<GetDeliveryStatsRequest> lastDeliveryRequest = new AtomicReference<>();

    private Clock fixedClock;

    @BeforeEach
    void setUp() throws Exception {
        accountsResponse.set(null);
        accountsError.set(null);
        lastAccountsRequest.set(null);

        deliveryResponse.set(null);
        deliveryError.set(null);
        lastDeliveryRequest.set(null);

        // Fixed clock at 2026-10-30T10:00:00Z (which is 15:30 in Colombo)
        fixedClock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), COLOMBO_ZONE);

        accountsServerName = InProcessServerBuilder.generateName();
        deliveryServerName = InProcessServerBuilder.generateName();

        accountsServer = InProcessServerBuilder.forName(accountsServerName).directExecutor()
                .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                    @Override
                    public void getPendingMerchantsSummary(PendingMerchantsSummaryRequest req, StreamObserver<PendingMerchantsSummaryResponse> out) {
                        lastAccountsRequest.set(req);
                        if (accountsError.get() != null) {
                            out.onError(accountsError.get());
                        } else if (accountsResponse.get() != null) {
                            out.onNext(accountsResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                        }
                    }
                }).build().start();

        deliveryServer = InProcessServerBuilder.forName(deliveryServerName).directExecutor()
                .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                    @Override
                    public void getDeliveryStatsInternal(GetDeliveryStatsRequest req, StreamObserver<GetDeliveryStatsResponse> out) {
                        lastDeliveryRequest.set(req);
                        if (deliveryError.get() != null) {
                            out.onError(deliveryError.get());
                        } else if (deliveryResponse.get() != null) {
                            out.onNext(deliveryResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Delivery not configured").asRuntimeException());
                        }
                    }
                }).build().start();

        ManagedChannel accountsChannel = InProcessChannelBuilder.forName(accountsServerName).directExecutor().build();
        ManagedChannel deliveryChannel = InProcessChannelBuilder.forName(deliveryServerName).directExecutor().build();

        accountsClient = TestClients.inProcess(accountsChannel, () -> "test-service-token");
        deliveryClient = TestClients.inProcess(deliveryChannel, () -> "test-service-token");

        resolver = new AdminDashboardResolver(accountsClient, deliveryClient, fixedClock);
    }

    @AfterEach
    void tearDown() {
        if (accountsServer != null) accountsServer.shutdownNow();
        if (deliveryServer != null) deliveryServer.shutdownNow();
        if (accountsClient != null) accountsClient.close();
        if (deliveryClient != null) deliveryClient.close();
    }

    private DataFetchingEnvironment createEnv(JwtAuthenticationToken token) {
        GraphQLContext context = GraphQLContext.newContext()
                .put("actor", token)
                .put("correlationId", CORRELATION_ID)
                .build();

        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .graphQLContext(context)
                .build();
    }

    private JwtAuthenticationToken createActor(String sub, String role) {
        Jwt jwt = Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .subject(sub)
                .claim("roles", List.of(role))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private com.swiftdrop.accounts.v1.PendingMerchantItem createPendingMerchant(String id, String name, String regNo, String status) {
        return com.swiftdrop.accounts.v1.PendingMerchantItem.newBuilder()
                .setMerchantId(id)
                .setBusinessName(name)
                .setRegistrationNumber(regNo)
                .setStatus(status)
                .setSubmittedAt(Timestamp.newBuilder().setSeconds(1761800000L).setNanos(0).build())
                .build();
    }

    @Test
    @DisplayName("field() returns adminDashboard")
    void testFieldName() {
        assertThat(resolver.field()).isEqualTo("adminDashboard");
    }

    // =========================================================================
    // A. Role Security Tests
    // =========================================================================

    @Test
    @DisplayName("Unauthenticated caller without actor is rejected")
    void testUnauthenticated_NoActor() {
        GraphQLContext context = GraphQLContext.newContext().build();
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .graphQLContext(context)
                .build();

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Caller with blank subject is rejected")
    void testBlankSubject() {
        JwtAuthenticationToken actor = createActor("   ", "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("Missing authenticated subject");
    }

    @Test
    @DisplayName("Non-admin roles are rejected with AccessDeniedException (CUSTOMER, MERCHANT, COURIER, DISPATCHER)")
    void testNonAdminRolesDenied() {
        for (String role : List.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER")) {
            JwtAuthenticationToken actor = createActor("user-1", role);
            assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Caller does not have ROLE_ADMIN");
        }
    }

    // =========================================================================
    // B. Merchant Aggregation Tests
    // =========================================================================

    @Test
    @DisplayName("Happy path: Admin receives authoritative merchant preview and delivery stats")
    void testHappyPath() throws Exception {
        String mId1 = UUID.randomUUID().toString();
        String mId2 = UUID.randomUUID().toString();

        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(42)
                .setPage(0)
                .setPageSize(20)
                .addItems(createPendingMerchant(mId1, "Acme Corp", "REG-001", "PENDING"))
                .addItems(createPendingMerchant(mId2, "Beta Logistics", "REG-002", "PENDING"))
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder()
                .setTotalCount(150)
                .setDeliveredCount(80)
                .setFailedCount(20)
                .setInTransitCount(35)
                .setCreatedCount(15)
                .setAverageDeliveryHours(2.5)
                .build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        Object result = resolver.resolve(createEnv(actor));

        assertThat(result).isInstanceOf(AdminDashboard.class);
        AdminDashboard dashboard = (AdminDashboard) result;

        assertThat(dashboard.pendingMerchantsCount()).isEqualTo(42);
        assertThat(dashboard.recentPendingMerchants()).hasSize(2);
        assertThat(dashboard.recentPendingMerchants().get(0).merchantId()).isEqualTo(mId1);
        assertThat(dashboard.recentPendingMerchants().get(0).businessName()).isEqualTo("Acme Corp");
        assertThat(dashboard.recentPendingMerchants().get(0).registrationNumber()).isEqualTo("REG-001");
        assertThat(dashboard.recentPendingMerchants().get(0).submittedAt()).isNotBlank();

        assertThat(dashboard.totalParcelsCount()).isEqualTo(150);
        assertThat(dashboard.activeParcelsCount()).isEqualTo(35);
        // 80 delivered / (80 delivered + 20 failed) = 80 / 100 = 0.8
        assertThat(dashboard.terminalSuccessRate()).isEqualTo(0.8);

        // Verify request shape sent to Accounts
        assertThat(lastAccountsRequest.get().getStatusFilter()).isEqualTo("PENDING");
        assertThat(lastAccountsRequest.get().getPage()).isEqualTo(0);
        assertThat(lastAccountsRequest.get().getPageSize()).isEqualTo(20);

        // Verify request shape sent to Delivery
        assertThat(lastDeliveryRequest.get().hasStartTime()).isTrue();
        assertThat(lastDeliveryRequest.get().hasEndTime()).isTrue();
    }

    @Test
    @DisplayName("Empty pending merchants returns count 0 and empty list")
    void testEmptyMerchants() throws Exception {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(0)
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder()
                .setTotalCount(0)
                .setDeliveredCount(0)
                .setFailedCount(0)
                .setInTransitCount(0)
                .setCreatedCount(0)
                .build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        AdminDashboard dashboard = (AdminDashboard) resolver.resolve(createEnv(actor));

        assertThat(dashboard.pendingMerchantsCount()).isEqualTo(0);
        assertThat(dashboard.recentPendingMerchants()).isEmpty();
        assertThat(dashboard.totalParcelsCount()).isEqualTo(0);
        assertThat(dashboard.activeParcelsCount()).isEqualTo(0);
        assertThat(dashboard.terminalSuccessRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Negative pending merchant count is rejected")
    void testNegativePendingCount() {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(-1)
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("negative pending count");
    }

    @Test
    @DisplayName("Accounts provider returning non-PENDING status is rejected as protocol violation")
    void testNonPendingMerchantStatusRejected() {
        String mId = UUID.randomUUID().toString();
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(1)
                .addItems(createPendingMerchant(mId, "Approved Store", "REG-X", "APPROVED"))
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("Provider returned non-pending merchant status");
    }

    @Test
    @DisplayName("Malformed merchantId UUID is rejected")
    void testMalformedMerchantUuid() {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(1)
                .addItems(createPendingMerchant("not-a-valid-uuid", "Invalid Merchant", "REG-X", "PENDING"))
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("malformed merchantId UUID");
    }

    @Test
    @DisplayName("Duplicate merchant IDs in preview list are rejected")
    void testDuplicateMerchantIdsRejected() {
        String duplicateId = UUID.randomUUID().toString();
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(2)
                .addItems(createPendingMerchant(duplicateId, "Shop 1", "REG-1", "PENDING"))
                .addItems(createPendingMerchant(duplicateId, "Shop 2", "REG-2", "PENDING"))
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("duplicate merchant ID");
    }

    @Test
    @DisplayName("Oversized items list (> 50) is rejected")
    void testOversizedMerchantList() {
        PendingMerchantsSummaryResponse.Builder builder = PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(60);
        for (int i = 0; i < 55; i++) {
            builder.addItems(createPendingMerchant(UUID.randomUUID().toString(), "Shop " + i, "REG-" + i, "PENDING"));
        }
        accountsResponse.set(builder.build());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("oversized items list");
    }

    // =========================================================================
    // C. Delivery Operational Statistics Tests
    // =========================================================================

    @Test
    @DisplayName("Terminal success rate evaluates to 0.0 when 0 terminal deliveries exist")
    void testZeroTerminalSuccessRate() throws Exception {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(5)
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder()
                .setTotalCount(10)
                .setDeliveredCount(0)
                .setFailedCount(0)
                .setInTransitCount(8)
                .setCreatedCount(2)
                .build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        AdminDashboard dashboard = (AdminDashboard) resolver.resolve(createEnv(actor));

        assertThat(dashboard.terminalSuccessRate()).isEqualTo(0.0);
        assertThat(dashboard.activeParcelsCount()).isEqualTo(8);
        assertThat(dashboard.totalParcelsCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("Terminal success rate evaluates to 1.0 when all terminal parcels are delivered")
    void testAllDeliveredSuccessRate() throws Exception {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder().build());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder()
                .setTotalCount(15)
                .setDeliveredCount(12)
                .setFailedCount(0)
                .build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        AdminDashboard dashboard = (AdminDashboard) resolver.resolve(createEnv(actor));

        assertThat(dashboard.terminalSuccessRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Negative count in delivery response is rejected")
    void testNegativeDeliveryCountRejected() {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder().build());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder()
                .setTotalCount(-5)
                .build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("negative counts");
    }

    @Test
    @DisplayName("totalParcelsCount exceeding signed 32-bit int is rejected")
    void testTotalParcelsIntOverflow() {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder().build());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder()
                .setTotalCount((long) Integer.MAX_VALUE + 10L)
                .build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("Total parcels count overflows GraphQL Int");
    }

    // =========================================================================
    // D. Multi-Provider Failure Tests
    // =========================================================================

    @Test
    @DisplayName("Accounts provider DEADLINE_EXCEEDED propagates deadline error")
    void testAccountsDeadlineExceeded() {
        accountsError.set(Status.DEADLINE_EXCEEDED.withDescription("Accounts deadline").asRuntimeException());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("Delivery provider DEADLINE_EXCEEDED propagates deadline error")
    void testDeliveryDeadlineExceeded() {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder().build());
        deliveryError.set(Status.DEADLINE_EXCEEDED.withDescription("Delivery deadline").asRuntimeException());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("Accounts provider UNAVAILABLE fails closed")
    void testAccountsUnavailable() {
        accountsError.set(Status.UNAVAILABLE.withDescription("Accounts down").asRuntimeException());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    @DisplayName("Delivery provider UNAVAILABLE fails closed")
    void testDeliveryUnavailable() {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder().build());
        deliveryError.set(Status.UNAVAILABLE.withDescription("Delivery down").asRuntimeException());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    @DisplayName("Accounts provider PERMISSION_DENIED fails closed")
    void testAccountsPermissionDenied() {
        accountsError.set(Status.PERMISSION_DENIED.withDescription("Accounts denied").asRuntimeException());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        JwtAuthenticationToken actor = createActor(ADMIN_SUB, "ADMIN");
        assertThatThrownBy(() -> resolver.resolve(createEnv(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("PERMISSION_DENIED");
    }
}
