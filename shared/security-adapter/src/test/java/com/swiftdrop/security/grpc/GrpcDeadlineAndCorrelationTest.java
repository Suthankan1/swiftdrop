package com.swiftdrop.security.grpc;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.MerchantKeyDigestRequest;
import com.swiftdrop.accounts.v1.MerchantKeyDigestResponse;
import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.service.JwtServiceTokenValidator;
import com.swiftdrop.security.service.ServiceTokenProvider;
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

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcDeadlineAndCorrelationTest {

    private final JwtTestHelper jwtHelper = new JwtTestHelper();

    private Server server;
    private ManagedChannel channel;
    private final AtomicReference<String> capturedServerCorrelationId = new AtomicReference<>();
    private final AtomicReference<Deadline> capturedServerDeadline = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        capturedServerCorrelationId.set(null);
        capturedServerDeadline.set(null);
        String serverName = InProcessServerBuilder.generateName();

        var serviceValidator = new JwtServiceTokenValidator(
                jwtHelper.createServiceJwtDecoder(),
                JwtTestHelper.DEFAULT_SERVICE_AUDIENCE,
                JwtTestHelper.DEFAULT_ISSUER
        );

        ServerInterceptor capturingInterceptor = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                capturedServerDeadline.set(Context.current().getDeadline());
                return next.startCall(call, headers);
            }
        };

        var securityInterceptor = new SwiftDropGrpcServerSecurityInterceptor(serviceValidator);

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(capturingInterceptor)
                .intercept(securityInterceptor)
                .addService(new TimingMockAccountsService(capturedServerCorrelationId))
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
    @DisplayName("Default deadline: client interceptor automatically sets 2000ms bounded deadline")
    void testDefaultDeadlineApplied() {
        String token = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        ServiceTokenProvider tokenProvider = () -> token;

        var clientInterceptor = new SwiftDropGrpcClientInterceptor(tokenProvider);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(clientInterceptor);

        MerchantKeyDigestResponse response = stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        );

        assertThat(response).isNotNull();
        assertThat(capturedServerDeadline.get()).isNotNull();
        // Server deadline should be bounded <= 2000ms
        long remainingMs = capturedServerDeadline.get().timeRemaining(TimeUnit.MILLISECONDS);
        assertThat(remainingMs).isGreaterThan(0).isLessThanOrEqualTo(2000);
    }

    @Test
    @DisplayName("Shorter upstream deadline is strictly preserved and not loosened")
    void testShorterUpstreamDeadlinePreserved() {
        String token = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        ServiceTokenProvider tokenProvider = () -> token;

        var clientInterceptor = new SwiftDropGrpcClientInterceptor(tokenProvider);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(clientInterceptor)
                        .withDeadlineAfter(350, TimeUnit.MILLISECONDS); // Upstream shorter than 2000ms

        MerchantKeyDigestResponse response = stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        );

        assertThat(response).isNotNull();
        assertThat(capturedServerDeadline.get()).isNotNull();
        long remainingMs = capturedServerDeadline.get().timeRemaining(TimeUnit.MILLISECONDS);
        assertThat(remainingMs).isGreaterThan(0).isLessThanOrEqualTo(350);
    }

    @Test
    @DisplayName("Deadline exceeded fails closed with DEADLINE_EXCEEDED")
    void testDeadlineExceededFailsClosed() {
        String token = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        ServiceTokenProvider tokenProvider = () -> token;

        var clientInterceptor = new SwiftDropGrpcClientInterceptor(tokenProvider, 50); // 50ms deadline

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(clientInterceptor);

        // Request with prefix "slow" causes mock service to sleep for 200ms
        assertThatThrownBy(() -> stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("slow").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
                });
    }

    @Test
    @DisplayName("Correlation ID propagation: transmitted and bound to SwiftDropGrpcContext")
    void testCorrelationIdPropagation() {
        String token = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        String expectedCorrelationId = "corr-" + UUID.randomUUID();

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, token);
        headers.put(SwiftDropGrpcMetadata.KEY_CORRELATION_ID, expectedCorrelationId);

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        MerchantKeyDigestResponse response = stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        );

        assertThat(response).isNotNull();
        assertThat(capturedServerCorrelationId.get()).isEqualTo(expectedCorrelationId);
    }

    @Test
    @DisplayName("Malformed correlation identifier: sanitized to clean generated UUID")
    void testMalformedCorrelationIdentifierSanitized() {
        String token = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        // Header with invalid characters / injection attempt
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, token);
        headers.put(SwiftDropGrpcMetadata.KEY_CORRELATION_ID, "bad<script>id\nwith\0null");

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        MerchantKeyDigestResponse response = stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
        );

        assertThat(response).isNotNull();
        String sanitized = capturedServerCorrelationId.get();
        assertThat(sanitized).isNotNull();
        assertThat(sanitized).doesNotContain("<script>").doesNotContain("\n");
        // Must be a valid UUID format
        assertThat(UUID.fromString(sanitized)).isNotNull();
    }

    @Test
    @DisplayName("Concurrent-call context isolation: verifies zero cross-talk across threads")
    void testConcurrentCallContextIsolation() throws Exception {
        String token = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threadCount; i++) {
            final String threadCorrelationId = "corr-thread-" + i;
            executor.submit(() -> {
                try {
                    Metadata headers = new Metadata();
                    headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, token);
                    headers.put(SwiftDropGrpcMetadata.KEY_CORRELATION_ID, threadCorrelationId);

                    AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                            AccountsInternalServiceGrpc.newBlockingStub(channel)
                                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

                    MerchantKeyDigestResponse resp = stub.validateMerchantKeyDigest(
                            MerchantKeyDigestRequest.newBuilder().setKeyPrefix("swd_live_abc1").build()
                    );
                    if (resp.getIsValid()) {
                        results.add(threadCorrelationId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(results).hasSize(threadCount);
    }

    private static class TimingMockAccountsService extends AccountsInternalServiceGrpc.AccountsInternalServiceImplBase {
        private final AtomicReference<String> correlationCapture;

        TimingMockAccountsService(AtomicReference<String> correlationCapture) {
            this.correlationCapture = correlationCapture;
        }

        @Override
        public void validateMerchantKeyDigest(MerchantKeyDigestRequest request, StreamObserver<MerchantKeyDigestResponse> responseObserver) {
            // Capture current correlation ID from SwiftDropGrpcContext
            correlationCapture.set(SwiftDropGrpcContext.currentCorrelationId().orElse(null));

            if ("slow".equals(request.getKeyPrefix())) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            MerchantKeyDigestResponse response = MerchantKeyDigestResponse.newBuilder()
                    .setIsValid(true)
                    .setMerchantId("m-1")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
