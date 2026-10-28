package com.swiftdrop.graphql.resolver;

import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.accounts.v1.AvailableCouriersSummaryRequest;
import com.swiftdrop.accounts.v1.AvailableCouriersSummaryResponse;
import com.swiftdrop.accounts.v1.CourierSummaryItem;
import com.swiftdrop.accounts.v1.ScopedProfileSummaryRequest;
import com.swiftdrop.accounts.v1.ScopedProfileSummaryResponse;
import com.swiftdrop.delivery.v1.CourierWorkloadResponse;
import com.swiftdrop.delivery.v1.CourierWorkloadSummaryRequest;
import com.swiftdrop.delivery.v1.CourierWorkloadSummaryResponse;
import com.swiftdrop.delivery.v1.ListUnassignedParcelsRequest;
import com.swiftdrop.delivery.v1.ListUnassignedParcelsResponse;
import com.swiftdrop.delivery.v1.UnassignedParcelSummary;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.limits.PaginationPolicy;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Production resolver for Query.dispatcherDashboard (Issue #135 / N10-WF16).
 *
 * Aggregates authorized unassigned parcel queues and available courier workloads
 * strictly for verified DISPATCHER and ADMIN identities via private internal gRPC reads.
 * Enforces operational scope, bounded pagination, batch workload reads (no N+1),
 * fail-closed security, and safe exception mapping.
 */
@Component("dispatcherDashboardResolver")
public class DispatcherDashboardResolver implements RootQueryResolver {

    private static final String FIELD_NAME = "dispatcherDashboard";
    private static final String ROLE_DISPATCHER = "DISPATCHER";
    private static final String ROLE_ADMIN = "ADMIN";

    private final AuthenticatedReadClients accountsClient;
    private final AuthenticatedReadClients deliveryClient;
    private final int maxCapacity;

    @Autowired
    public DispatcherDashboardResolver(
            @Autowired(required = false) @Qualifier("accountsReadClients") AuthenticatedReadClients accountsClient,
            @Autowired(required = false) @Qualifier("deliveryReadClients") AuthenticatedReadClients deliveryClient,
            @Value("${swiftdrop.graphql.dispatcher.courier-max-capacity:5}") int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be > 0: " + maxCapacity);
        }
        this.accountsClient = accountsClient;
        this.deliveryClient = deliveryClient;
        this.maxCapacity = maxCapacity;
    }

    // Package-private constructor for unit testing with synthetic test doubles
    DispatcherDashboardResolver(
            AuthenticatedReadClients accountsClient,
            AuthenticatedReadClients deliveryClient) {
        this(accountsClient, deliveryClient, 5);
    }

    @Override
    public String field() {
        return FIELD_NAME;
    }

    @Override
    public Object resolve(DataFetchingEnvironment environment) throws Exception {
        // 1. Identity & Actor Validation
        JwtAuthenticationToken actor = environment.getGraphQlContext().get("actor");
        if (actor == null || !actor.isAuthenticated() || actor.getToken() == null || actor.getToken().getSubject() == null) {
            throw Status.UNAUTHENTICATED.withDescription("Unauthenticated caller").asRuntimeException();
        }
        String actorSub = actor.getToken().getSubject().trim();
        if (actorSub.isBlank()) {
            throw Status.UNAUTHENTICATED.withDescription("Missing authenticated subject").asRuntimeException();
        }

        // 2. Role Gate
        boolean isDispatcher = actor.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + ROLE_DISPATCHER).equals(a.getAuthority()));
        boolean isAdmin = actor.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + ROLE_ADMIN).equals(a.getAuthority()));

        if (!isDispatcher && !isAdmin) {
            throw new AccessDeniedException("Caller does not have required role DISPATCHER or ADMIN");
        }

        // 3. Provider Availability Check
        if (accountsClient == null || deliveryClient == null) {
            throw Status.UNAVAILABLE.withDescription("Service provider unavailable").asRuntimeException();
        }

        // 4. Pagination Policy Validation
        PaginationPolicy pagination = PaginationPolicy.from(environment.getArgument("pagination"));
        int page = pagination.page();
        int size = pagination.size();

        // 5. Authoritative Operational Scope Resolution
        String assignedZone = "";
        if (isDispatcher) {
            // Verify Dispatcher Business Eligibility with Accounts
            AccountEligibilityRequest eligReq = AccountEligibilityRequest.newBuilder()
                    .setSubject(actorSub)
                    .setRequiredRole(ROLE_DISPATCHER)
                    .build();

            AccountEligibilityResponse eligRes = accountsClient.accounts(
                    environment.getGraphQlContext(),
                    stub -> stub.checkAccountEligibility(eligReq)
            );

            if (eligRes == null) {
                throw Status.UNAVAILABLE.withDescription("Accounts service returned null response").asRuntimeException();
            }

            if (!actorSub.equals(eligRes.getSubject())) {
                throw Status.PERMISSION_DENIED.withDescription("Subject mismatch in accounts eligibility response").asRuntimeException();
            }

            if (!eligRes.getIsEligible() || !eligRes.getIsActive() || !"ACTIVE".equalsIgnoreCase(eligRes.getStatus())) {
                throw Status.PERMISSION_DENIED.withDescription("Dispatcher is not active or eligible: " + eligRes.getRejectionReason()).asRuntimeException();
            }

            // Retrieve Authoritative Operational Zone from Staff Profile
            ScopedProfileSummaryRequest profileReq = ScopedProfileSummaryRequest.newBuilder()
                    .setSubject(actorSub)
                    .setScope("STAFF")
                    .build();

            ScopedProfileSummaryResponse profileRes = accountsClient.accounts(
                    environment.getGraphQlContext(),
                    stub -> stub.getScopedProfileSummary(profileReq)
            );

            if (profileRes == null) {
                throw Status.UNAVAILABLE.withDescription("Accounts service returned null profile response").asRuntimeException();
            }

            if (profileRes.getAssignedZone() != null) {
                assignedZone = profileRes.getAssignedZone().trim();
            }
        } else if (isAdmin) {
            // Admin with an assigned zone in staff profile is scoped; otherwise platform-wide
            try {
                ScopedProfileSummaryRequest profileReq = ScopedProfileSummaryRequest.newBuilder()
                        .setSubject(actorSub)
                        .setScope("STAFF")
                        .build();

                ScopedProfileSummaryResponse profileRes = accountsClient.accounts(
                        environment.getGraphQlContext(),
                        stub -> stub.getScopedProfileSummary(profileReq)
                );

                if (profileRes != null && profileRes.getAssignedZone() != null) {
                    assignedZone = profileRes.getAssignedZone().trim();
                }
            } catch (StatusRuntimeException ex) {
                if (ex.getStatus().getCode() != Status.Code.NOT_FOUND) {
                    throw ex;
                }
                assignedZone = "";
            }
        }

        // 6. Accounts: Fetch Available Couriers Summary (Bounded Preview: ceiling 50)
        AvailableCouriersSummaryRequest couriersReq = AvailableCouriersSummaryRequest.newBuilder()
                .setZoneFilter(assignedZone)
                .setPage(0)
                .setPageSize(PaginationPolicy.MAX_SIZE)
                .build();

        AvailableCouriersSummaryResponse couriersRes = accountsClient.accounts(
                environment.getGraphQlContext(),
                stub -> stub.getAvailableCouriersSummary(couriersReq)
        );

        if (couriersRes == null) {
            throw Status.UNAVAILABLE.withDescription("Accounts service returned null couriers response").asRuntimeException();
        }

        if (couriersRes.getItemsCount() > PaginationPolicy.MAX_SIZE) {
            throw Status.INTERNAL.withDescription("Accounts provider returned oversized couriers list: " + couriersRes.getItemsCount()).asRuntimeException();
        }

        Set<String> seenCourierSubs = new HashSet<>();
        List<CourierSummaryItem> eligibleCouriers = new ArrayList<>();

        for (CourierSummaryItem item : couriersRes.getItemsList()) {
            String sub = item.getCourierSub() != null ? item.getCourierSub().trim() : "";
            if (sub.isBlank()) {
                throw Status.INTERNAL.withDescription("Accounts provider returned blank courierSub").asRuntimeException();
            }
            if (!seenCourierSubs.add(sub)) {
                throw Status.INTERNAL.withDescription("Accounts provider returned duplicate courierSub: " + sub).asRuntimeException();
            }

            String fullName = item.getFullName() != null ? item.getFullName().trim() : "";
            if (fullName.isBlank()) {
                throw Status.INTERNAL.withDescription("Accounts provider returned blank fullName for courier: " + sub).asRuntimeException();
            }

            // Cross-scope protection: Courier assigned zone must match dispatcher operational scope
            if (!assignedZone.isBlank() && !item.getAssignedZone().isBlank() && !assignedZone.equalsIgnoreCase(item.getAssignedZone().trim())) {
                throw Status.PERMISSION_DENIED.withDescription("Courier assigned zone outside dispatcher operational scope: " + item.getAssignedZone()).asRuntimeException();
            }

            // Exclude inactive and ineligible couriers from selection list
            if (item.getIsActive() && item.getIsEligible()) {
                eligibleCouriers.add(item);
            }
        }

        // 7. Delivery: Batch Workload Read (Single Bounded gRPC Request, No N+1)
        Map<String, Integer> workloadCounts = new HashMap<>();
        if (!eligibleCouriers.isEmpty()) {
            List<String> courierSubs = eligibleCouriers.stream()
                    .map(CourierSummaryItem::getCourierSub)
                    .map(String::trim)
                    .toList();

            CourierWorkloadSummaryRequest workloadReq = CourierWorkloadSummaryRequest.newBuilder()
                    .addAllCourierSubs(courierSubs)
                    .build();

            CourierWorkloadSummaryResponse workloadRes = deliveryClient.delivery(
                    environment.getGraphQlContext(),
                    stub -> stub.getCourierWorkloadSummary(workloadReq)
            );

            if (workloadRes == null) {
                throw Status.UNAVAILABLE.withDescription("Delivery service returned null workload response").asRuntimeException();
            }

            for (CourierWorkloadResponse w : workloadRes.getWorkloadsList()) {
                String sub = w.getCourierSub() != null ? w.getCourierSub().trim() : "";
                if (sub.isBlank()) {
                    throw Status.INTERNAL.withDescription("Delivery provider returned blank courierSub in workload").asRuntimeException();
                }
                if (workloadCounts.put(sub, w.getActiveAssignmentsCount()) != null) {
                    throw Status.INTERNAL.withDescription("Delivery provider returned duplicate courierSub in workload: " + sub).asRuntimeException();
                }
                if (!courierSubs.contains(sub)) {
                    throw Status.INTERNAL.withDescription("Delivery provider returned unknown courierSub in workload: " + sub).asRuntimeException();
                }
                if (w.getActiveAssignmentsCount() < 0) {
                    throw Status.INTERNAL.withDescription("Delivery provider returned negative workload count for: " + sub).asRuntimeException();
                }
            }

            for (String sub : courierSubs) {
                if (!workloadCounts.containsKey(sub)) {
                    throw Status.INTERNAL.withDescription("Delivery provider omitted requested courier from workload: " + sub).asRuntimeException();
                }
            }
        }

        List<CourierWorkloadSummary> availableCouriers = new ArrayList<>(eligibleCouriers.size());
        for (CourierSummaryItem c : eligibleCouriers) {
            int activeCount = workloadCounts.get(c.getCourierSub().trim());
            availableCouriers.add(new CourierWorkloadSummary(
                    c.getCourierSub().trim(),
                    c.getFullName().trim(),
                    activeCount,
                    maxCapacity,
                    true
            ));
        }

        // 8. Delivery: List Unassigned Parcels
        ListUnassignedParcelsRequest parcelReq = ListUnassignedParcelsRequest.newBuilder()
                .setPage(page)
                .setPageSize(size)
                .setZone(assignedZone)
                .build();

        ListUnassignedParcelsResponse parcelRes = deliveryClient.delivery(
                environment.getGraphQlContext(),
                stub -> stub.listUnassignedParcels(parcelReq)
        );

        if (parcelRes == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery service returned null unassigned parcels response").asRuntimeException();
        }

        int totalElements = parcelRes.getTotalCount();
        if (totalElements < 0) {
            throw Status.INTERNAL.withDescription("Delivery provider returned negative total parcel count").asRuntimeException();
        }
        if (parcelRes.getItemsCount() > size) {
            throw Status.INTERNAL.withDescription("Delivery provider returned more items than requested page size").asRuntimeException();
        }

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        boolean hasNext = (page + 1) < totalPages;
        PageInfo pageInfo = new PageInfo(page, size, totalElements, totalPages, hasNext);

        // 9. Map and Validate Unassigned Parcels
        Set<String> seenParcelIds = new HashSet<>();
        List<UnassignedParcelItem> unassignedParcelItems = new ArrayList<>(parcelRes.getItemsCount());

        for (UnassignedParcelSummary p : parcelRes.getItemsList()) {
            String parcelId = p.getParcelId() != null ? p.getParcelId().trim() : "";
            if (parcelId.isBlank()) {
                throw Status.INTERNAL.withDescription("Missing parcel ID").asRuntimeException();
            }
            try {
                UUID.fromString(parcelId);
            } catch (IllegalArgumentException e) {
                throw Status.INTERNAL.withDescription("Malformed parcel ID UUID: " + parcelId).asRuntimeException();
            }
            if (!seenParcelIds.add(parcelId)) {
                throw Status.INTERNAL.withDescription("Duplicate parcel ID returned from provider: " + parcelId).asRuntimeException();
            }

            String trackingNumber = p.getTrackingNumber() != null ? p.getTrackingNumber().trim() : "";
            if (trackingNumber.isBlank()) {
                throw Status.INTERNAL.withDescription("Missing tracking number for parcel: " + parcelId).asRuntimeException();
            }

            String status = p.getStatus() != null ? p.getStatus().trim() : "";
            if (!"CREATED".equalsIgnoreCase(status)) {
                throw Status.INTERNAL.withDescription("Unassigned parcel has non-CREATED status: " + status).asRuntimeException();
            }

            String pickupZone = p.getPickupZone() != null ? p.getPickupZone().trim() : "";
            if (pickupZone.isBlank()) {
                throw Status.INTERNAL.withDescription("Missing authoritative pickup zone for parcel: " + parcelId).asRuntimeException();
            }

            String dropoffZone = p.getDropoffZone() != null ? p.getDropoffZone().trim() : "";
            if (dropoffZone.isBlank()) {
                throw Status.INTERNAL.withDescription("Missing authoritative dropoff zone for parcel: " + parcelId).asRuntimeException();
            }

            // Cross-scope protection: Scoped dispatcher must only see parcels matching their operational zone
            if (!assignedZone.isBlank() && !assignedZone.equalsIgnoreCase(pickupZone) && !assignedZone.equalsIgnoreCase(dropoffZone)) {
                throw Status.PERMISSION_DENIED.withDescription("Parcel outside dispatcher operational scope: " + parcelId).asRuntimeException();
            }

            double weightKg = p.getWeightKg();
            if (!Double.isFinite(weightKg) || weightKg <= 0.0) {
                throw Status.INTERNAL.withDescription("Invalid weight for parcel " + parcelId + ": " + weightKg).asRuntimeException();
            }

            String sizeCategory = p.getSizeCategory() != null ? p.getSizeCategory().trim() : "";
            if (sizeCategory.isBlank()) {
                throw Status.INTERNAL.withDescription("Authoritative sizeCategory missing from provider for parcel: " + parcelId).asRuntimeException();
            }

            if (!p.hasCreatedAt() || p.getCreatedAt().getSeconds() <= 0) {
                throw Status.INTERNAL.withDescription("Missing or invalid createdAt timestamp for parcel: " + parcelId).asRuntimeException();
            }
            Instant createdInstant = Instant.ofEpochSecond(p.getCreatedAt().getSeconds(), p.getCreatedAt().getNanos());
            String createdAtStr = createdInstant.toString();

            int version;
            try {
                version = Math.toIntExact(p.getVersion());
                if (version < 0) {
                    throw Status.INTERNAL.withDescription("Invalid negative parcel version: " + version).asRuntimeException();
                }
            } catch (ArithmeticException e) {
                throw Status.INTERNAL.withDescription("Parcel version overflows GraphQL Int: " + p.getVersion()).asRuntimeException();
            }

            unassignedParcelItems.add(new UnassignedParcelItem(
                    parcelId,
                    trackingNumber,
                    pickupZone,
                    dropoffZone,
                    weightKg,
                    sizeCategory,
                    createdAtStr,
                    version
            ));
        }

        // 10. Construct Composite Dashboard Result
        return new DispatcherDashboard(
                unassignedParcelItems,
                pageInfo,
                availableCouriers
        );
    }
}
