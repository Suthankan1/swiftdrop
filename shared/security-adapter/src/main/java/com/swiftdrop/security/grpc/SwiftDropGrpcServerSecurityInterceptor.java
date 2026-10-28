package com.swiftdrop.security.grpc;

import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.correlation.CorrelationContext;
import com.swiftdrop.security.service.ServiceTokenValidator;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Reusable gRPC server interceptor enforcing cryptographic service authentication,
 * method-level caller authorization, verified actor delegation, tracing propagation,
 * and bounded operational server metrics.
 */
public class SwiftDropGrpcServerSecurityInterceptor implements ServerInterceptor {

    private static final Pattern SAFE_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final ServiceTokenValidator serviceTokenValidator;
    private final ActorDelegationValidator actorDelegationValidator;
    private final GrpcSecurityPolicy grpcSecurityPolicy;
    private final MeterRegistry meterRegistry;
    private final String serverServiceName;

    public SwiftDropGrpcServerSecurityInterceptor(
            ServiceTokenValidator serviceTokenValidator,
            ActorDelegationValidator actorDelegationValidator,
            GrpcSecurityPolicy grpcSecurityPolicy,
            MeterRegistry meterRegistry,
            String serverServiceName) {
        this.serviceTokenValidator = Objects.requireNonNull(serviceTokenValidator, "serviceTokenValidator must not be null");
        this.actorDelegationValidator = actorDelegationValidator != null ? actorDelegationValidator : new ActorDelegationValidator();
        this.grpcSecurityPolicy = grpcSecurityPolicy != null ? grpcSecurityPolicy : new GrpcSecurityPolicy();
        this.meterRegistry = meterRegistry;
        this.serverServiceName = serverServiceName;
    }

    public SwiftDropGrpcServerSecurityInterceptor(
            ServiceTokenValidator serviceTokenValidator,
            ActorDelegationValidator actorDelegationValidator,
            GrpcSecurityPolicy grpcSecurityPolicy,
            MeterRegistry meterRegistry) {
        this(serviceTokenValidator, actorDelegationValidator, grpcSecurityPolicy, meterRegistry, null);
    }

    public SwiftDropGrpcServerSecurityInterceptor(
            ServiceTokenValidator serviceTokenValidator,
            ActorDelegationValidator actorDelegationValidator,
            GrpcSecurityPolicy grpcSecurityPolicy) {
        this(serviceTokenValidator, actorDelegationValidator, grpcSecurityPolicy, null, null);
    }

