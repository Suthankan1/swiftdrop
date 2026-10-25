package com.swiftdrop.deliveryservice.assignment.client;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.CourierEligibilityRequest;
import com.swiftdrop.accounts.v1.CourierEligibilityResponse;
import com.swiftdrop.deliveryservice.assignment.CourierEligibilityClient;
import com.swiftdrop.deliveryservice.assignment.CourierEligibilityResult;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Production gRPC adapter connecting to Accounts Service to verify courier eligibility (IF-GRPC-01).
 *
 * Implements bounded request deadline (default 2000ms per SERVICE_INTERFACE_MATRIX.md)
 * and fail-closed safety semantics on timeout or provider unavailability.
 */
@Component
public class GrpcCourierEligibilityClient implements CourierEligibilityClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcCourierEligibilityClient.class);

    private final String grpcHost;
    private final int grpcPort;
    private final long deadlineMs;
    private final boolean clientEnabled;

    public GrpcCourierEligibilityClient(
            @Value("${swiftdrop.accounts.grpc.host:localhost}") String grpcHost,
            @Value("${swiftdrop.accounts.grpc.port:9091}") int grpcPort,
            @Value("${swiftdrop.accounts.grpc.deadline-ms:2000}") long deadlineMs,
            @Value("${swiftdrop.accounts.grpc.enabled:true}") boolean clientEnabled) {
        this.grpcHost = grpcHost;
        this.grpcPort = grpcPort;
        this.deadlineMs = deadlineMs;
        this.clientEnabled = clientEnabled;
    }

    @Override
    public CourierEligibilityResult checkEligibility(String courierSub) {
        if (!clientEnabled) {
            log.warn("Accounts gRPC client disabled; failing closed for courier {}", courierSub);
            return new CourierEligibilityResult(courierSub, false, false, "ELIGIBILITY_PROVIDER_DISABLED");
        }

        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder.forAddress(grpcHost, grpcPort)
                    .usePlaintext()
                    .build();

            AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                    AccountsInternalServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);

            CourierEligibilityRequest request = CourierEligibilityRequest.newBuilder()
                    .setCourierSub(courierSub)
                    .build();

            CourierEligibilityResponse response = stub.checkCourierEligibility(request);
            return new CourierEligibilityResult(
                    response.getCourierSub(),
                    response.getIsEligible(),
                    response.getIsActive(),
                    response.getRejectionReason()
            );
        } catch (StatusRuntimeException e) {
            log.error("gRPC call to CheckCourierEligibility failed for {}: {}", courierSub, e.getStatus());
            return new CourierEligibilityResult(courierSub, false, false, "PROVIDER_UNAVAILABLE: " + e.getStatus().getCode());
        } catch (Exception e) {
            log.error("Failed to connect to Accounts service for {}: {}", courierSub, e.getMessage());
            return new CourierEligibilityResult(courierSub, false, false, "PROVIDER_ERROR: " + e.getMessage());
        } finally {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
            }
        }
    }
}
