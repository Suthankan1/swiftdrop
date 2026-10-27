package com.swiftdrop.graphql.resolver.admin;

import com.google.protobuf.Timestamp;
import com.swiftdrop.accounts.v1.PendingMerchantsSummaryRequest;
import com.swiftdrop.accounts.v1.PendingMerchantsSummaryResponse;
import com.swiftdrop.delivery.v1.GetDeliveryStatsRequest;
import com.swiftdrop.delivery.v1.GetDeliveryStatsResponse;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.limits.PaginationPolicy;
import com.swiftdrop.graphql.resolver.RootQueryResolver;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Production resolver for Query.adminDashboard (Issue #139 / N10-WF23).
 * Aggregates authorized Accounts merchant review summary and Delivery operational statistics
 * strictly for verified ADMIN callers via private internal gRPC reads.
 */
@Component
public class AdminDashboardResolver implements RootQueryResolver {

    private static final String FIELD_NAME = "adminDashboard";
    private static final String REQUIRED_ROLE = "ADMIN";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final ZoneId COLOMBO_ZONE = ZoneId.of("Asia/Colombo");

    private final AuthenticatedReadClients accountsClient;
    private final AuthenticatedReadClients deliveryClient;
    private final Clock clock;

    @Autowired
    public AdminDashboardResolver(
            @Autowired(required = false) @Qualifier("accountsReadClients") AuthenticatedReadClients accountsClient,
            @Autowired(required = false) @Qualifier("deliveryReadClients") AuthenticatedReadClients deliveryClient) {
        this(accountsClient, deliveryClient, Clock.system(COLOMBO_ZONE));
    }

    AdminDashboardResolver(
            AuthenticatedReadClients accountsClient,
            AuthenticatedReadClients deliveryClient,
            Clock clock) {
        this.accountsClient = accountsClient;
        this.deliveryClient = deliveryClient;
        this.clock = clock;
    }

    @Override
    public String field() {
        return FIELD_NAME;
    }

    @Override
    public Object resolve(DataFetchingEnvironment environment) throws Exception {
        // 1. Identity & ADMIN Role Gate
        JwtAuthenticationToken actor = environment.getGraphQlContext().get("actor");
        if (actor == null || !actor.isAuthenticated() || actor.getToken() == null || actor.getToken().getSubject() == null) {
            throw Status.UNAUTHENTICATED.withDescription("Unauthenticated caller").asRuntimeException();
        }
        String actorSub = actor.getToken().getSubject().trim();
        if (actorSub.isBlank()) {
            throw Status.UNAUTHENTICATED.withDescription("Missing authenticated subject").asRuntimeException();
        }

        boolean hasAdminRole = actor.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + REQUIRED_ROLE).equals(a.getAuthority()));
        if (!hasAdminRole) {
            throw new AccessDeniedException("Caller does not have ROLE_ADMIN");
        }

        // 2. Provider Availability Check
        if (accountsClient == null || deliveryClient == null) {
            throw Status.UNAVAILABLE.withDescription("Service provider unavailable").asRuntimeException();
        }

        // 3. Accounts: Pending Merchant Summary Read (IF-GRPC-06)
        PendingMerchantsSummaryRequest accountsReq = PendingMerchantsSummaryRequest.newBuilder()
                .setStatusFilter("PENDING")
                .setPage(0)
                .setPageSize(DEFAULT_PAGE_SIZE)
                .build();

        PendingMerchantsSummaryResponse accountsRes = accountsClient.accounts(
                environment.getGraphQlContext(),
                stub -> stub.getPendingMerchantsSummary(accountsReq)
        );

        if (accountsRes == null) {
            throw Status.UNAVAILABLE.withDescription("Accounts service returned null response").asRuntimeException();
        }

        int pendingMerchantsCount = accountsRes.getTotalPendingCount();
        if (pendingMerchantsCount < 0) {
            throw Status.INTERNAL.withDescription("Accounts provider returned negative pending count").asRuntimeException();
        }

        if (accountsRes.getItemsCount() > PaginationPolicy.MAX_SIZE) {
            throw Status.INTERNAL.withDescription("Accounts provider returned oversized items list: " + accountsRes.getItemsCount()).asRuntimeException();
        }

        List<PendingMerchantItem> recentPendingMerchants = new ArrayList<>(accountsRes.getItemsCount());
        Set<String> seenMerchantIds = new HashSet<>();
        for (com.swiftdrop.accounts.v1.PendingMerchantItem item : accountsRes.getItemsList()) {
            recentPendingMerchants.add(mapMerchantItem(item, seenMerchantIds));
        }

        // 4. Delivery: Daily Operational Statistics Read (IF-GRPC-12)
        LocalDate todayColombo = LocalDate.now(clock.withZone(COLOMBO_ZONE));
        Instant startInstant = todayColombo.atStartOfDay(COLOMBO_ZONE).toInstant();
        Instant endInstant = todayColombo.plusDays(1).atStartOfDay(COLOMBO_ZONE).toInstant();

        GetDeliveryStatsRequest deliveryReq = GetDeliveryStatsRequest.newBuilder()
                .setStartTime(Timestamp.newBuilder()
                        .setSeconds(startInstant.getEpochSecond())
                        .setNanos(startInstant.getNano()))
                .setEndTime(Timestamp.newBuilder()
                        .setSeconds(endInstant.getEpochSecond())
                        .setNanos(endInstant.getNano()))
                .build();

        GetDeliveryStatsResponse deliveryRes = deliveryClient.delivery(
                environment.getGraphQlContext(),
                stub -> stub.getDeliveryStatsInternal(deliveryReq)
        );

        if (deliveryRes == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery service returned null response").asRuntimeException();
        }

        long totalCountLong = deliveryRes.getTotalCount();
        long deliveredCount = deliveryRes.getDeliveredCount();
        long failedCount = deliveryRes.getFailedCount();
        long inTransitCountLong = deliveryRes.getInTransitCount();
        long createdCount = deliveryRes.getCreatedCount();

        if (totalCountLong < 0 || deliveredCount < 0 || failedCount < 0 || inTransitCountLong < 0 || createdCount < 0) {
            throw Status.INTERNAL.withDescription("Delivery provider returned negative counts").asRuntimeException();
        }

        int totalParcelsCount;
        try {
            totalParcelsCount = Math.toIntExact(totalCountLong);
        } catch (ArithmeticException e) {
            throw Status.INTERNAL.withDescription("Total parcels count overflows GraphQL Int: " + totalCountLong).asRuntimeException();
        }

        int activeParcelsCount;
        try {
            activeParcelsCount = Math.toIntExact(inTransitCountLong);
        } catch (ArithmeticException e) {
            throw Status.INTERNAL.withDescription("Active parcels count overflows GraphQL Int: " + inTransitCountLong).asRuntimeException();
        }

        long terminalTotal = deliveredCount + failedCount;
        double terminalSuccessRate;
        if (terminalTotal == 0) {
            terminalSuccessRate = 0.0;
        } else {
            terminalSuccessRate = (double) deliveredCount / terminalTotal;
            terminalSuccessRate = Math.round(terminalSuccessRate * 10000.0) / 10000.0;
        }

        if (Double.isNaN(terminalSuccessRate) || Double.isInfinite(terminalSuccessRate)) {
            throw Status.INTERNAL.withDescription("Terminal success rate evaluated to non-finite number").asRuntimeException();
        }

        return new AdminDashboard(
                pendingMerchantsCount,
                totalParcelsCount,
                activeParcelsCount,
                terminalSuccessRate,
                recentPendingMerchants
        );
    }

    private PendingMerchantItem mapMerchantItem(com.swiftdrop.accounts.v1.PendingMerchantItem item, Set<String> seenIds) {
        String merchantId = item.getMerchantId();
        if (merchantId == null || merchantId.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing merchantId").asRuntimeException();
        }
        try {
            UUID.fromString(merchantId.trim());
        } catch (IllegalArgumentException e) {
            throw Status.INTERNAL.withDescription("Provider returned malformed merchantId UUID: " + merchantId).asRuntimeException();
        }

        if (!seenIds.add(merchantId.trim())) {
            throw Status.INTERNAL.withDescription("Provider returned duplicate merchant ID: " + merchantId).asRuntimeException();
        }

        String businessName = item.getBusinessName();
        if (businessName == null || businessName.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing businessName").asRuntimeException();
        }

        String registrationNumber = item.getRegistrationNumber();
        if (registrationNumber == null || registrationNumber.isBlank()) {
            throw Status.INTERNAL.withDescription("Provider returned missing registrationNumber").asRuntimeException();
        }

        String status = item.getStatus();
        if (status == null || !"PENDING".equalsIgnoreCase(status.trim())) {
            throw Status.INTERNAL.withDescription("Provider returned non-pending merchant status: " + status).asRuntimeException();
        }

        if (!item.hasSubmittedAt() || item.getSubmittedAt().getSeconds() <= 0) {
            throw Status.INTERNAL.withDescription("Missing or invalid submitted_at timestamp").asRuntimeException();
        }

        Instant submittedInstant = Instant.ofEpochSecond(
                item.getSubmittedAt().getSeconds(),
                item.getSubmittedAt().getNanos()
        );
        String submittedAtStr = submittedInstant.toString();

        return new PendingMerchantItem(
                merchantId.trim(),
                businessName.trim(),
                registrationNumber.trim(),
                submittedAtStr
        );
    }
}
