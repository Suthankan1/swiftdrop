package com.swiftdrop.graphql.resolver;

import com.google.protobuf.Timestamp;
import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.delivery.v1.ListMerchantOrdersRequest;
import com.swiftdrop.delivery.v1.ListMerchantOrdersResponse;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MerchantDashboardResolver}.
 *
 * <p>Two distinct merchant fixtures (MERCHANT_A and MERCHANT_B) are used to explicitly verify
 * that neither merchant can see the other's data. All gRPC calls go through real in-process
 * servers, exercising the full resolver/client/proto stack without needing live containers.
 */
class MerchantDashboardResolverTest {

    // Merchant A — the primary test subject.
    private static final String MERCHANT_A_SUB = "sub-merchant-aaaaa";
    private static final String MERCHANT_A_ID = "merchant-id-aaaaa";
    private static final String MERCHANT_A_BUSINESS = "Alpha Traders Ltd";

    // Merchant B — used for cross-tenant isolation tests.
    private static final String MERCHANT_B_SUB = "sub-merchant-bbbbb";
    private static final String MERCHANT_B_ID = "merchant-id-bbbbb";
    private static final String MERCHANT_B_BUSINESS = "Beta Logistics Ltd";

    private static final String CORRELATION_ID = "corr-merchant-test-001";

    // In-process servers + captured last requests (for assertion).
    private Server accountsServer;
    private Server deliveryServer;
    private AuthenticatedReadClients accountsClient;
    private AuthenticatedReadClients deliveryClient;
    private MerchantDashboardResolver resolver;

    private final AtomicReference<AccountEligibilityResponse> accountsResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();
    private final AtomicReference<AccountEligibilityRequest> lastAccountsRequest = new AtomicReference<>();

    private final AtomicReference<ListMerchantOrdersResponse> deliveryResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> deliveryError = new AtomicReference<>();
    private final AtomicReference<ListMerchantOrdersRequest> lastDeliveryRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        accountsResponse.set(null);
        accountsError.set(null);
        lastAccountsRequest.set(null);
        deliveryResponse.set(null);
        deliveryError.set(null);
        lastDeliveryRequest.set(null);

        String accountsName = InProcessServerBuilder.generateName();
        String deliveryName = InProcessServerBuilder.generateName();

        accountsServer = InProcessServerBuilder.forName(accountsName).directExecutor()
                .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                    @Override
                    public void checkAccountEligibility(AccountEligibilityRequest req,
                            StreamObserver<AccountEligibilityResponse> out) {
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

        deliveryServer = InProcessServerBuilder.forName(deliveryName).directExecutor()
                .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                    @Override
                    public void listMerchantOrders(ListMerchantOrdersRequest req,
                            StreamObserver<ListMerchantOrdersResponse> out) {
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

        ManagedChannel aChan = InProcessChannelBuilder.forName(accountsName).directExecutor().build();
        ManagedChannel dChan = InProcessChannelBuilder.forName(deliveryName).directExecutor().build();
        accountsClient = com.swiftdrop.graphql.client.TestClients.inProcess(aChan, () -> "test-service-token");
        deliveryClient = com.swiftdrop.graphql.client.TestClients.inProcess(dChan, () -> "test-service-token");
        resolver = new MerchantDashboardResolver(accountsClient, deliveryClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (accountsClient != null) accountsClient.close();
        if (deliveryClient != null) deliveryClient.close();
        if (accountsServer != null) { accountsServer.shutdownNow(); accountsServer.awaitTermination(5, TimeUnit.SECONDS); }
        if (deliveryServer != null) { deliveryServer.shutdownNow(); deliveryServer.awaitTermination(5, TimeUnit.SECONDS); }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private DataFetchingEnvironment env(JwtAuthenticationToken actor) {
        GraphQLContext ctx = GraphQLContext.newContext()
                .of("actor", actor, "correlationId", CORRELATION_ID)
                .build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment().graphQLContext(ctx).build();
    }

    private JwtAuthenticationToken actor(String sub, String role) {
        Jwt jwt = Jwt.withTokenValue("mock.jwt")
                .header("alg", "RS256")
                .subject(sub)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), sub);
    }

    private AccountEligibilityResponse approvedMerchantResponse(String sub, String merchantId, String businessName) {
        return AccountEligibilityResponse.newBuilder()
                .setSubject(sub)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("APPROVED")
                .setMerchantId(merchantId)
                .setBusinessName(businessName)
                .setRejectionReason("ELIGIBLE")
                .build();
    }

    private Timestamp ts(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds).setNanos(0).build();
    }

