package com.swiftdrop.graphql.resolver;

import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.delivery.v1.CourierAssignmentSummary;
import com.swiftdrop.delivery.v1.ListCourierAssignmentsRequest;
import com.swiftdrop.delivery.v1.ListCourierAssignmentsResponse;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.limits.PaginationPolicy;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Production resolver for Query.courierDashboard (Issue #134 / N10-WF11).
 * Resolves operational dashboard data strictly for the verified authenticated Courier identity.
 * Reads eligibility from Accounts gRPC and bounded assignments from Delivery gRPC.
 */
@Component
public class CourierDashboardResolver implements RootQueryResolver {

    private static final String FIELD_NAME = "courierDashboard";
    private static final String REQUIRED_ROLE = "COURIER";
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<String> VALID_PARCEL_STATUSES = Set.of(
            "CREATED",
            "PICKED_UP",
            "IN_TRANSIT",
            "OUT_FOR_DELIVERY",
            "DELIVERED",
            "FAILED"
    );

    private final AuthenticatedReadClients accountsClient;
    private final AuthenticatedReadClients deliveryClient;

    public CourierDashboardResolver(
            @Autowired(required = false) @Qualifier("accountsReadClients") AuthenticatedReadClients accountsClient,
            @Autowired(required = false) @Qualifier("deliveryReadClients") AuthenticatedReadClients deliveryClient) {
        this.accountsClient = accountsClient;
        this.deliveryClient = deliveryClient;
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

        boolean hasCourierRole = actor.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + REQUIRED_ROLE).equals(a.getAuthority()));
        if (!hasCourierRole) {
            throw new AccessDeniedException("Caller does not have ROLE_COURIER");
        }

        // 2. Provider availability check
        if (accountsClient == null || deliveryClient == null) {
            throw Status.UNAVAILABLE.withDescription("Service provider unavailable").asRuntimeException();
        }

        // 3. Accounts Eligibility Verification
        AccountEligibilityRequest accountsReq = AccountEligibilityRequest.newBuilder()
                .setSubject(actorSub)
                .setRequiredRole(REQUIRED_ROLE)
                .build();

        AccountEligibilityResponse accountsRes = accountsClient.accounts(
                environment.getGraphQlContext(),
                stub -> stub.checkAccountEligibility(accountsReq)
        );

        if (accountsRes == null) {
            throw Status.UNAVAILABLE.withDescription("Accounts service returned null response").asRuntimeException();
        }

        // Anti-spoofing check: Accounts response subject must match the JWT subject
        if (!actorSub.equals(accountsRes.getSubject())) {
            throw Status.PERMISSION_DENIED.withDescription("Subject mismatch in accounts eligibility response").asRuntimeException();
        }

        // Active Courier check: Must be eligible, active, and status ACTIVE
        if (!accountsRes.getIsEligible() || !accountsRes.getIsActive() || !"ACTIVE".equalsIgnoreCase(accountsRes.getStatus())) {
            throw Status.PERMISSION_DENIED.withDescription("Courier is not active or eligible: " + accountsRes.getRejectionReason()).asRuntimeException();
        }

        // 4. Delivery Assignments & Daily Outcomes Read
        ListCourierAssignmentsRequest deliveryReq = ListCourierAssignmentsRequest.newBuilder()
                .setCourierSub(actorSub)
                .setPage(0)
                .setPageSize(DEFAULT_PAGE_SIZE)
                .setActiveOnly(true)
                .build();

        ListCourierAssignmentsResponse deliveryRes = deliveryClient.delivery(
                environment.getGraphQlContext(),
                stub -> stub.listCourierAssignments(deliveryReq)
        );

        if (deliveryRes == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery service returned null response").asRuntimeException();
        }

        // 5. Response Validation
        if (deliveryRes.getAssignmentsCount() > PaginationPolicy.MAX_SIZE) {
            throw Status.INTERNAL.withDescription("Provider returned oversized assignment list: " + deliveryRes.getAssignmentsCount()).asRuntimeException();
        }

        int activeAssignedCount = deliveryRes.getActiveCount();
        int todayDeliveredCount = deliveryRes.getTodayDeliveredCount();
        int todayFailedCount = deliveryRes.getTodayFailedCount();

        if (activeAssignedCount < 0 || todayDeliveredCount < 0 || todayFailedCount < 0) {
            throw Status.INTERNAL.withDescription("Provider returned negative assignment counts").asRuntimeException();
        }

        // 6. Projections & Mapping
        List<CourierParcelItem> parcelItems = new ArrayList<>(deliveryRes.getAssignmentsCount());
        for (CourierAssignmentSummary summary : deliveryRes.getAssignmentsList()) {
            parcelItems.add(mapParcelItem(summary));
        }

        return new CourierDashboard(
                actorSub,
                activeAssignedCount,
                todayDeliveredCount,
                todayFailedCount,
                parcelItems
        );
    }

    private CourierParcelItem mapParcelItem(CourierAssignmentSummary summary) {
        String parcelId = summary.getParcelId();
        if (parcelId == null || parcelId.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing parcelId").asRuntimeException();
        }

        String trackingNumber = summary.getTrackingNumber();
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing trackingNumber").asRuntimeException();
        }

        String currentStatus = summary.getCurrentStatus();
        if (currentStatus == null || !VALID_PARCEL_STATUSES.contains(currentStatus.trim())) {
            throw Status.INTERNAL.withDescription("Invalid parcel lifecycle status: " + currentStatus).asRuntimeException();
        }

        if (!summary.hasAssignedAt() || summary.getAssignedAt().getSeconds() <= 0) {
            throw Status.INTERNAL.withDescription("Missing or invalid assignment timestamp").asRuntimeException();
        }

        Instant assignedInstant = Instant.ofEpochSecond(
                summary.getAssignedAt().getSeconds(),
                summary.getAssignedAt().getNanos()
        );
        String assignedAtStr = assignedInstant.toString();

        int version;
        try {
            version = Math.toIntExact(summary.getVersion());
            if (version < 0) {
                throw Status.INTERNAL.withDescription("Invalid negative version: " + version).asRuntimeException();
            }
        } catch (ArithmeticException e) {
            throw Status.INTERNAL.withDescription("Version overflows GraphQL Int: " + summary.getVersion()).asRuntimeException();
        }

        String recipientName = summary.getRecipientName().isBlank() ? null : summary.getRecipientName();
        String recipientPhone = summary.getRecipientPhone().isBlank() ? null : summary.getRecipientPhone();

        return new CourierParcelItem(
                parcelId,
                trackingNumber,
                currentStatus.trim(),
                summary.getPickupAddress(),
                summary.getDropoffAddress(),
                recipientName,
                recipientPhone,
                assignedAtStr,
                version
        );
    }
}
