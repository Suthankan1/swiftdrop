package com.swiftdrop.security.grpc;

import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.service.JwtServiceTokenValidator;
import com.swiftdrop.security.service.ServiceTokenValidator;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import com.swiftdrop.security.testutil.JwtTestHelper;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcDelegationSpoofingTest {

    private final JwtTestHelper jwtHelper = new JwtTestHelper();
    private final AtomicInteger handlerInvocations = new AtomicInteger(0);

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        handlerInvocations.set(0);
        String serverName = InProcessServerBuilder.generateName();

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

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(securityInterceptor)
                .addService(new MockAccountsInternalService(handlerInvocations))
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

    @Test
    @DisplayName("Valid authenticated actor delegation: verified actor token and matching claims succeed")
    void testValidAuthenticatedActorDelegation() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        String actorSub = "courier-sub-12345";
        String actorToken = jwtHelper.createSignedUserToken(actorSub, List.of("COURIER"));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "COURIER");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, actorToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        CourierEligibilityResponse response = stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub(actorSub).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getIsEligible()).isTrue();
        assertThat(handlerInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Missing actor when required: rejects with UNAUTHENTICATED")
    void testMissingActorWhenRequired() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub("courier-1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(sre.getStatus().getDescription()).contains("Actor delegation required");
                });

        assertThat(handlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Unverified actor forwarding: raw actor metadata without cryptographic proof fails closed")
    void testUnverifiedActorForwardingRejected() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        // Attacker forwards sub and role strings but no actor token or signed claim
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "courier-sub-12345");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "COURIER");

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub("courier-sub-12345").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(sre.getStatus().getDescription()).contains("lacks cryptographic proof");
                });

        assertThat(handlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Forged actor subject: header sub mismatching verified token sub fails with PERMISSION_DENIED")
    void testForgedActorSubjectRejected() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        // Attacker provides their own token for 'attacker-sub', but attempts to impersonate 'victim-sub' in metadata
        String attackerToken = jwtHelper.createSignedUserToken("attacker-sub", List.of("COURIER"));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "victim-sub");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "COURIER");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, attackerToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub("victim-sub").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(sre.getStatus().getDescription()).contains("Forged actor subject");
                });

        assertThat(handlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Forged role: claiming elevated role (ADMIN) not present in verified actor token fails closed")
    void testForgedPrivilegedRoleRejected() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        // User token grants only COURIER
        String courierToken = jwtHelper.createSignedUserToken("courier-sub", List.of("COURIER"));

        // Header claims ADMIN
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "courier-sub");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "ADMIN");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, courierToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub("courier-sub").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(sre.getStatus().getDescription()).contains("role elevation rejected");
                });

        assertThat(handlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Admin-only RPC rejected for non-admin actor: customer calling GetPendingMerchantsSummary fails")
    void testAdminOnlyRpcRejectedForNonAdminActor() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName());
        String customerToken = jwtHelper.createSignedUserToken("customer-sub", List.of("CUSTOMER"));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "customer-sub");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "CUSTOMER");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, customerToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.getPendingMerchantsSummary(
                PendingMerchantsSummaryRequest.newBuilder().setPage(0).setPageSize(10).build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(sre.getStatus().getDescription()).contains("does not possess required role");
                });

        assertThat(handlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Admin-only RPC succeeds for verified ADMIN actor")
    void testAdminOnlyRpcAcceptedForAdminActor() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName());
        String adminToken = jwtHelper.createSignedUserToken("admin-sub", List.of("ADMIN"));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "admin-sub");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "ADMIN");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, adminToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        PendingMerchantsSummaryResponse response = stub.getPendingMerchantsSummary(
                PendingMerchantsSummaryRequest.newBuilder().setPage(0).setPageSize(10).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getTotalPendingCount()).isEqualTo(1);
        assertThat(handlerInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Service-only RPC rejects synthetic human actor metadata with PERMISSION_DENIED")
    void testServiceOnlyCallRejectsSyntheticHumanActor() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        // Attempting to inject human actor headers into service-only ValidateMerchantKeyDigest
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "fake-human-sub");

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(sre.getStatus().getDescription()).contains("Actor delegation is forbidden");
                });

        assertThat(handlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Background job without synthetic human identity succeeds")
    void testBackgroundJobOperationWithoutSyntheticHumanIdentity() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE.getServiceName());

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        RecipientContactResponse response = stub.resolveRecipientContact(
                RecipientContactRequest.newBuilder().setRecipientSub("recipient-sub-1").build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("customer@example.com");
        assertThat(handlerInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Signed delegation embedded in service token is accepted and verified")
    void testSignedDelegationInServiceTokenAccepted() {
        String actorSub = "courier-sub-signed";
        String serviceTokenWithDelegation = jwtHelper.createSignedServiceToken(
                SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(),
                JwtTestHelper.DEFAULT_SERVICE_AUDIENCE,
                JwtTestHelper.DEFAULT_ISSUER,
                java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.HOURS),
                actorSub,
                List.of("COURIER")
        );

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceTokenWithDelegation);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "COURIER");

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        CourierEligibilityResponse response = stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub(actorSub).build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getIsEligible()).isTrue();
        assertThat(handlerInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AuthorizeEventRecipient policy: COMMUNICATIONS_SERVICE allowed, actor delegation FORBIDDEN")
    void testAuthorizeEventRecipientPolicy() {
        GrpcSecurityPolicy policy = new GrpcSecurityPolicy();
        var opt = policy.getPolicy("swiftdrop.delivery.v1.DeliveryInternalService/AuthorizeEventRecipient");
        assertThat(opt).isPresent();
        var p = opt.get();
        assertThat(p.isCallerAllowed(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE)).isTrue();
        assertThat(p.isCallerAllowed(SwiftDropServiceIdentity.DELIVERY_SERVICE)).isFalse();
        assertThat(p.isCallerAllowed(SwiftDropServiceIdentity.ACCOUNTS_SERVICE)).isFalse();
        assertThat(p.delegationRequirement()).isEqualTo(GrpcSecurityPolicy.DelegationRequirement.FORBIDDEN);
    }

    private static class MockAccountsInternalService extends AccountsInternalServiceGrpc.AccountsInternalServiceImplBase {
        private final AtomicInteger counter;

        MockAccountsInternalService(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public void checkCourierEligibility(CourierEligibilityRequest request, StreamObserver<CourierEligibilityResponse> responseObserver) {
            counter.incrementAndGet();
            responseObserver.onNext(CourierEligibilityResponse.newBuilder()
                    .setCourierSub(request.getCourierSub())
                    .setIsEligible(true)
                    .setIsActive(true)
                    .setFullName("John Courier")
                    .setRejectionReason("ELIGIBLE")
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void validateMerchantKeyDigest(MerchantKeyDigestRequest request, StreamObserver<MerchantKeyDigestResponse> responseObserver) {
            counter.incrementAndGet();
            responseObserver.onNext(MerchantKeyDigestResponse.newBuilder().setIsValid(true).build());
            responseObserver.onCompleted();
        }

        @Override
        public void resolveRecipientContact(RecipientContactRequest request, StreamObserver<RecipientContactResponse> responseObserver) {
            counter.incrementAndGet();
            responseObserver.onNext(RecipientContactResponse.newBuilder()
                    .setRecipientSub(request.getRecipientSub())
                    .setEmail("customer@example.com")
                    .setFullName("Jane Customer")
                    .setPhoneNumber("+94771234567")
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void getPendingMerchantsSummary(PendingMerchantsSummaryRequest request, StreamObserver<PendingMerchantsSummaryResponse> responseObserver) {
            counter.incrementAndGet();
            responseObserver.onNext(PendingMerchantsSummaryResponse.newBuilder()
                    .setTotalPendingCount(1)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize())
                    .addItems(PendingMerchantItem.newBuilder()
                            .setMerchantId("m-1")
                            .setBusinessName("Acme Ltd")
                            .setStatus("PENDING")
                            .build())
                    .build());
            responseObserver.onCompleted();
        }
    }
}