    public SwiftDropGrpcServerSecurityInterceptor(ServiceTokenValidator serviceTokenValidator) {
        this(serviceTokenValidator, new ActorDelegationValidator(), new GrpcSecurityPolicy(), null, null);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String fullMethodName = call.getMethodDescriptor().getFullMethodName();
        long startTimeNanos = System.nanoTime();

        // 1. Sanitize or generate correlation ID using unified CorrelationContext policy
        String rawCorrelationId = headers.get(SwiftDropGrpcMetadata.KEY_CORRELATION_ID);
        String correlationId = CorrelationContext.sanitizeOrGenerate(rawCorrelationId);

        // 2. Validate calling service token
        String serviceToken = headers.get(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN);
        if (serviceToken == null || serviceToken.isBlank()) {
            recordServerMetrics(fullMethodName, Status.UNAUTHENTICATED, null, System.nanoTime() - startTimeNanos);
            call.close(Status.UNAUTHENTICATED.withDescription("Missing service authentication token"), headers);
            return new ServerCall.Listener<>() {};
        }

        ServiceTokenValidator.Result serviceAuthResult = serviceTokenValidator.validate(serviceToken);
        if (!serviceAuthResult.isValid()) {
            recordServerMetrics(fullMethodName, Status.UNAUTHENTICATED, null, System.nanoTime() - startTimeNanos);
            call.close(Status.UNAUTHENTICATED.withDescription(serviceAuthResult.errorMessage()), headers);
            return new ServerCall.Listener<>() {};
        }

        SwiftDropServiceIdentity callingService = serviceAuthResult.serviceIdentity();

        // 3. Evaluate method-level authorization & delegation policy
        DelegatedActorContext delegatedActor = null;
        var optionalPolicy = grpcSecurityPolicy.getPolicy(fullMethodName);

        if (optionalPolicy.isPresent()) {
            GrpcSecurityPolicy.MethodPolicy policy = optionalPolicy.get();

            // A. Check caller authorization
            if (!policy.isCallerAllowed(callingService)) {
                recordServerMetrics(fullMethodName, Status.PERMISSION_DENIED, callingService, System.nanoTime() - startTimeNanos);
                call.close(Status.PERMISSION_DENIED.withDescription(
                        "Calling service '" + callingService.getServiceName() +
                                "' is not authorized to invoke " + fullMethodName), headers);
                return new ServerCall.Listener<>() {};
            }

            String actorSub = headers.get(SwiftDropGrpcMetadata.KEY_ACTOR_SUB);
            String actorRoles = headers.get(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES);
            String actorToken = headers.get(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN);

            // B. Enforce delegation requirements
            if (policy.delegationRequirement() == GrpcSecurityPolicy.DelegationRequirement.REQUIRED) {
                if (actorSub == null || actorSub.isBlank()) {
                    recordServerMetrics(fullMethodName, Status.UNAUTHENTICATED, callingService, System.nanoTime() - startTimeNanos);
                    call.close(Status.UNAUTHENTICATED.withDescription(
                            "Actor delegation required for " + fullMethodName + " but no actor subject provided"), headers);
                    return new ServerCall.Listener<>() {};
                }

                ActorDelegationValidator.Result delegationResult = actorDelegationValidator.validateDelegation(
                        actorSub, actorRoles, actorToken, serviceAuthResult.jwt(), callingService
                );

                if (!delegationResult.isValid()) {
                    recordServerMetrics(fullMethodName, Status.PERMISSION_DENIED, callingService, System.nanoTime() - startTimeNanos);
                    call.close(Status.PERMISSION_DENIED.withDescription(
                            "Actor delegation rejected: " + delegationResult.errorMessage()), headers);
                    return new ServerCall.Listener<>() {};
                }

                delegatedActor = delegationResult.context();

                // Check actor role authorization
                if (!policy.isActorRoleAllowed(delegatedActor.actorRoles())) {
                    recordServerMetrics(fullMethodName, Status.PERMISSION_DENIED, callingService, System.nanoTime() - startTimeNanos);
                    call.close(Status.PERMISSION_DENIED.withDescription(
                            "Delegated actor does not possess required role for " + fullMethodName), headers);
                    return new ServerCall.Listener<>() {};
                }

            } else if (policy.delegationRequirement() == GrpcSecurityPolicy.DelegationRequirement.FORBIDDEN) {
                // Service-only RPCs (e.g. background notification jobs or API key digest checks)
                // Caller must NOT pass synthetic human actor headers
                if (actorSub != null && !actorSub.isBlank()) {
                    recordServerMetrics(fullMethodName, Status.PERMISSION_DENIED, callingService, System.nanoTime() - startTimeNanos);
                    call.close(Status.PERMISSION_DENIED.withDescription(
                            "Actor delegation is forbidden on service-only operation " + fullMethodName), headers);
                    return new ServerCall.Listener<>() {};
                }
            } else if (actorSub != null && !actorSub.isBlank()) {
                // OPTIONAL delegation: if provided, it MUST be validly verified
                ActorDelegationValidator.Result delegationResult = actorDelegationValidator.validateDelegation(
                        actorSub, actorRoles, actorToken, serviceAuthResult.jwt(), callingService
                );
                if (!delegationResult.isValid()) {
                    recordServerMetrics(fullMethodName, Status.PERMISSION_DENIED, callingService, System.nanoTime() - startTimeNanos);
                    call.close(Status.PERMISSION_DENIED.withDescription(
                            "Actor delegation rejected: " + delegationResult.errorMessage()), headers);
                    return new ServerCall.Listener<>() {};
                }
                delegatedActor = delegationResult.context();
            }
        }

        // 4. Bind request context to thread-isolated io.grpc.Context
        Context context = Context.current()
                .withValue(SwiftDropGrpcContext.KEY_CALLING_SERVICE, callingService)
                .withValue(SwiftDropGrpcContext.KEY_DELEGATED_ACTOR, delegatedActor)
                .withValue(SwiftDropGrpcContext.KEY_CORRELATION_ID, correlationId);

        // 5. Wrap call to intercept close and record execution metrics
        ServerCall<ReqT, RespT> metricsCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                recordServerMetrics(fullMethodName, status, callingService, System.nanoTime() - startTimeNanos);
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> delegate = Contexts.interceptCall(context, metricsCall, headers, next);

        // 6. Wrap listener to propagate CorrelationContext into current thread during processing
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                CorrelationContext.runWith(correlationId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                CorrelationContext.runWith(correlationId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                CorrelationContext.runWith(correlationId, super::onCancel);
            }

            @Override
            public void onComplete() {
                CorrelationContext.runWith(correlationId, super::onComplete);
            }

            @Override
            public void onReady() {
                CorrelationContext.runWith(correlationId, super::onReady);
            }
        };
    }

    private void recordServerMetrics(String fullMethodName, Status status, SwiftDropServiceIdentity callingService, long durationNanos) {
        try {
            MeterRegistry registry = (this.meterRegistry != null) ? this.meterRegistry : Metrics.globalRegistry;
            if (registry == null) {
                return;
            }
            String caller = (callingService != null) ? callingService.getServiceName() : "unknown";
            String svc = (serverServiceName != null && !serverServiceName.isBlank()) ? serverServiceName : "server";
            String methodTag = sanitizeMethodName(fullMethodName);
            String statusTag = (status != null && status.getCode() != null) ? status.getCode().name() : "UNKNOWN";

            Timer.builder("swiftdrop.grpc.server.duration")
                    .description("Duration of incoming gRPC server calls")
                    .tag("service", svc)
                    .tag("method", methodTag)
                    .tag("status", statusTag)
                    .tag("calling_service", caller)
                    .register(registry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);

            Counter.builder("swiftdrop.grpc.server.requests")
                    .description("Total count of incoming gRPC server requests")
                    .tag("service", svc)
                    .tag("method", methodTag)
                    .tag("status", statusTag)
                    .tag("calling_service", caller)
                    .register(registry)
                    .increment();

            if (status != null && !status.isOk()) {
                Counter.builder("swiftdrop.grpc.server.errors")
                        .description("Total count of incoming gRPC server errors")
                        .tag("service", svc)
                        .tag("method", methodTag)
                        .tag("status", statusTag)
                        .tag("calling_service", caller)
                        .register(registry)
                        .increment();
            }
        } catch (Exception ignored) {
            // Metrics collection must never disrupt primary business logic
        }
    }

    private String sanitizeMethodName(String fullMethodName) {
        if (fullMethodName == null) {
            return "unknown";
        }
        int lastSlash = fullMethodName.lastIndexOf('/');
        if (lastSlash != -1) {
            int serviceDot = fullMethodName.lastIndexOf('.', lastSlash);
            if (serviceDot != -1) {
                return fullMethodName.substring(serviceDot + 1);
            }
        }
        return fullMethodName;
    }
}
