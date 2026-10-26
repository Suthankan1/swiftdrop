package com.swiftdrop.graphql.resolver;

import com.google.protobuf.Timestamp;
import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.*;

class CourierDashboardResolverTest {

    private static final String COURIER_SUB = "courier-sub-12345";
    private static final String CORRELATION_ID = "corr-req-test-999";

    private String accountsServerName;
    private String deliveryServerName;
    private Server accountsServer;
    private Server deliveryServer;

    private AuthenticatedReadClients accountsClient;
    private AuthenticatedReadClients deliveryClient;
    private CourierDashboardResolver resolver;

    private final AtomicReference<AccountEligibilityResponse> accountsEligibilityResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();
    private final AtomicReference<AccountEligibilityRequest> lastAccountsRequest = new AtomicReference<>();

    private final AtomicReference<ListCourierAssignmentsResponse> deliveryAssignmentsResponse = new AtomicReference<>();
    private final AtomicReference<StatusRuntimeException> deliveryError = new AtomicReference<>();
    private final AtomicReference<ListCourierAssignmentsRequest> lastDeliveryRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        accountsEligibilityResponse.set(null);
        accountsError.set(null);
        lastAccountsRequest.set(null);

        deliveryAssignmentsResponse.set(null);
        deliveryError.set(null);
        lastDeliveryRequest.set(null);

        accountsServerName = InProcessServerBuilder.generateName();
        deliveryServerName = InProcessServerBuilder.generateName();

