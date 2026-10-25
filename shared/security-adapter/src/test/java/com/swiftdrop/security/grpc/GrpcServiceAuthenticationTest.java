package com.swiftdrop.security.grpc;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.MerchantKeyDigestRequest;
import com.swiftdrop.accounts.v1.MerchantKeyDigestResponse;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcServiceAuthenticationTest {

    private final JwtTestHelper jwtHelper = new JwtTestHelper();
    private final AtomicInteger serviceHandlerInvocations = new AtomicInteger(0);

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        serviceHandlerInvocations.set(0);
        String serverName = InProcessServerBuilder.generateName();

        ServiceTokenValidator tokenValidator = new JwtServiceTokenValidator(
                jwtHelper.createServiceJwtDecoder(),
                JwtTestHelper.DEFAULT_SERVICE_AUDIENCE,
                JwtTestHelper.DEFAULT_ISSUER
        );

        SwiftDropGrpcServerSecurityInterceptor securityInterceptor =
                new SwiftDropGrpcServerSecurityInterceptor(tokenValidator);

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(securityInterceptor)
                .addService(new MockAccountsInternalService(serviceHandlerInvocations))
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
    @DisplayName("Valid signed service token from authorized caller succeeds")
    void testValidSignedServiceIdentity() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        MerchantKeyDigestResponse response = stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder()
                        .setKeyPrefix("swd_live_abc1")
                        .setKeyDigest("digest-12345678")
                        .build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getIsValid()).isTrue();
        assertThat(serviceHandlerInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Missing service token rejects call with UNAUTHENTICATED")
    void testMissingServiceToken() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(sre.getStatus().getDescription()).contains("Missing service authentication token");
                });

        assertThat(serviceHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Forged service token signed with untrusted key rejects call with UNAUTHENTICATED")
    void testForgedServiceToken() {
        String forgedToken = jwtHelper.createUntrustedSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, forgedToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                });

        assertThat(serviceHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Expired service token rejects call with UNAUTHENTICATED")
    void testExpiredServiceToken() {
        String expiredToken = jwtHelper.createExpiredServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, expiredToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(sre.getStatus().getDescription()).contains("expired");
                });

        assertThat(serviceHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Wrong service audience rejects call with UNAUTHENTICATED")
    void testWrongServiceAudience() {
        String wrongAudienceToken = jwtHelper.createSignedServiceToken(
                SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(),
                "wrong-service-aud",
                JwtTestHelper.DEFAULT_ISSUER,
                Instant.now().plus(1, ChronoUnit.HOURS),
                null,
                null
        );

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, wrongAudienceToken);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                });

        assertThat(serviceHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Unauthorized caller service rejects call with PERMISSION_DENIED")
    void testUnauthorizedServiceCaller() {
        // communications-service is a valid service, but is NOT authorized for ValidateMerchantKeyDigest
        String commsToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE.getServiceName());

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, commsToken);

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
                    assertThat(sre.getStatus().getDescription()).contains("not authorized");
                });

        assertThat(serviceHandlerInvocations.get()).isZero();
    }

    @Test
    @DisplayName("Metadata forwarding without valid service token fails closed with UNAUTHENTICATED")
    void testMetadataForwardingWithoutServiceTokenFails() {
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "user-sub-1");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "ADMIN");
        headers.put(SwiftDropGrpcMetadata.KEY_CORRELATION_ID, "corr-12345");

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                });

        assertThat(serviceHandlerInvocations.get()).isZero();
    }

    private static class MockAccountsInternalService extends AccountsInternalServiceGrpc.AccountsInternalServiceImplBase {
        private final AtomicInteger counter;

        MockAccountsInternalService(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public void validateMerchantKeyDigest(MerchantKeyDigestRequest request, StreamObserver<MerchantKeyDigestResponse> responseObserver) {
            counter.incrementAndGet();
            MerchantKeyDigestResponse response = MerchantKeyDigestResponse.newBuilder()
                    .setIsValid(true)
                    .setMerchantId("merchant-uuid-1")
                    .setBusinessName("Test Merchant")
                    .setStatus("APPROVED")
                    .addScopes("parcels:create")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