    // ── Happy path ─────────────────────────────────────────────────────────────────

    @Test
    void fieldNameIsMerchantDashboard() {
        assertThat(resolver.field()).isEqualTo("merchantDashboard");
    }

    @Test
    void approvedMerchantReceivesFullDashboard() throws Exception {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));

        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(10)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(4)
                .setDeliveredCount(5)
                .setFailedCount(1)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("parcel-aaa-001")
                        .setTrackingNumber("SWD-2026-001001")
                        .setCurrentStatus("IN_TRANSIT")
                        .setWeightKg(2.5)
                        .setFeeMinorUnits(1500L)
                        .setCurrency("LKR")
                        .setCreatedAt(ts(1761400000L))
                        .setLastStatusUpdate(ts(1761410000L))
                        .build())
                .build());

        Object result = resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT")));

        assertThat(result).isInstanceOf(MerchantDashboard.class);
        MerchantDashboard d = (MerchantDashboard) result;

        // Identity sourced from Accounts, never from caller.
        assertThat(d.merchantId()).isEqualTo(MERCHANT_A_ID);
        assertThat(d.businessName()).isEqualTo(MERCHANT_A_BUSINESS);
        assertThat(d.status()).isEqualTo("APPROVED");

        // Counts from Delivery.
        assertThat(d.totalOrdersCount()).isEqualTo(10);
        assertThat(d.activeOrdersCount()).isEqualTo(4);
        assertThat(d.deliveredOrdersCount()).isEqualTo(5);
        assertThat(d.failedOrdersCount()).isEqualTo(1);

        assertThat(d.recentOrders()).hasSize(1);
        MerchantOrderSummaryItem order = d.recentOrders().get(0);
        assertThat(order.parcelId()).isEqualTo("parcel-aaa-001");
        assertThat(order.trackingNumber()).isEqualTo("SWD-2026-001001");
        assertThat(order.currentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(order.weightKg()).isEqualTo(2.5);
        assertThat(order.feeMinorUnits()).isEqualTo(1500);
        assertThat(order.currency()).isEqualTo("LKR");
        assertThat(order.createdAt()).isEqualTo(Instant.ofEpochSecond(1761400000L).toString());
        assertThat(order.lastStatusUpdate()).isEqualTo(Instant.ofEpochSecond(1761410000L).toString());

        // Verify Accounts was called with the correct subject and role.
        assertThat(lastAccountsRequest.get().getSubject()).isEqualTo(MERCHANT_A_SUB);
        assertThat(lastAccountsRequest.get().getRequiredRole()).isEqualTo("MERCHANT");

        // Verify Delivery was scoped to the Accounts-confirmed merchantId.
        assertThat(lastDeliveryRequest.get().getMerchantId()).isEqualTo(MERCHANT_A_ID);
        assertThat(lastDeliveryRequest.get().getPage()).isEqualTo(0);
        assertThat(lastDeliveryRequest.get().getPageSize()).isEqualTo(20);
    }

    @Test
    void emptyOrderListReturnsZeroCountsAndEmptyList() throws Exception {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20)
                .setActiveCount(0).setDeliveredCount(0).setFailedCount(0)
                .build());

        MerchantDashboard d = (MerchantDashboard) resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT")));

        assertThat(d.merchantId()).isEqualTo(MERCHANT_A_ID);
        assertThat(d.totalOrdersCount()).isEqualTo(0);
        assertThat(d.activeOrdersCount()).isEqualTo(0);
        assertThat(d.deliveredOrdersCount()).isEqualTo(0);
        assertThat(d.failedOrdersCount()).isEqualTo(0);
        assertThat(d.recentOrders()).isEmpty();
    }

    // ── Authentication / identity guards ──────────────────────────────────────────

    @Test
    void unauthenticatedCallerRejected() {
        GraphQLContext ctx = GraphQLContext.newContext().of("correlationId", CORRELATION_ID).build();
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().graphQLContext(ctx).build();

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void blankSubjectRejected() {
        assertThatThrownBy(() -> resolver.resolve(env(actor("   ", "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void nonMerchantRoleRejected() {
        for (String role : List.of("CUSTOMER", "COURIER", "DISPATCHER", "ADMIN")) {
            assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, role))))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("ROLE_MERCHANT");
        }
    }

    // ── Merchant enrollment / approval state guards ───────────────────────────────

    @Test
    void pendingMerchantRejected() {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(MERCHANT_A_SUB).setIsEligible(false).setIsActive(false)
                .setStatus("PENDING").setRejectionReason("MERCHANT_PENDING")
                .setMerchantId(MERCHANT_A_ID).setBusinessName(MERCHANT_A_BUSINESS)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void suspendedMerchantRejected() {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(MERCHANT_A_SUB).setIsEligible(false).setIsActive(false)
                .setStatus("SUSPENDED").setRejectionReason("MERCHANT_SUSPENDED")
                .setMerchantId(MERCHANT_A_ID).setBusinessName(MERCHANT_A_BUSINESS)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void rejectedMerchantRejected() {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(MERCHANT_A_SUB).setIsEligible(false).setIsActive(false)
                .setStatus("REJECTED").setRejectionReason("MERCHANT_REJECTED")
                .setMerchantId(MERCHANT_A_ID).setBusinessName(MERCHANT_A_BUSINESS)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void notFoundMerchantRejected() {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(MERCHANT_A_SUB).setIsEligible(false).setIsActive(false)
                .setStatus("NOT_FOUND").setRejectionReason("ACCOUNT_NOT_FOUND")
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    // ── Anti-spoofing / cross-merchant isolation ──────────────────────────────────

    @Test
    void accountsSubjectMismatchRejectedAsImpersonation() {
        // Accounts returns a response for a different subject than the one we sent.
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject("imposter-sub-zzzzz")
                .setIsEligible(true).setIsActive(true).setStatus("APPROVED")
                .setMerchantId(MERCHANT_A_ID).setBusinessName(MERCHANT_A_BUSINESS)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void missingMerchantIdFromAccountsRejected() {
        // Accounts approves but returns no merchantId — no unscoped delivery query is possible.
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(MERCHANT_A_SUB).setIsEligible(true).setIsActive(true)
                .setStatus("APPROVED").setMerchantId("").setBusinessName(MERCHANT_A_BUSINESS)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void merchantBCannotSeeMerchantADataByChangingContext() throws Exception {
        // Merchant B is approved with their own merchantId.
        accountsResponse.set(approvedMerchantResponse(MERCHANT_B_SUB, MERCHANT_B_ID, MERCHANT_B_BUSINESS));

        // Delivery returns Merchant B's orders, scoped to MERCHANT_B_ID.
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(3).setPage(0).setPageSize(20)
                .setActiveCount(2).setDeliveredCount(1).setFailedCount(0)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("parcel-bbb-001").setTrackingNumber("SWD-2026-002001")
                        .setCurrentStatus("DELIVERED").setWeightKg(1.0).setFeeMinorUnits(800L)
                        .setCurrency("LKR").setCreatedAt(ts(1761500000L)).setLastStatusUpdate(ts(1761510000L))
                        .build())
                .build());

        // Merchant B authenticates and calls the resolver.
        MerchantDashboard d = (MerchantDashboard) resolver.resolve(env(actor(MERCHANT_B_SUB, "MERCHANT")));

        // Must see only Merchant B's data.
        assertThat(d.merchantId()).isEqualTo(MERCHANT_B_ID);
        assertThat(d.businessName()).isEqualTo(MERCHANT_B_BUSINESS);
        assertThat(d.totalOrdersCount()).isEqualTo(3);
        assertThat(d.recentOrders().get(0).parcelId()).isEqualTo("parcel-bbb-001");

        // Delivery was called with MERCHANT_B_ID — never with MERCHANT_A_ID.
        assertThat(lastDeliveryRequest.get().getMerchantId()).isEqualTo(MERCHANT_B_ID);
        assertThat(lastDeliveryRequest.get().getMerchantId()).isNotEqualTo(MERCHANT_A_ID);
    }

    // ── Provider failure cases ─────────────────────────────────────────────────────

    @Test
    void accountsProviderUnavailableFailsClosed() {
        accountsError.set(Status.UNAVAILABLE.withDescription("Accounts down").asRuntimeException());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void accountsProviderDeadlineExceededFailsClosed() {
        accountsError.set(Status.DEADLINE_EXCEEDED.withDescription("Accounts timeout").asRuntimeException());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    }

    @Test
    void deliveryProviderUnavailableFailsClosed() {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryError.set(Status.UNAVAILABLE.withDescription("Delivery down").asRuntimeException());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void deliveryProviderPermissionDeniedFailsClosed() {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryError.set(Status.PERMISSION_DENIED.withDescription("Not authorized").asRuntimeException());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void missingProviderClientsFailsClosed() {
        MerchantDashboardResolver noClients = new MerchantDashboardResolver(null, null);

        assertThatThrownBy(() -> noClients.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    // ── Provider response integrity ────────────────────────────────────────────────

    @Test
    void oversizedOrderListRejected() {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));

        ListMerchantOrdersResponse.Builder b = ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(51).setPage(0).setPageSize(20)
                .setActiveCount(51).setDeliveredCount(0).setFailedCount(0);
        for (int i = 0; i < 51; i++) {
            b.addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                    .setParcelId("p-" + i).setTrackingNumber("SWD-" + i)
                    .setCurrentStatus("CREATED").setCreatedAt(ts(1761400000L)).build());
        }
        deliveryResponse.set(b.build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void negativeCountsRejected() {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(-1).setPage(0).setPageSize(20)
                .setActiveCount(0).setDeliveredCount(0).setFailedCount(0)
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void invalidParcelStatusRejected() {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(1).setActiveCount(1).setDeliveredCount(0).setFailedCount(0)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("p-1").setTrackingNumber("SWD-001")
                        .setCurrentStatus("UNKNOWN_STATUS")
                        .setCreatedAt(ts(1761400000L))
                        .build())
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void missingCreatedAtTimestampRejected() {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(1).setActiveCount(1).setDeliveredCount(0).setFailedCount(0)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("p-1").setTrackingNumber("SWD-001")
                        .setCurrentStatus("CREATED")
                        // No createdAt set.
                        .build())
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void feeMinorUnitsOverflowRejected() {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(1).setActiveCount(1).setDeliveredCount(0).setFailedCount(0)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("p-1").setTrackingNumber("SWD-001")
                        .setCurrentStatus("CREATED")
                        .setCreatedAt(ts(1761400000L))
                        .setFeeMinorUnits((long) Integer.MAX_VALUE + 100L)
                        .build())
                .build());

        assertThatThrownBy(() -> resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT"))))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void lastStatusUpdateFallsBackToCreatedAtWhenAbsent() throws Exception {
        accountsResponse.set(approvedMerchantResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(1).setActiveCount(1).setDeliveredCount(0).setFailedCount(0)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("p-1").setTrackingNumber("SWD-001")
                        .setCurrentStatus("CREATED")
                        .setCreatedAt(ts(1761400000L))
                        // lastStatusUpdate intentionally absent.
                        .build())
                .build());

        MerchantDashboard d = (MerchantDashboard) resolver.resolve(env(actor(MERCHANT_A_SUB, "MERCHANT")));
        MerchantOrderSummaryItem order = d.recentOrders().get(0);
        assertThat(order.lastStatusUpdate()).isEqualTo(order.createdAt());
    }
}