        accountsServer = InProcessServerBuilder.forName(accountsServerName).directExecutor()
                .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                    @Override
                    public void checkAccountEligibility(AccountEligibilityRequest req, StreamObserver<AccountEligibilityResponse> out) {
                        lastAccountsRequest.set(req);
                        if (accountsError.get() != null) {
                            out.onError(accountsError.get());
                        } else if (accountsEligibilityResponse.get() != null) {
                            out.onNext(accountsEligibilityResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                        }
                    }
                }).build().start();

        deliveryServer = InProcessServerBuilder.forName(deliveryServerName).directExecutor()
                .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                    @Override
                    public void listCourierAssignments(ListCourierAssignmentsRequest req, StreamObserver<ListCourierAssignmentsResponse> out) {
                        lastDeliveryRequest.set(req);
                        if (deliveryError.get() != null) {
                            out.onError(deliveryError.get());
                        } else if (deliveryAssignmentsResponse.get() != null) {
                            out.onNext(deliveryAssignmentsResponse.get());
                            out.onCompleted();
                        } else {
                            out.onError(Status.UNAVAILABLE.withDescription("Delivery not configured").asRuntimeException());
                        }
                    }
                }).build().start();

        ManagedChannel accountsChannel = InProcessChannelBuilder.forName(accountsServerName).directExecutor().build();
        ManagedChannel deliveryChannel = InProcessChannelBuilder.forName(deliveryServerName).directExecutor().build();

        accountsClient = com.swiftdrop.graphql.client.TestClients.inProcess(accountsChannel, () -> "test-service-token");
        deliveryClient = com.swiftdrop.graphql.client.TestClients.inProcess(deliveryChannel, () -> "test-service-token");

        resolver = new CourierDashboardResolver(accountsClient, deliveryClient);
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

    private DataFetchingEnvironment createEnvironment(JwtAuthenticationToken actor) {
        GraphQLContext context = GraphQLContext.newContext()
                .of("actor", actor, "correlationId", CORRELATION_ID)
                .build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .graphQLContext(context)
                .build();
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

    @Test
    void fieldNameIsCourierDashboard() {
        assertThat(resolver.field()).isEqualTo("courierDashboard");
    }

    @Test
    void activeEligibleCourierRetrievesAuthorizedDashboardAndPreservesCounts() throws Exception {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .setRejectionReason("ELIGIBLE")
                .build());

        Timestamp assignedAt1 = Timestamp.newBuilder().setSeconds(1761400000L).setNanos(500000).build();
        Timestamp assignedAt2 = Timestamp.newBuilder().setSeconds(1761410000L).setNanos(200000).build();

        CourierAssignmentSummary item1 = CourierAssignmentSummary.newBuilder()
                .setAssignmentId("asgn-1")
                .setParcelId("parcel-101")
                .setTrackingNumber("SWD-2026-000101")
                .setCurrentStatus("IN_TRANSIT")
                .setPickupAddress("123 Main St, Colombo")
                .setDropoffAddress("456 Galle Rd, Colombo")
                .setRecipientName("Alice Perera")
                .setRecipientPhone("+94771234567")
                .setAssignedAt(assignedAt1)
                .setVersion(3L)
                .setIsActive(true)
                .build();

        CourierAssignmentSummary item2 = CourierAssignmentSummary.newBuilder()
                .setAssignmentId("asgn-2")
                .setParcelId("parcel-102")
                .setTrackingNumber("SWD-2026-000102")
                .setCurrentStatus("OUT_FOR_DELIVERY")
                .setPickupAddress("")
                .setDropoffAddress("")
                .setRecipientName("")
                .setRecipientPhone("")
                .setAssignedAt(assignedAt2)
                .setVersion(1L)
                .setIsActive(true)
                .build();

        deliveryAssignmentsResponse.set(ListCourierAssignmentsResponse.newBuilder()
                .setTotalCount(2)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(2)
                .setTodayDeliveredCount(7)
                .setTodayFailedCount(1)
                .addAssignments(item1)
                .addAssignments(item2)
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        Object result = resolver.resolve(createEnvironment(actor));

        assertThat(result).isInstanceOf(CourierDashboard.class);
        CourierDashboard dashboard = (CourierDashboard) result;

        assertThat(dashboard.courierSub()).isEqualTo(COURIER_SUB);
        assertThat(dashboard.activeAssignedCount()).isEqualTo(2);
        assertThat(dashboard.todayDeliveredCount()).isEqualTo(7);
        assertThat(dashboard.todayFailedCount()).isEqualTo(1);
        assertThat(dashboard.assignedParcels()).hasSize(2);

        CourierParcelItem parcel1 = dashboard.assignedParcels().get(0);
        assertThat(parcel1.parcelId()).isEqualTo("parcel-101");
        assertThat(parcel1.trackingNumber()).isEqualTo("SWD-2026-000101");
        assertThat(parcel1.currentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(parcel1.pickupAddress()).isEqualTo("123 Main St, Colombo");
        assertThat(parcel1.dropoffAddress()).isEqualTo("456 Galle Rd, Colombo");
        assertThat(parcel1.recipientName()).isEqualTo("Alice Perera");
        assertThat(parcel1.recipientPhone()).isEqualTo("+94771234567");
        assertThat(parcel1.assignedAt()).isEqualTo(Instant.ofEpochSecond(1761400000L, 500000).toString());
        assertThat(parcel1.version()).isEqualTo(3);

        CourierParcelItem parcel2 = dashboard.assignedParcels().get(1);
        assertThat(parcel2.parcelId()).isEqualTo("parcel-102");
        assertThat(parcel2.trackingNumber()).isEqualTo("SWD-2026-000102");
        assertThat(parcel2.currentStatus()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(parcel2.pickupAddress()).isEmpty();
        assertThat(parcel2.dropoffAddress()).isEmpty();
        assertThat(parcel2.recipientName()).isNull();
        assertThat(parcel2.recipientPhone()).isNull();
        assertThat(parcel2.assignedAt()).isEqualTo(Instant.ofEpochSecond(1761410000L, 200000).toString());
        assertThat(parcel2.version()).isEqualTo(1);

        assertThat(lastAccountsRequest.get().getSubject()).isEqualTo(COURIER_SUB);
        assertThat(lastAccountsRequest.get().getRequiredRole()).isEqualTo("COURIER");

        assertThat(lastDeliveryRequest.get().getCourierSub()).isEqualTo(COURIER_SUB);
        assertThat(lastDeliveryRequest.get().getPage()).isEqualTo(0);
        assertThat(lastDeliveryRequest.get().getPageSize()).isEqualTo(20);
        assertThat(lastDeliveryRequest.get().getActiveOnly()).isTrue();
    }

    @Test
    void emptyAssignmentsReturnsAuthoritativeZeroCountsAndEmptyList() throws Exception {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryAssignmentsResponse.set(ListCourierAssignmentsResponse.newBuilder()
                .setTotalCount(0)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(0)
                .setTodayDeliveredCount(0)
                .setTodayFailedCount(0)
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        CourierDashboard dashboard = (CourierDashboard) resolver.resolve(createEnvironment(actor));

        assertThat(dashboard.courierSub()).isEqualTo(COURIER_SUB);
        assertThat(dashboard.activeAssignedCount()).isEqualTo(0);
        assertThat(dashboard.todayDeliveredCount()).isEqualTo(0);
        assertThat(dashboard.todayFailedCount()).isEqualTo(0);
        assertThat(dashboard.assignedParcels()).isEmpty();
    }

    @Test
    void unauthenticatedCallerRejected() {
        GraphQLContext ctxWithoutActor = GraphQLContext.newContext().of("correlationId", "c1").build();
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().graphQLContext(ctxWithoutActor).build();

        assertThatThrownBy(() -> resolver.resolve(env))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void blankSubjectRejected() {
        JwtAuthenticationToken actor = createActor("   ", "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void nonCourierRoleRejected() {
        for (String role : List.of("CUSTOMER", "MERCHANT", "DISPATCHER", "ADMIN")) {
            JwtAuthenticationToken actor = createActor(COURIER_SUB, role);
            assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("ROLE_COURIER");
        }
    }

    @Test
    void suspendedCourierRejectedByAccountsEligibility() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(false)
                .setIsActive(false)
                .setStatus("SUSPENDED")
                .setRejectionReason("COURIER_SUSPENDED")
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void inactiveCourierRejectedByAccountsEligibility() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(false)
                .setIsActive(false)
                .setStatus("INACTIVE")
                .setRejectionReason("COURIER_INACTIVE")
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void notFoundCourierRejectedByAccountsEligibility() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(false)
                .setIsActive(false)
                .setStatus("NOT_FOUND")
                .setRejectionReason("STAFF_NOT_FOUND")
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void accountsSubjectMismatchRejectedAsImpersonation() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject("imposter-subject-999")
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void accountsProviderUnavailableFailsClosed() {
        accountsError.set(Status.UNAVAILABLE.withDescription("Accounts service is down").asRuntimeException());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void accountsProviderDeadlineExceededFailsClosed() {
        accountsError.set(Status.DEADLINE_EXCEEDED.withDescription("Accounts call timed out").asRuntimeException());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    }

    @Test
    void deliveryProviderUnavailableFailsClosed() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryError.set(Status.UNAVAILABLE.withDescription("Delivery service unreachable").asRuntimeException());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void deliveryProviderPermissionDeniedFailsClosed() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryError.set(Status.PERMISSION_DENIED.withDescription("Not authorized for assignments").asRuntimeException());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void oversizedProviderResultRejected() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        ListCourierAssignmentsResponse.Builder builder = ListCourierAssignmentsResponse.newBuilder()
                .setActiveCount(51)
                .setTodayDeliveredCount(0)
                .setTodayFailedCount(0);

        for (int i = 0; i < 51; i++) {
            builder.addAssignments(CourierAssignmentSummary.newBuilder()
                    .setParcelId("parcel-" + i)
                    .setTrackingNumber("SWD-" + i)
                    .setCurrentStatus("CREATED")
                    .setAssignedAt(Timestamp.newBuilder().setSeconds(1761400000L).build())
                    .setVersion(1L)
                    .build());
        }

        deliveryAssignmentsResponse.set(builder.build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void negativeCountsRejected() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryAssignmentsResponse.set(ListCourierAssignmentsResponse.newBuilder()
                .setActiveCount(-1)
                .setTodayDeliveredCount(0)
                .setTodayFailedCount(0)
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void invalidLifecycleEnumRejected() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryAssignmentsResponse.set(ListCourierAssignmentsResponse.newBuilder()
                .setActiveCount(1)
                .addAssignments(CourierAssignmentSummary.newBuilder()
                        .setParcelId("p-1")
                        .setTrackingNumber("SWD-001")
                        .setCurrentStatus("UNKNOWN")
                        .setAssignedAt(Timestamp.newBuilder().setSeconds(1761400000L).build())
                        .setVersion(1L)
                        .build())
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void missingTimestampRejected() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryAssignmentsResponse.set(ListCourierAssignmentsResponse.newBuilder()
                .setActiveCount(1)
                .addAssignments(CourierAssignmentSummary.newBuilder()
                        .setParcelId("p-1")
                        .setTrackingNumber("SWD-001")
                        .setCurrentStatus("CREATED")
                        .setVersion(1L)
                        .build())
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void versionOverflowRejected() {
        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryAssignmentsResponse.set(ListCourierAssignmentsResponse.newBuilder()
                .setActiveCount(1)
                .addAssignments(CourierAssignmentSummary.newBuilder()
                        .setParcelId("p-1")
                        .setTrackingNumber("SWD-001")
                        .setCurrentStatus("CREATED")
                        .setAssignedAt(Timestamp.newBuilder().setSeconds(1761400000L).build())
                        .setVersion((long) Integer.MAX_VALUE + 100L)
                        .build())
                .build());

        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");
        assertThatThrownBy(() -> resolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    void missingProviderClientsFailsClosed() {
        CourierDashboardResolver missingClientsResolver = new CourierDashboardResolver(null, null);
        JwtAuthenticationToken actor = createActor(COURIER_SUB, "COURIER");

        assertThatThrownBy(() -> missingClientsResolver.resolve(createEnvironment(actor)))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> Status.fromThrowable(e).getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }
}
