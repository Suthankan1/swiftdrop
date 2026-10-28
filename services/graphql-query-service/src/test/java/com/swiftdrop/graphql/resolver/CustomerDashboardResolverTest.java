package com.swiftdrop.graphql.resolver;

import com.google.protobuf.Timestamp;
import com.swiftdrop.delivery.v1.CustomerParcelSummary;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.delivery.v1.ListCustomerParcelsRequest;
import com.swiftdrop.delivery.v1.ListCustomerParcelsResponse;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive unit tests for {@link CustomerDashboardResolver} (Issue #132 / N10-WF03).
 *
 * <p>Uses real in-process gRPC server and AuthenticatedReadClients test seam to verify:
 * <ul>
 *   <li>Positive retrieval: active, terminal, mixed, empty, 20 items, >20 items bounded preview</li>
 *   <li>Customer identity binding strictly to verified JWT subject</li>
 *   <li>Cross-customer isolation and role enforcement (CUSTOMER required, all others rejected)</li>
 *   <li>Defensive local authorization preventing unauthorized provider reads</li>
 *   <li>Provider error and data invariant checks: bounds, overflow, statuses, timestamps, UUIDs</li>
 * </ul>
 */
class CustomerDashboardResolverTest {

    private static final String CUSTOMER_A_SUB = "a1111111-1111-1111-1111-111111111111";
    private static final String CUSTOMER_B_SUB = "b2222222-2222-2222-2222-222222222222";
    private static final String CORRELATION_ID = "corr-customer-test-001";

    private Server deliveryServer;
    private AuthenticatedReadClients deliveryClient;
    private CustomerDashboardResolver resolver;

    private final AtomicReference<ListCustomerParcelsResponse> deliveryResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> deliveryError = new AtomicReference<>();
    private final AtomicReference<ListCustomerParcelsRequest> lastDeliveryRequest = new AtomicReference<>();
    private final AtomicInteger deliveryCallCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        deliveryResponse.set(null);
        deliveryError.set(null);
        lastDeliveryRequest.set(null);
        deliveryCallCount.set(0);

        String deliveryServerName = InProcessServerBuilder.generateName();

        deliveryServer = InProcessServerBuilder.forName(deliveryServerName).directExecutor()
                .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                    @Override
                    public void listCustomerParcels(ListCustomerParcelsRequest req,
                                                    StreamObserver<ListCustomerParcelsResponse> out) {
                        deliveryCallCount.incrementAndGet();
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

        ManagedChannel channel = InProcessChannelBuilder.forName(deliveryServerName).directExecutor().build();
        deliveryClient = TestClients.inProcess(channel, () -> "test-service-token");
        resolver = new CustomerDashboardResolver(deliveryClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (deliveryClient != null) {
            deliveryClient.close();
        }
        if (deliveryServer != null) {
            deliveryServer.shutdownNow();
            deliveryServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ── Helper methods ─────────────────────────────────────────────────────────────

    private DataFetchingEnvironment env(JwtAuthenticationToken actor) {
        GraphQLContext ctx = GraphQLContext.newContext()
                .of("actor", actor, "correlationId", CORRELATION_ID)
                .build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment().graphQLContext(ctx).build();
    }

    private JwtAuthenticationToken actor(String sub, String role) {
        Jwt jwt = Jwt.withTokenValue("mock.jwt.token")
                .header("alg", "RS256")
                .subject(sub)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), sub);
    }

    private Timestamp ts(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds).setNanos(0).build();
    }

    private CustomerParcelSummary summary(String parcelId, String trackingNumber, String status,
                                          long amountMinorUnits, String currency, String city,
                                          long createdSec, long updatedSec) {
        return CustomerParcelSummary.newBuilder()
                .setParcelId(parcelId)
                .setTrackingNumber(trackingNumber)
                .setCurrentStatus(status)
                .setAmountMinorUnits(amountMinorUnits)
                .setCurrency(currency)
                .setRecipientCity(city)
                .setCreatedAt(ts(createdSec))
                .setUpdatedAt(ts(updatedSec))
                .setVersion(1L)
                .build();
    }

    // ── Field & Component registration ─────────────────────────────────────────────

    @Test
    void fieldNameIsCustomerDashboard() {
        assertThat(resolver.field()).isEqualTo("customerDashboard");
    }

    // ── Phase 9.1: Positive cases ──────────────────────────────────────────────────

    @Test
    void authorizedCustomerWithMultipleActiveParcels() throws Exception {
        String p1 = UUID.randomUUID().toString();
        String p2 = UUID.randomUUID().toString();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(2)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(2)
                .setCompletedCount(0)
                .addParcels(summary(p1, "SWD-2026-000001", "CREATED", 1500, "LKR", "Colombo", 1761400000L, 1761400100L))
                .addParcels(summary(p2, "SWD-2026-000002", "IN_TRANSIT", 2500, "LKR", "Kandy", 1761400200L, 1761400300L))
                .build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(dashboard.userId()).isEqualTo(CUSTOMER_A_SUB);
        assertThat(dashboard.activeDeliveriesCount()).isEqualTo(2);
        assertThat(dashboard.completedDeliveriesCount()).isEqualTo(0);
        assertThat(dashboard.recentParcels()).hasSize(2);
        assertThat(dashboard.recentParcels().get(0).parcelId()).isEqualTo(p1);
        assertThat(dashboard.recentParcels().get(0).currentStatus()).isEqualTo("CREATED");
        assertThat(dashboard.recentParcels().get(1).parcelId()).isEqualTo(p2);
        assertThat(dashboard.recentParcels().get(1).currentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(deliveryCallCount.get()).isEqualTo(1);
    }

    @Test
    void authorizedCustomerWithDeliveredParcels() throws Exception {
        String p1 = UUID.randomUUID().toString();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(0)
                .setCompletedCount(1)
                .addParcels(summary(p1, "SWD-2026-000003", "DELIVERED", 1800, "LKR", "Galle", 1761400000L, 1761405000L))
                .build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(dashboard.activeDeliveriesCount()).isEqualTo(0);
        assertThat(dashboard.completedDeliveriesCount()).isEqualTo(1);
        assertThat(dashboard.recentParcels()).hasSize(1);
        assertThat(dashboard.recentParcels().get(0).currentStatus()).isEqualTo("DELIVERED");
    }

    @Test
    void authorizedCustomerWithFailedParcels() throws Exception {
        String p1 = UUID.randomUUID().toString();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(0)
                .setCompletedCount(1)
                .addParcels(summary(p1, "SWD-2026-000004", "FAILED", 1200, "LKR", "Jaffna", 1761400000L, 1761408000L))
                .build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(dashboard.activeDeliveriesCount()).isEqualTo(0);
        assertThat(dashboard.completedDeliveriesCount()).isEqualTo(1);
        assertThat(dashboard.recentParcels().get(0).currentStatus()).isEqualTo("FAILED");
    }

    @Test
    void authorizedCustomerWithMixedActiveAndTerminalStatuses() throws Exception {
        String p1 = UUID.randomUUID().toString();
        String p2 = UUID.randomUUID().toString();
        String p3 = UUID.randomUUID().toString();
        String p4 = UUID.randomUUID().toString();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(4)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(2)
                .setCompletedCount(2)
                .addParcels(summary(p1, "SWD-2026-000010", "PICKED_UP", 2000, "LKR", "Colombo", 1761400000L, 1761400500L))
                .addParcels(summary(p2, "SWD-2026-000011", "OUT_FOR_DELIVERY", 3000, "LKR", "Negombo", 1761400100L, 1761400600L))
                .addParcels(summary(p3, "SWD-2026-000012", "DELIVERED", 1500, "LKR", "Colombo", 1761400200L, 1761400700L))
                .addParcels(summary(p4, "SWD-2026-000013", "FAILED", 1800, "LKR", "Colombo", 1761400300L, 1761400800L))
                .build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(dashboard.activeDeliveriesCount()).isEqualTo(2);
        assertThat(dashboard.completedDeliveriesCount()).isEqualTo(2);
        assertThat(dashboard.recentParcels()).hasSize(4);
    }

    @Test
    void legitimateEmptyCustomerDashboard() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(0)
                .setCompletedCount(0)
                .build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(dashboard.userId()).isEqualTo(CUSTOMER_A_SUB);
        assertThat(dashboard.activeDeliveriesCount()).isEqualTo(0);
        assertThat(dashboard.completedDeliveriesCount()).isEqualTo(0);
        assertThat(dashboard.recentParcels()).isNotNull().isEmpty();
    }

    @Test
    void exactly20RecentParcels() throws Exception {
        ListCustomerParcelsResponse.Builder builder = ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(20)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(10)
                .setCompletedCount(10);

        for (int i = 0; i < 20; i++) {
            builder.addParcels(summary(UUID.randomUUID().toString(), "SWD-2026-" + String.format("%06d", i),
                    i < 10 ? "IN_TRANSIT" : "DELIVERED", 1000 + i, "LKR", "Colombo", 1761400000L + i, 1761400100L + i));
        }
        deliveryResponse.set(builder.build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(dashboard.activeDeliveriesCount()).isEqualTo(10);
        assertThat(dashboard.completedDeliveriesCount()).isEqualTo(10);
        assertThat(dashboard.recentParcels()).hasSize(20);
    }

    @Test
    void moreThan20TotalParcelsWithBoundedPreview() throws Exception {
        // Customer has 50 total parcels, but preview is bounded to 20
        ListCustomerParcelsResponse.Builder builder = ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(50)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(30)
                .setCompletedCount(20);

        for (int i = 0; i < 20; i++) {
            builder.addParcels(summary(UUID.randomUUID().toString(), "SWD-2026-" + String.format("%06d", i),
                    "IN_TRANSIT", 1500, "LKR", "City-" + i, 1761400000L + i, 1761400100L + i));
        }
        deliveryResponse.set(builder.build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(dashboard.activeDeliveriesCount()).isEqualTo(30);
        assertThat(dashboard.completedDeliveriesCount()).isEqualTo(20);
        assertThat(dashboard.recentParcels()).hasSize(20);
    }

    @Test
    void correctAuthenticatedUserId() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20).setActiveCount(0).setCompletedCount(0).build());

        CustomerDashboard dashboardA = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));
        assertThat(dashboardA.userId()).isEqualTo(CUSTOMER_A_SUB);

        CustomerDashboard dashboardB = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_B_SUB, "CUSTOMER")));
        assertThat(dashboardB.userId()).isEqualTo(CUSTOMER_B_SUB);
    }

    @Test
    void correctTimestampsAndIsoFormat() throws Exception {
        String p1 = UUID.randomUUID().toString();
        long createdSec = 1761400000L;
        long updatedSec = 1761400500L;

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(p1, "SWD-2026-000099", "CREATED", 1500, "LKR", "Colombo", createdSec, updatedSec))
                .build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        CustomerParcelSummaryItem item = dashboard.recentParcels().get(0);
        assertThat(item.createdAt()).isEqualTo(Instant.ofEpochSecond(createdSec).toString());
        assertThat(item.updatedAt()).isEqualTo(Instant.ofEpochSecond(updatedSec).toString());
        assertThat(item.createdAt()).endsWith("Z");
        assertThat(item.updatedAt()).endsWith("Z");
    }

    @Test
    void correctCurrencyAndMinorUnitAmounts() throws Exception {
        String p1 = UUID.randomUUID().toString();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(p1, "SWD-2026-000098", "CREATED", 450000, "LKR", "Colombo", 1761400000L, 1761400000L))
                .build());

        CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        CustomerParcelSummaryItem item = dashboard.recentParcels().get(0);
        assertThat(item.amountMinorUnits()).isEqualTo(450000);
        assertThat(item.currency()).isEqualTo("LKR");
    }

    @Test
    void correctLatestStatusMapping() throws Exception {
        for (String status : List.of("CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED")) {
            boolean isTerminal = status.equals("DELIVERED") || status.equals("FAILED");
            deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                    .setTotalCount(1).setPage(0).setPageSize(20)
                    .setActiveCount(isTerminal ? 0 : 1)
                    .setCompletedCount(isTerminal ? 1 : 0)
                    .addParcels(summary(UUID.randomUUID().toString(), "SWD-STATUS-TEST", status, 1000, "LKR", "City", 1761400000L, 1761400000L))
                    .build());

            CustomerDashboard dashboard = (CustomerDashboard) resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));
            assertThat(dashboard.recentParcels().get(0).currentStatus()).isEqualTo(status);
        }
    }

    @Test
    void oneBoundedDeliveryReadInsteadOfNPlusOne() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(2).setPage(0).setPageSize(20).setActiveCount(2).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-1", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-2", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(deliveryCallCount.get()).isEqualTo(1);
    }

    // ── Phase 9.2: Authentication & Authorization ──────────────────────────────────

    @Test
    void missingAuthentication_throwsUnauthenticated() {
        GraphQLContext ctx = GraphQLContext.newContext().build();
        DataFetchingEnvironment emptyEnv = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().graphQLContext(ctx).build();

        assertThatThrownBy(() -> resolver.resolve(emptyEnv))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void unauthenticatedToken_throwsUnauthenticated() {
        Jwt jwt = Jwt.withTokenValue("mock.jwt").header("alg", "RS256").subject(CUSTOMER_A_SUB).build();
        JwtAuthenticationToken unauthenticated = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), CUSTOMER_A_SUB);
        unauthenticated.setAuthenticated(false);

        assertThatThrownBy(() -> resolver.resolve(env(unauthenticated)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void missingJwtToken_throwsUnauthenticated() {
        JwtAuthenticationToken tokenWithoutJwt = org.mockito.Mockito.mock(JwtAuthenticationToken.class);
        org.mockito.Mockito.when(tokenWithoutJwt.isAuthenticated()).thenReturn(true);
        org.mockito.Mockito.when(tokenWithoutJwt.getToken()).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolve(env(tokenWithoutJwt)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void missingSubject_throwsUnauthenticated() {
        Jwt jwt = Jwt.withTokenValue("mock.jwt")
                .header("alg", "RS256")
                .claim("custom", "claim")
                .build();
        JwtAuthenticationToken tokenWithoutSub = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

        assertThatThrownBy(() -> resolver.resolve(env(tokenWithoutSub)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void blankSubject_throwsUnauthenticated() {
        Jwt jwt = Jwt.withTokenValue("mock.jwt")
                .header("alg", "RS256")
                .subject("   ")
                .build();
        JwtAuthenticationToken tokenBlankSub = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), "   ");

        assertThatThrownBy(() -> resolver.resolve(env(tokenBlankSub)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void missingCustomerAuthority_throwsAccessDenied() {
        Jwt jwt = Jwt.withTokenValue("mock.jwt")
                .header("alg", "RS256")
                .subject(CUSTOMER_A_SUB)
                .build();
        JwtAuthenticationToken tokenNoRoles = new JwtAuthenticationToken(jwt, List.of(), CUSTOMER_A_SUB);

        assertThatThrownBy(() -> resolver.resolve(env(tokenNoRoles)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ROLE_CUSTOMER");
    }

    @Test
    void merchantOnlyCallerDenied_throwsAccessDenied() {
        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "MERCHANT"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ROLE_CUSTOMER");
        assertThat(deliveryCallCount.get()).isEqualTo(0);
    }

    @Test
    void courierOnlyCallerDenied_throwsAccessDenied() {
        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "COURIER"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ROLE_CUSTOMER");
        assertThat(deliveryCallCount.get()).isEqualTo(0);
    }

    @Test
    void dispatcherOnlyCallerDenied_throwsAccessDenied() {
        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "DISPATCHER"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ROLE_CUSTOMER");
        assertThat(deliveryCallCount.get()).isEqualTo(0);
    }

    @Test
    void adminOnlyCallerDenied_throwsAccessDenied() {
        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "ADMIN"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ROLE_CUSTOMER");
        assertThat(deliveryCallCount.get()).isEqualTo(0);
    }

    @Test
    void customerACannotSelectCustomerB() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20).setActiveCount(0).setCompletedCount(0).build());

        // Context contains Customer A's verified JWT. Even if environment contained extraneous arguments,
        // the resolver strictly extracts subject from JWT.
        resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        assertThat(lastDeliveryRequest.get()).isNotNull();
        assertThat(lastDeliveryRequest.get().getCustomerSub()).isEqualTo(CUSTOMER_A_SUB);
        assertThat(lastDeliveryRequest.get().getCustomerSub()).isNotEqualTo(CUSTOMER_B_SUB);
    }

    @Test
    void customerAGrpcRequestCarriesCustomerAVerifiedSubject() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20).setActiveCount(0).setCompletedCount(0).build());

        resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER")));

        ListCustomerParcelsRequest captured = lastDeliveryRequest.get();
        assertThat(captured.getCustomerSub()).isEqualTo(CUSTOMER_A_SUB);
        assertThat(captured.getPage()).isEqualTo(0);
        assertThat(captured.getPageSize()).isEqualTo(20);
    }

    @Test
    void providerDenialIsPropagatedSafely() {
        deliveryError.set(Status.PERMISSION_DENIED.withDescription("Delivery rejected cross-customer read").asRuntimeException());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    void noUnauthorizedProviderReadsOccurAfterLocalRoleRejection() {
        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "COURIER"))))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(deliveryCallCount.get()).isEqualTo(0);
        assertThat(lastDeliveryRequest.get()).isNull();
    }

    // ── Phase 9.3: Provider and Data Failures ──────────────────────────────────────

    @Test
    void deliveryUnavailable_propagatesStatus() {
        deliveryError.set(Status.UNAVAILABLE.withDescription("Delivery down").asRuntimeException());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
    }

    @Test
    void deliveryDeadlineExceeded_propagatesStatus() {
        deliveryError.set(Status.DEADLINE_EXCEEDED.withDescription("Deadline exceeded").asRuntimeException());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }

    @Test
    void missingDeliveryClient_throwsUnavailable() {
        CustomerDashboardResolver unconfiguredResolver = new CustomerDashboardResolver(null);

        assertThatThrownBy(() -> unconfiguredResolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
    }

    @Test
    void negativeActiveCount_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20).setActiveCount(-1).setCompletedCount(0).build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void negativeCompletedCount_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20).setActiveCount(0).setCompletedCount(-1).build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void negativeTotalCount_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(-1).setPage(0).setPageSize(20).setActiveCount(0).setCompletedCount(0).build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void inconsistentTotals_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(10).setPage(0).setPageSize(20).setActiveCount(2).setCompletedCount(3).build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void providerReturnsMorePreviewParcelsThanTotalCount_throwsInternal() {
        String p1 = UUID.randomUUID().toString();
        String p2 = UUID.randomUUID().toString();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(p1, "SWD-1", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .addParcels(summary(p2, "SWD-2", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void oversizedPreview_throwsInternal() {
        ListCustomerParcelsResponse.Builder builder = ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(21).setPage(0).setPageSize(20).setActiveCount(21).setCompletedCount(0);

        for (int i = 0; i < 21; i++) {
            builder.addParcels(summary(UUID.randomUUID().toString(), "SWD-" + i, "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L));
        }
        deliveryResponse.set(builder.build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void invalidPageMetadata_unexpectedPage_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(1).setPageSize(20).setActiveCount(0).setCompletedCount(0).build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void invalidPageMetadata_invalidPageSize_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(0).setActiveCount(0).setCompletedCount(0).build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void unknownParcelStatus_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-1", "LOST_FOREVER", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void missingCreatedAtTimestamp_throwsInternal() {
        CustomerParcelSummary itemWithoutCreatedAt = CustomerParcelSummary.newBuilder()
                .setParcelId(UUID.randomUUID().toString())
                .setTrackingNumber("SWD-1")
                .setCurrentStatus("CREATED")
                .setAmountMinorUnits(1000)
                .setCurrency("LKR")
                .setRecipientCity("City")
                .setUpdatedAt(ts(1761400100L))
                .build();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(itemWithoutCreatedAt)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void missingUpdatedAtTimestamp_throwsInternal() {
        CustomerParcelSummary itemWithoutUpdatedAt = CustomerParcelSummary.newBuilder()
                .setParcelId(UUID.randomUUID().toString())
                .setTrackingNumber("SWD-1")
                .setCurrentStatus("CREATED")
                .setAmountMinorUnits(1000)
                .setCurrency("LKR")
                .setRecipientCity("City")
                .setCreatedAt(ts(1761400100L))
                .build();

        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(itemWithoutUpdatedAt)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void updatedAtBeforeCreatedAt_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-1", "CREATED", 1000, "LKR", "City", 1761400500L, 1761400100L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void negativeMonetaryAmount_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-1", "CREATED", -50, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void monetaryIntegerOverflow_throwsInternal() {
        long overflowingAmount = (long) Integer.MAX_VALUE + 1000L;
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-1", "CREATED", overflowingAmount, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void duplicateParcelRecords_throwsInternal() {
        String duplicateId = UUID.randomUUID().toString();
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(2).setPage(0).setPageSize(20).setActiveCount(2).setCompletedCount(0)
                .addParcels(summary(duplicateId, "SWD-DUP-1", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .addParcels(summary(duplicateId, "SWD-DUP-2", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void missingParcelId_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary("", "SWD-1", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void malformedParcelIdUuid_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary("not-a-valid-uuid", "SWD-1", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void missingTrackingNumber_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "   ", "CREATED", 1000, "LKR", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void missingCurrency_throwsInternal() {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-1", "CREATED", 1000, "   ", "City", 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }

    @Test
    void recipientCityExceedsMaximumLength_throwsInternal() {
        String longCity = "C".repeat(257);
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(1).setPage(0).setPageSize(20).setActiveCount(1).setCompletedCount(0)
                .addParcels(summary(UUID.randomUUID().toString(), "SWD-1", "CREATED", 1000, "LKR", longCity, 1761400000L, 1761400000L))
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(CUSTOMER_A_SUB, "CUSTOMER"))))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
    }
}
