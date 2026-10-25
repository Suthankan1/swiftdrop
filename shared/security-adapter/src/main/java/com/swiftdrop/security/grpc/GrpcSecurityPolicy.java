package com.swiftdrop.security.grpc;

import com.swiftdrop.security.jwt.SwiftDropRoles;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative security and delegation policy register for SwiftDrop internal gRPC interfaces.
 * Conforms to Architecture Baseline v1.3 and docs/contracts/SERVICE_INTERFACE_MATRIX.md.
 */
public class GrpcSecurityPolicy {

    public enum DelegationRequirement {
        REQUIRED,
        FORBIDDEN, // Service-only calls (background jobs)
        OPTIONAL
    }

    public record MethodPolicy(
            String fullMethodName,
            Set<SwiftDropServiceIdentity> allowedCallers,
            DelegationRequirement delegationRequirement,
            Set<String> requiredActorRoles
    ) {
        public boolean isCallerAllowed(SwiftDropServiceIdentity caller) {
            return allowedCallers.contains(caller);
        }

        public boolean isActorRoleAllowed(Set<String> actorRoles) {
            if (requiredActorRoles.isEmpty()) {
                return true;
            }
            if (actorRoles == null || actorRoles.isEmpty()) {
                return false;
            }
            return actorRoles.stream().anyMatch(requiredActorRoles::contains);
        }
    }

    private final Map<String, MethodPolicy> policyMap = new ConcurrentHashMap<>();

    public GrpcSecurityPolicy() {
        registerBaselinePolicies();
    }

    private void registerBaselinePolicies() {
        // IF-GRPC-01: Delivery / GraphQL -> Accounts CheckCourierEligibility (Actor: COURIER, DISPATCHER, ADMIN)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/CheckCourierEligibility",
                Set.of(SwiftDropServiceIdentity.DELIVERY_SERVICE, SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_COURIER, SwiftDropRoles.ROLE_DISPATCHER, SwiftDropRoles.ROLE_ADMIN)
        ));

        // IF-GRPC-02: Delivery -> Accounts ValidateMerchantKeyDigest (Service-only)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/ValidateMerchantKeyDigest",
                Set.of(SwiftDropServiceIdentity.DELIVERY_SERVICE),
                DelegationRequirement.FORBIDDEN,
                Collections.emptySet()
        ));

        // IF-GRPC-03: Communications -> Accounts ResolveRecipientContact (Service-only, background job)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/ResolveRecipientContact",
                Set.of(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE),
                DelegationRequirement.FORBIDDEN,
                Collections.emptySet()
        ));

        // IF-GRPC-06: GraphQL -> Accounts GetPendingMerchantsSummary (Actor: ADMIN only)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/GetPendingMerchantsSummary",
                Set.of(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_ADMIN)
        ));

        // Accounts CheckAccountEligibility (Actor: CUSTOMER, MERCHANT, COURIER, DISPATCHER, ADMIN, #109)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/CheckAccountEligibility",
                Set.of(SwiftDropServiceIdentity.DELIVERY_SERVICE, SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_CUSTOMER, SwiftDropRoles.ROLE_MERCHANT, SwiftDropRoles.ROLE_COURIER,
                        SwiftDropRoles.ROLE_DISPATCHER, SwiftDropRoles.ROLE_ADMIN)
        ));

        // Accounts GetScopedProfileSummary (Actor: CUSTOMER, MERCHANT, COURIER, DISPATCHER, ADMIN, #109)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/GetScopedProfileSummary",
                Set.of(SwiftDropServiceIdentity.DELIVERY_SERVICE, SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_CUSTOMER, SwiftDropRoles.ROLE_MERCHANT, SwiftDropRoles.ROLE_COURIER,
                        SwiftDropRoles.ROLE_DISPATCHER, SwiftDropRoles.ROLE_ADMIN)
        ));

        // Accounts GetBatchProfileSummaries (Actor: DISPATCHER, ADMIN, #109)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/GetBatchProfileSummaries",
                Set.of(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_DISPATCHER, SwiftDropRoles.ROLE_ADMIN)
        ));

        // Accounts GetAvailableCouriersSummary (Actor: DISPATCHER, ADMIN, #109, #135)
        register(new MethodPolicy(
                "swiftdrop.accounts.v1.AccountsInternalService/GetAvailableCouriersSummary",
                Set.of(SwiftDropServiceIdentity.DELIVERY_SERVICE, SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_DISPATCHER, SwiftDropRoles.ROLE_ADMIN)
        ));

        // IF-GRPC-04: Delivery -> Pricing ValidateQuote (Actor: CUSTOMER, MERCHANT, ADMIN)
        register(new MethodPolicy(
                "swiftdrop.pricing.v1.PricingInternalService/ValidateQuote",
                Set.of(SwiftDropServiceIdentity.DELIVERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_CUSTOMER, SwiftDropRoles.ROLE_MERCHANT, SwiftDropRoles.ROLE_ADMIN)
        ));

        // IF-GRPC-05: GraphQL -> Delivery GetParcelInternal (Actor: CUSTOMER, COURIER, DISPATCHER, ADMIN)
        register(new MethodPolicy(
                "swiftdrop.delivery.v1.DeliveryInternalService/GetParcelInternal",
                Set.of(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_CUSTOMER, SwiftDropRoles.ROLE_COURIER, SwiftDropRoles.ROLE_DISPATCHER, SwiftDropRoles.ROLE_ADMIN)
        ));

        // Delivery GetCourierWorkload: GraphQL -> Delivery (Actor: DISPATCHER, ADMIN)
        register(new MethodPolicy(
                "swiftdrop.delivery.v1.DeliveryInternalService/GetCourierWorkload",
                Set.of(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE),
                DelegationRequirement.REQUIRED,
                Set.of(SwiftDropRoles.ROLE_DISPATCHER, SwiftDropRoles.ROLE_ADMIN)
        ));
    }

    public void register(MethodPolicy policy) {
        policyMap.put(policy.fullMethodName(), policy);
    }

    public Optional<MethodPolicy> getPolicy(String fullMethodName) {
        return Optional.ofNullable(policyMap.get(fullMethodName));
    }
}
