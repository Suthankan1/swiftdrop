package com.swiftdrop.graphql.resolver;

import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.delivery.v1.ListMerchantOrdersRequest;
import com.swiftdrop.delivery.v1.ListMerchantOrdersResponse;
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
 * Production resolver for Query.merchantDashboard (Issue #145 / N10-WF09).
 *
 * <p>Authorization policy enforced here:
 * <ol>
 *   <li>Caller must supply a valid, signed SwiftDrop JWT (enforced by the gateway/security layer
 *       before this resolver is reached, and verified again from GraphQL context).</li>
 *   <li>Caller must carry ROLE_MERCHANT in the JWT (checked by {@code GraphqlFieldPolicy} before
 *       this resolver is called, and verified again defensively here).</li>
 *   <li>Accounts service must confirm the subject is eligible as an approved merchant. Status must
 *       be exactly {@code "APPROVED"} per the schema enum and the Accounts proto comment. Pending,
 *       suspended, and rejected merchants cannot access this dashboard.</li>
 *   <li>The merchant identity (merchantId, businessName, status) is resolved exclusively from the
 *       trusted Accounts response — never from caller-supplied GraphQL arguments, variables,
 *       headers, or query fields.</li>
 *   <li>All Delivery queries are scoped to the Accounts-confirmed merchantId; cross-merchant
 *       queries are structurally impossible.</li>
 * </ol>
 *
 * <p>Data flow:
 * <ol>
 *   <li>Extract and validate JWT subject from {@code env.getGraphQlContext().get("actor")}.</li>
 *   <li>Call {@code Accounts.CheckAccountEligibility} with subject and required role "MERCHANT";
 *       verify subject echo-back (anti-spoofing); require status == "APPROVED".</li>
 *   <li>Call {@code Delivery.ListMerchantOrders} scoped to the Accounts-confirmed merchantId.</li>
 *   <li>Validate provider response bounds and field integrity.</li>
 *   <li>Return {@link MerchantDashboard} composed from both provider responses.</li>
 * </ol>
 */
@Component
public class MerchantDashboardResolver implements RootQueryResolver {

    private static final String FIELD_NAME = "merchantDashboard";
    private static final String REQUIRED_ROLE = "MERCHANT";

    /**
     * The only status value that grants dashboard access. Accounts proto documents the status field
     * as: "APPROVED", "ACTIVE", "PENDING", "SUSPENDED", "INACTIVE", "NOT_FOUND". Only "APPROVED"
     * merchants may access the full dashboard per the GraphQL schema comment and issue #145.
     */
    private static final String APPROVED_STATUS = "APPROVED";

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<String> VALID_PARCEL_STATUSES = Set.of(
            "CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"
    );

    private final AuthenticatedReadClients accountsClient;
    private final AuthenticatedReadClients deliveryClient;

    public MerchantDashboardResolver(
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
        // 1. Identity & Actor Validation — read actor from trusted GraphQL context only.
        JwtAuthenticationToken actor = environment.getGraphQlContext().get("actor");
        if (actor == null || !actor.isAuthenticated() || actor.getToken() == null
                || actor.getToken().getSubject() == null) {
            throw Status.UNAUTHENTICATED.withDescription("Unauthenticated caller").asRuntimeException();
        }
        String actorSub = actor.getToken().getSubject().trim();
        if (actorSub.isBlank()) {
            throw Status.UNAUTHENTICATED.withDescription("Missing authenticated subject").asRuntimeException();
        }

        // 2. Role verification — defensive; GraphqlFieldPolicy already enforced MERCHANT before us.
        boolean hasMerchantRole = actor.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + REQUIRED_ROLE).equals(a.getAuthority()));
        if (!hasMerchantRole) {
            throw new AccessDeniedException("Caller does not have ROLE_MERCHANT");
        }

        // 3. Provider availability check.
        if (accountsClient == null || deliveryClient == null) {
            throw Status.UNAVAILABLE.withDescription("Service provider unavailable").asRuntimeException();
        }

        // 4. Accounts approval & tenant binding.
        //    The merchantId returned here is the authoritative, server-validated tenant identity.
        //    We never accept merchantId from any client-supplied source.
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

        // Anti-spoofing check: Accounts must echo back the same subject we sent.
        if (!actorSub.equals(accountsRes.getSubject())) {
            throw Status.PERMISSION_DENIED.withDescription(
                    "Subject mismatch in Accounts eligibility response").asRuntimeException();
        }

        // Enrollment/approval guard: only APPROVED merchants may access the dashboard.
        // PENDING, SUSPENDED, REJECTED, NOT_FOUND, INACTIVE all result in FORBIDDEN.
        if (!accountsRes.getIsEligible() || !accountsRes.getIsActive()
                || !APPROVED_STATUS.equalsIgnoreCase(accountsRes.getStatus())) {
            throw Status.PERMISSION_DENIED.withDescription(
                    "Merchant is not approved: " + accountsRes.getRejectionReason()).asRuntimeException();
        }

        // Extract tenant identity from the trusted Accounts response.
        String merchantId = accountsRes.getMerchantId();
        if (merchantId == null || merchantId.isBlank()) {
            throw Status.PERMISSION_DENIED.withDescription(
                    "Accounts response does not include a merchant tenant identifier").asRuntimeException();
        }
        String businessName = accountsRes.getBusinessName();
        if (businessName == null || businessName.isBlank()) {
            throw Status.PERMISSION_DENIED.withDescription(
                    "Accounts response does not include a business name").asRuntimeException();
        }

        // 5. Delivery: bounded recent orders scoped to the Accounts-confirmed merchantId.
        //    merchantId here is the server-resolved identity, not a caller argument.
        ListMerchantOrdersRequest deliveryReq = ListMerchantOrdersRequest.newBuilder()
                .setMerchantId(merchantId)
                .setPage(0)
                .setPageSize(DEFAULT_PAGE_SIZE)
                .build();

        ListMerchantOrdersResponse deliveryRes = deliveryClient.delivery(
                environment.getGraphQlContext(),
                stub -> stub.listMerchantOrders(deliveryReq)
        );

        if (deliveryRes == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery service returned null response").asRuntimeException();
        }

        // 6. Response validation: bound the returned order list.
        if (deliveryRes.getOrdersCount() > PaginationPolicy.MAX_SIZE) {
            throw Status.INTERNAL.withDescription(
                    "Provider returned oversized order list: " + deliveryRes.getOrdersCount()).asRuntimeException();
        }

        int totalOrdersCount = deliveryRes.getTotalCount();
        int activeOrdersCount = deliveryRes.getActiveCount();
        int deliveredOrdersCount = deliveryRes.getDeliveredCount();
        int failedOrdersCount = deliveryRes.getFailedCount();

        if (totalOrdersCount < 0 || activeOrdersCount < 0 || deliveredOrdersCount < 0 || failedOrdersCount < 0) {
            throw Status.INTERNAL.withDescription("Provider returned negative order counts").asRuntimeException();
        }

        // 7. Map order summaries.
        List<MerchantOrderSummaryItem> recentOrders = new ArrayList<>(deliveryRes.getOrdersCount());
        for (com.swiftdrop.delivery.v1.MerchantOrderSummary summary : deliveryRes.getOrdersList()) {
            recentOrders.add(mapOrderSummary(summary));
        }

        // 8. Compose response — merchantId and businessName from Accounts (trusted), never from caller.
        return new MerchantDashboard(
                merchantId,
                businessName,
                APPROVED_STATUS,
                totalOrdersCount,
                activeOrdersCount,
                deliveredOrdersCount,
                failedOrdersCount,
                recentOrders
        );
    }

    private MerchantOrderSummaryItem mapOrderSummary(com.swiftdrop.delivery.v1.MerchantOrderSummary summary) {
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
            throw Status.INTERNAL.withDescription("Invalid parcel lifecycle status: " + currentStatus)
                    .asRuntimeException();
        }

        if (!summary.hasCreatedAt() || summary.getCreatedAt().getSeconds() <= 0) {
            throw Status.INTERNAL.withDescription("Missing or invalid createdAt timestamp").asRuntimeException();
        }
        String createdAt = Instant.ofEpochSecond(
                summary.getCreatedAt().getSeconds(),
                summary.getCreatedAt().getNanos()).toString();

        // lastStatusUpdate is optional; default to createdAt if absent.
        String lastStatusUpdate;
        if (summary.hasLastStatusUpdate() && summary.getLastStatusUpdate().getSeconds() > 0) {
            lastStatusUpdate = Instant.ofEpochSecond(
                    summary.getLastStatusUpdate().getSeconds(),
                    summary.getLastStatusUpdate().getNanos()).toString();
        } else {
            lastStatusUpdate = createdAt;
        }

        // feeMinorUnits in proto is int64; GraphQL schema uses Int (32-bit). Validate bounds.
        int feeMinorUnits;
        try {
            feeMinorUnits = Math.toIntExact(summary.getFeeMinorUnits());
            if (feeMinorUnits < 0) {
                throw Status.INTERNAL.withDescription("Negative feeMinorUnits: " + feeMinorUnits)
                        .asRuntimeException();
            }
        } catch (ArithmeticException e) {
            throw Status.INTERNAL.withDescription(
                    "feeMinorUnits overflows GraphQL Int: " + summary.getFeeMinorUnits()).asRuntimeException();
        }

        double weightKg = summary.getWeightKg();
        if (Double.isNaN(weightKg) || Double.isInfinite(weightKg) || weightKg < 0) {
            throw Status.INTERNAL.withDescription("Invalid weightKg: " + weightKg).asRuntimeException();
        }

        return new MerchantOrderSummaryItem(
                parcelId,
                trackingNumber,
                currentStatus.trim(),
                weightKg,
                feeMinorUnits,
                summary.getCurrency(),
                createdAt,
                lastStatusUpdate
        );
    }
}
