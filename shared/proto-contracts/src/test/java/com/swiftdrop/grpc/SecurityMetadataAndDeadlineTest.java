package com.swiftdrop.grpc;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.CourierEligibilityRequest;
import com.swiftdrop.accounts.v1.CourierEligibilityResponse;
import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityMetadataAndDeadlineTest {

    private Server server;
    private ManagedChannel channel;
    private final AtomicReference<Metadata> capturedServerMetadata = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();

        ServerInterceptor capturingInterceptor = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                capturedServerMetadata.set(headers);
                String serviceToken = headers.get(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN);
                if (serviceToken == null || serviceToken.isBlank()) {
                    call.close(Status.UNAUTHENTICATED.withDescription("Missing service token"), headers);
                    return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(capturingInterceptor)
                .addService(new SlowMockAccountsService())
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
    @DisplayName("Metadata propagation: transmits service token, actor sub, actor roles, and correlation ID")
    void testMetadataPropagation() {
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, "jwt.service.token");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "user-sub-12345");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "ADMIN,DISPATCHER");
        headers.put(SwiftDropGrpcMetadata.KEY_CORRELATION_ID, "corr-uuid-99999");

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        CourierEligibilityResponse response = stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub("courier-1").build()
        );

        assertThat(response).isNotNull();
        Metadata received = capturedServerMetadata.get();
        assertThat(received).isNotNull();
        assertThat(received.get(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN)).isEqualTo("jwt.service.token");
        assertThat(received.get(SwiftDropGrpcMetadata.KEY_ACTOR_SUB)).isEqualTo("user-sub-12345");
        assertThat(received.get(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES)).isEqualTo("ADMIN,DISPATCHER");
        assertThat(received.get(SwiftDropGrpcMetadata.KEY_CORRELATION_ID)).isEqualTo("corr-uuid-99999");
    }

    @Test
    @DisplayName("Authentication enforcement: rejects calls without service token with UNAUTHENTICATED")
    void testRejectsMissingServiceToken() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub("courier-1").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                });
    }

    @Test
    @DisplayName("Deadline enforcement: client timeout throws DEADLINE_EXCEEDED")
    void testDeadlineExceeded() {
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, "jwt.service.token");

        // Client deadline 50 milliseconds; server takes 200 milliseconds
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub timedStub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .withDeadlineAfter(50, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> timedStub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub("courier-slow").build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
                });
    }

    private static class SlowMockAccountsService extends AccountsInternalServiceGrpc.AccountsInternalServiceImplBase {
        @Override
        public void checkCourierEligibility(CourierEligibilityRequest request,
                                            StreamObserver<CourierEligibilityResponse> responseObserver) {
            if ("courier-slow".equals(request.getCourierSub())) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            CourierEligibilityResponse response = CourierEligibilityResponse.newBuilder()
                    .setCourierSub(request.getCourierSub())
                    .setIsEligible(true)
                    .setIsActive(true)
                    .setFullName("Jane Slow")
                    .setRejectionReason("ELIGIBLE")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
