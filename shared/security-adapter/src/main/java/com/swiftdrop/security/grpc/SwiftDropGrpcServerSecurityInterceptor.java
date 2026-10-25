package com.swiftdrop.security.grpc;

import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.service.ServiceTokenValidator;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.*;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reusable gRPC server interceptor enforcing cryptographic service authentication,
 * method-level caller authorization, verified actor delegation, and tracing propagation.
 */
public class SwiftDropGrpcServerSecurityInterceptor implements ServerInterceptor {

    private static final Pattern SAFE_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final ServiceTokenValidator serviceTokenValidator;
    private final ActorDelegationValidator actorDelegationValidator;
    private final GrpcSecurityPolicy grpcSecurityPolicy;

    public SwiftDropGrpcServerSecurityInterceptor(
            ServiceTokenValidator serviceTokenValidator,
            ActorDelegationValidator actorDelegationValidator,
            GrpcSecurityPolicy grpcSecurityPolicy) {
        this.serviceTokenValidator = Objects.requireNonNull(serviceTokenValidator, "serviceTokenValidator must not be null");
        this.actorDelegationValidator = actorDelegationValidator != null ? actorDelegationValidator : new ActorDelegationValidator();
        this.grpcSecurityPolicy = grpcSecurityPolicy != null ? grpcSecurityPolicy : new GrpcSecurityPolicy();
    }

    public SwiftDropGrpcServerSecurityInterceptor(ServiceTokenValidator serviceTokenValidator) {
        this(serviceTokenValidator, new ActorDelegationValidator(), new GrpcSecurityPolicy());
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String fullMethodName = call.getMethodDescriptor().getFullMethodName();

        // 1. Sanitize or generate correlation ID
        String rawCorrelationId = headers.get(SwiftDropGrpcMetadata.KEY_CORRELATION_ID);
        String correlationId;
        if (rawCorrelationId != null && SAFE_CORRELATION_ID_PATTERN.matcher(rawCorrelationId.trim()).matches()) {
            correlationId = rawCorrelationId.trim();
        } else {
            correlationId = UUID.randomUUID().toString();
        }

        // 2. Validate calling service token
        String serviceToken = headers.get(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN);
        if (serviceToken == null || serviceToken.isBlank()) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing service authentication token"), headers);
            return new ServerCall.Listener<>() {};
        }

        ServiceTokenValidator.Result serviceAuthResult = serviceTokenValidator.validate(serviceToken);
        if (!serviceAuthResult.isValid()) {
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
                    call.close(Status.UNAUTHENTICATED.withDescription(
                            "Actor delegation required for " + fullMethodName + " but no actor subject provided"), headers);
                    return new ServerCall.Listener<>() {};
                }

                ActorDelegationValidator.Result delegationResult = actorDelegationValidator.validateDelegation(
                        actorSub, actorRoles, actorToken, serviceAuthResult.jwt(), callingService
                );

                if (!delegationResult.isValid()) {
                    call.close(Status.PERMISSION_DENIED.withDescription(
                            "Actor delegation rejected: " + delegationResult.errorMessage()), headers);
                    return new ServerCall.Listener<>() {};
                }

                delegatedActor = delegationResult.context();

                // Check actor role authorization
                if (!policy.isActorRoleAllowed(delegatedActor.actorRoles())) {
                    call.close(Status.PERMISSION_DENIED.withDescription(
                            "Delegated actor does not possess required role for " + fullMethodName), headers);
                    return new ServerCall.Listener<>() {};
                }

            } else if (policy.delegationRequirement() == GrpcSecurityPolicy.DelegationRequirement.FORBIDDEN) {
                // Service-only RPCs (e.g. background notification jobs or API key digest checks)
                // Caller must NOT pass synthetic human actor headers
                if (actorSub != null && !actorSub.isBlank()) {
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

        return Contexts.interceptCall(context, call, headers, next);
    }
}
