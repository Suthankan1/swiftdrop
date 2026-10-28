package com.swiftdrop.security.grpc;

import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.correlation.CorrelationContext;
import com.swiftdrop.security.jwt.KeycloakRoleExtractor;
import com.swiftdrop.security.jwt.SwiftDropPrincipal;
import com.swiftdrop.security.service.ServiceTokenProvider;
import io.grpc.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Reusable gRPC client interceptor that attaches service credentials, propagates verified actor delegation,
 * preserves distributed correlation identifiers across HTTP and gRPC hops, enforces bounded execution deadlines,
 * and records bounded operational metrics.
 */
public class SwiftDropGrpcClientInterceptor implements ClientInterceptor {

    public static final long DEFAULT_DEADLINE_MS = SwiftDropGrpcMetadata.DEFAULT_DEADLINE_MS;

    private final ServiceTokenProvider serviceTokenProvider;
    private final long defaultDeadlineMs;
    private final MeterRegistry meterRegistry;
    private final String clientServiceName;

    public SwiftDropGrpcClientInterceptor(
            ServiceTokenProvider serviceTokenProvider,
            long defaultDeadlineMs,
            MeterRegistry meterRegistry,
            String clientServiceName) {
        this.serviceTokenProvider = Objects.requireNonNull(serviceTokenProvider, "serviceTokenProvider must not be null");
        this.defaultDeadlineMs = defaultDeadlineMs > 0 ? defaultDeadlineMs : DEFAULT_DEADLINE_MS;
        this.meterRegistry = meterRegistry;
        this.clientServiceName = clientServiceName;
    }

    public SwiftDropGrpcClientInterceptor(
            ServiceTokenProvider serviceTokenProvider,
            long defaultDeadlineMs,
            MeterRegistry meterRegistry) {
        this(serviceTokenProvider, defaultDeadlineMs, meterRegistry, null);
    }

    public SwiftDropGrpcClientInterceptor(ServiceTokenProvider serviceTokenProvider, long defaultDeadlineMs) {
        this(serviceTokenProvider, defaultDeadlineMs, null, null);
    }

    public SwiftDropGrpcClientInterceptor(ServiceTokenProvider serviceTokenProvider) {
        this(serviceTokenProvider, DEFAULT_DEADLINE_MS, null, null);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        // 1. Enforce bounded deadline (respecting earlier upstream deadlines)
        Deadline configuredDeadline = Deadline.after(defaultDeadlineMs, TimeUnit.MILLISECONDS);
        Deadline existingDeadline = callOptions.getDeadline();
        CallOptions boundedOptions;

        if (existingDeadline == null) {
            boundedOptions = callOptions.withDeadline(configuredDeadline);
        } else if (existingDeadline.isBefore(configuredDeadline)) {
            // Upstream deadline is shorter: preserve shorter deadline
            boundedOptions = callOptions;
        } else {
            // Our bounded timeout is shorter than upstream: apply stricter bound
            boundedOptions = callOptions.withDeadline(configuredDeadline);
        }

        long startTimeNanos = System.nanoTime();

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, boundedOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                // 2. Inject service authentication token
                String serviceToken = serviceTokenProvider.getServiceToken();
                if (serviceToken != null && !serviceToken.isBlank()) {
                    headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
                }

                // 3. Inject or preserve correlation ID (bridge SwiftDropGrpcContext and CorrelationContext)
                String existingCorrelation = headers.get(SwiftDropGrpcMetadata.KEY_CORRELATION_ID);
                if (existingCorrelation == null || existingCorrelation.isBlank()) {
                    String correlationId = SwiftDropGrpcContext.currentCorrelationId()
                            .or(CorrelationContext::get)
                            .orElseGet(() -> UUID.randomUUID().toString());
                    headers.put(SwiftDropGrpcMetadata.KEY_CORRELATION_ID, correlationId);
                }

                // 4. Inject verified actor delegation if present
                attachActorDelegation(headers);

                // 5. Wrap responseListener to observe completion and failure metrics
                Listener<RespT> metricsListener = new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        recordClientMetrics(method.getFullMethodName(), status, System.nanoTime() - startTimeNanos);
                        super.onClose(status, trailers);
                    }
                };

                super.start(metricsListener, headers);
            }
        };
    }

    private void recordClientMetrics(String fullMethodName, Status status, long durationNanos) {
        try {
            MeterRegistry registry = (this.meterRegistry != null) ? this.meterRegistry : Metrics.globalRegistry;
            if (registry == null) {
                return;
            }
            String svc = (clientServiceName != null && !clientServiceName.isBlank()) ? clientServiceName : "client";
            String methodTag = sanitizeMethodName(fullMethodName);
            String statusTag = (status != null && status.getCode() != null) ? status.getCode().name() : "UNKNOWN";

            Timer.builder("swiftdrop.grpc.client.duration")
                    .description("Duration of outbound gRPC client calls")
                    .tag("service", svc)
                    .tag("method", methodTag)
                    .tag("status", statusTag)
                    .register(registry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);

            Counter.builder("swiftdrop.grpc.client.requests")
                    .description("Total count of outbound gRPC client requests")
                    .tag("service", svc)
                    .tag("method", methodTag)
                    .tag("status", statusTag)
                    .register(registry)
                    .increment();

            if (status != null && !status.isOk()) {
                Counter.builder("swiftdrop.grpc.client.errors")
                        .description("Total count of outbound gRPC client errors")
                        .tag("service", svc)
                        .tag("method", methodTag)
                        .tag("status", statusTag)
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

    private void attachActorDelegation(Metadata headers) {
        // A. Check explicit gRPC context first
        Optional<DelegatedActorContext> contextActor = SwiftDropGrpcContext.currentDelegatedActor();
        if (contextActor.isPresent()) {
            DelegatedActorContext actor = contextActor.get();
            headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actor.actorSub());
            if (!actor.actorRoles().isEmpty()) {
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, String.join(",", actor.actorRoles()));
            }
            return;
        }

        // B. Check Spring Security context
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String actorSub = auth.getName();
            Set<String> roles = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
            if (!roles.isEmpty()) {
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, String.join(",", roles));
            }

            // If credentials contain the underlying bearer JWT, attach it for cryptographic verification
            if (auth.getCredentials() instanceof Jwt jwt) {
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, jwt.getTokenValue());
            } else if (auth.getPrincipal() instanceof SwiftDropPrincipal principal) {
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, principal.subject());
                if (!principal.roles().isEmpty()) {
                    headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, String.join(",", principal.roles()));
                }
            }
        }
    }
}
