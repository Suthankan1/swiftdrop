package com.swiftdrop.graphql.resolver;

import com.swiftdrop.delivery.v1.CustomerParcelSummary;
import com.swiftdrop.delivery.v1.ListCustomerParcelsRequest;
import com.swiftdrop.delivery.v1.ListCustomerParcelsResponse;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.limits.PaginationPolicy;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Production resolver for Query.customerDashboard (Issue #132 / N10-WF03).
 *
 * <p>Authorization and security policy enforced here:
 * <ol>
 *   <li>Caller must supply a valid, signed SwiftDrop JWT (verified by security filter
 *       and available in GraphQL context as "actor").</li>
 *   <li>Caller must possess {@code ROLE_CUSTOMER} authority (gated by {@code GraphqlFieldPolicy}
 *       and defensively verified here). Callers with other roles only (e.g. MERCHANT, COURIER,
 *       DISPATCHER, ADMIN) are denied access with {@link AccessDeniedException}.</li>
 *   <li>Customer identity is bound strictly and exclusively to the verified JWT subject
 *       ({@code actor.getToken().getSubject()}). No caller-supplied customer ID, GraphQL argument,
 *       variable, or HTTP header is ever trusted for customer identity.</li>
 *   <li>Delivery operations gRPC is queried via {@code ListCustomerParcels} with the authenticated
 *       subject, default page 0 and bounded size 20 (up to max 50). Delivery independently authorizes
 *       the delegated actor context.</li>
 *   <li>Responses are validated for bounds, valid UUIDs, canonical lifecycle statuses, non-negative
 *       and consistent counts, Int32 monetary bounds, and monotonic ISO-8601 UTC timestamps.</li>
 * </ol>
 */
@Component
public class CustomerDashboardResolver implements RootQueryResolver {

    private static final String FIELD_NAME = "customerDashboard";
    private static final String REQUIRED_ROLE = "CUSTOMER";
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<String> VALID_PARCEL_STATUSES = Set.of(
            "CREATED",
            "PICKED_UP",
            "IN_TRANSIT",
            "OUT_FOR_DELIVERY",
            "DELIVERED",
            "FAILED"
    );

    private final AuthenticatedReadClients deliveryClient;

    public CustomerDashboardResolver(
            @Autowired(required = false) @Qualifier("deliveryReadClients") AuthenticatedReadClients deliveryClient) {
        this.deliveryClient = deliveryClient;
    }

    @Override
    public String field() {
        return FIELD_NAME;
    }

    @Override
    public Object resolve(DataFetchingEnvironment environment) throws Exception {
        // 1. Identity & Actor Validation — read actor exclusively from trusted GraphQL context
        JwtAuthenticationToken actor = environment.getGraphQlContext().get("actor");
        if (actor == null || !actor.isAuthenticated() || actor.getToken() == null || actor.getToken().getSubject() == null) {
            throw Status.UNAUTHENTICATED.withDescription("Unauthenticated caller").asRuntimeException();
        }
        String actorSub = actor.getToken().getSubject().trim();
        if (actorSub.isBlank()) {
            throw Status.UNAUTHENTICATED.withDescription("Missing authenticated subject").asRuntimeException();
        }

        // Defensive role check matching GraphqlFieldPolicy
        boolean hasCustomerRole = actor.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + REQUIRED_ROLE).equals(a.getAuthority()));
        if (!hasCustomerRole) {
            throw new AccessDeniedException("Caller does not have ROLE_CUSTOMER");
        }

        // 2. Provider availability check
        if (deliveryClient == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery service provider unavailable").asRuntimeException();
        }

        // 3. Construct scoped gRPC request for recent parcels preview
        ListCustomerParcelsRequest request = ListCustomerParcelsRequest.newBuilder()
                .setCustomerSub(actorSub)
                .setPage(0)
                .setPageSize(DEFAULT_PAGE_SIZE)
                .build();

        // 4. Query Delivery gRPC via authenticated client
        ListCustomerParcelsResponse response = deliveryClient.delivery(
                environment.getGraphQlContext(),
                stub -> stub.listCustomerParcels(request)
        );

        if (response == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery service returned null response").asRuntimeException();
        }

        // 5. Response Validation
        if (response.getPage() != 0) {
            throw Status.INTERNAL.withDescription("Provider returned unexpected page: " + response.getPage()).asRuntimeException();
        }
        if (response.getPageSize() < 1 || response.getPageSize() > PaginationPolicy.MAX_SIZE) {
            throw Status.INTERNAL.withDescription("Provider returned invalid page size: " + response.getPageSize()).asRuntimeException();
        }
        if (response.getParcelsCount() > DEFAULT_PAGE_SIZE) {
            throw Status.INTERNAL.withDescription("Provider returned oversized parcel preview: " + response.getParcelsCount()).asRuntimeException();
        }

        int activeCount = response.getActiveCount();
        int completedCount = response.getCompletedCount();
        int totalCount = response.getTotalCount();

        if (activeCount < 0 || completedCount < 0 || totalCount < 0) {
            throw Status.INTERNAL.withDescription("Provider returned negative parcel counts").asRuntimeException();
        }

        // Reconcile counts: for an unfiltered complete query, active_count + completed_count == total_count
        if (activeCount + completedCount != totalCount) {
            throw Status.INTERNAL.withDescription("Provider returned inconsistent totals: active=" + activeCount
                    + " + completed=" + completedCount + " != total=" + totalCount).asRuntimeException();
        }

        if (totalCount < response.getParcelsCount()) {
            throw Status.INTERNAL.withDescription("Provider returned more preview parcels than total count").asRuntimeException();
        }

        // 6. Projections & Mapping
        Set<String> seenParcelIds = new HashSet<>();
        List<CustomerParcelSummaryItem> parcelItems = new ArrayList<>(response.getParcelsCount());
        for (CustomerParcelSummary summary : response.getParcelsList()) {
            CustomerParcelSummaryItem item = mapParcelItem(summary);
            if (!seenParcelIds.add(item.parcelId())) {
                throw Status.INTERNAL.withDescription("Duplicate parcel record returned by provider: " + item.parcelId()).asRuntimeException();
            }
            parcelItems.add(item);
        }

        return new CustomerDashboard(
                actorSub,
                activeCount,
                completedCount,
                parcelItems
        );
    }

    private CustomerParcelSummaryItem mapParcelItem(CustomerParcelSummary summary) {
        String parcelId = summary.getParcelId();
        if (parcelId == null || parcelId.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing parcelId").asRuntimeException();
        }
        try {
            UUID.fromString(parcelId.trim());
        } catch (IllegalArgumentException e) {
            throw Status.INTERNAL.withDescription("Provider returned malformed parcelId UUID: " + parcelId).asRuntimeException();
        }

        String trackingNumber = summary.getTrackingNumber();
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing trackingNumber").asRuntimeException();
        }

        String currentStatus = summary.getCurrentStatus();
        if (currentStatus == null || !VALID_PARCEL_STATUSES.contains(currentStatus.trim())) {
            throw Status.INTERNAL.withDescription("Invalid parcel lifecycle status: " + currentStatus).asRuntimeException();
        }

        long amountMinorUnitsRaw = summary.getAmountMinorUnits();
        if (amountMinorUnitsRaw < 0) {
            throw Status.INTERNAL.withDescription("Provider returned negative amountMinorUnits: " + amountMinorUnitsRaw).asRuntimeException();
        }
        int amountMinorUnits;
        try {
            amountMinorUnits = Math.toIntExact(amountMinorUnitsRaw);
        } catch (ArithmeticException e) {
            throw Status.INTERNAL.withDescription("amountMinorUnits overflows GraphQL Int: " + amountMinorUnitsRaw).asRuntimeException();
        }

        String currency = summary.getCurrency();
        if (currency == null || currency.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing currency").asRuntimeException();
        }

        String recipientCity = summary.getRecipientCity() != null ? summary.getRecipientCity().trim() : "";
        if (recipientCity.length() > 256) {
            throw Status.INTERNAL.withDescription("recipientCity exceeds maximum length").asRuntimeException();
        }

        if (!summary.hasCreatedAt() || summary.getCreatedAt().getSeconds() <= 0) {
            throw Status.INTERNAL.withDescription("Missing or invalid createdAt timestamp").asRuntimeException();
        }
        if (!summary.hasUpdatedAt() || summary.getUpdatedAt().getSeconds() <= 0) {
            throw Status.INTERNAL.withDescription("Missing or invalid updatedAt timestamp").asRuntimeException();
        }

        Instant createdInstant = Instant.ofEpochSecond(
                summary.getCreatedAt().getSeconds(),
                summary.getCreatedAt().getNanos()
        );
        Instant updatedInstant = Instant.ofEpochSecond(
                summary.getUpdatedAt().getSeconds(),
                summary.getUpdatedAt().getNanos()
        );

        if (updatedInstant.isBefore(createdInstant)) {
            throw Status.INTERNAL.withDescription("updatedAt timestamp is before createdAt timestamp").asRuntimeException();
        }

        return new CustomerParcelSummaryItem(
                parcelId.trim(),
                trackingNumber.trim(),
                currentStatus.trim(),
                amountMinorUnits,
                currency.trim(),
                recipientCity,
                createdInstant.toString(),
                updatedInstant.toString()
        );
    }
}
