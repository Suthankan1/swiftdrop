package com.swiftdrop.deliveryservice.query.service;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.security.KeycloakRoleExtractor;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Enforces strict object-level authorization (BOLA / IDOR defense) for parcel reads.
 *
 * Rules:
 * 1. DISPATCHER and ADMIN hold operational scope for all parcels.
 * 2. CUSTOMER can read only parcels where caller subject matches senderSub.
 * 3. MERCHANT can read only parcels where caller's merchant_id matches parcel's merchantId,
 *    and the merchant status is APPROVED (not PENDING or SUSPENDED).
 * 4. COURIER can read only parcels where the courier has a currently active assignment.
 *    Previous assignees whose assignment ended have no read access.
 * 5. All other cases fail closed.
 */
@Service
public class ParcelAuthorizationService {

    private final DeliveryAssignmentRepository assignmentRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final com.swiftdrop.deliveryservice.security.merchant.MerchantTenantResolver merchantTenantResolver;

    public ParcelAuthorizationService(DeliveryAssignmentRepository assignmentRepository) {
        this(assignmentRepository, null, null);
    }

    public ParcelAuthorizationService(
            DeliveryAssignmentRepository assignmentRepository,
            com.swiftdrop.deliveryservice.security.merchant.MerchantTenantResolver merchantTenantResolver) {
        this(assignmentRepository, merchantTenantResolver, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ParcelAuthorizationService(
            DeliveryAssignmentRepository assignmentRepository,
            com.swiftdrop.deliveryservice.security.merchant.MerchantTenantResolver merchantTenantResolver,
            StatusHistoryRepository statusHistoryRepository) {
        this.assignmentRepository = assignmentRepository;
        this.merchantTenantResolver = merchantTenantResolver;
        this.statusHistoryRepository = statusHistoryRepository;
    }

    public boolean isAuthorizedToRead(Jwt jwt, Parcel parcel) {
        if (jwt == null || parcel == null) {
            return false;
        }

        String callerSub = jwt.getSubject();
        if (callerSub == null || callerSub.isBlank()) {
            return false;
        }

        List<String> roles = KeycloakRoleExtractor.extractApprovedRoles(jwt);

        // 1. Staff operational access
        if (roles.contains("ADMIN") || roles.contains("DISPATCHER")) {
            return true;
        }

        // 2. Customer creator / owner access
        if (roles.contains("CUSTOMER") && callerSub.equals(parcel.getSenderSub())) {
            return true;
        }

        // 3. Merchant tenant authorization
        if (roles.contains("MERCHANT") && parcel.getMerchantId() != null) {
            Object merchantClaim = jwt.getClaim("merchant_id");
            if (merchantClaim != null && parcel.getMerchantId().toString().equalsIgnoreCase(merchantClaim.toString())) {
                String statusClaim = jwt.getClaimAsString("merchant_status");
                if (statusClaim != null && !statusClaim.equalsIgnoreCase("APPROVED")) {
                    return false;
                }
                return true;
            } else if (merchantTenantResolver != null) {
                var resolvedTenant = merchantTenantResolver.resolveMerchantTenant(callerSub);
                if (resolvedTenant.isPresent() && resolvedTenant.get().equals(parcel.getMerchantId())) {
                    return true;
                }
            }
        }

        // 4. Currently assigned courier authorization
        if (roles.contains("COURIER")) {
            return assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(
                    parcel.getParcelId(),
                    callerSub.trim()
            );
        }

        return false;
    }

    public boolean isAuthorizedToReadHistoricalCourier(Jwt jwt, Parcel parcel) {
        if (jwt == null || parcel == null || statusHistoryRepository == null
                || !KeycloakRoleExtractor.extractApprovedRoles(jwt).contains("COURIER")) {
            return false;
        }
        String courierSub = jwt.getSubject();
        if (courierSub == null || courierSub.isBlank()) return false;

        var histories = statusHistoryRepository.findByParcelIdOrderByOccurredAtDesc(parcel.getParcelId());
        return assignmentRepository.findByParcelIdOrderByAssignedAtDesc(parcel.getParcelId()).stream()
                .filter(assignment -> courierSub.equals(assignment.getCourierSub()))
                .anyMatch(assignment -> histories.stream().anyMatch(history ->
                        courierSub.equals(history.getActorSub())
                                && history.getNewStatus().isTerminal()
                                && !history.getOccurredAt().isBefore(assignment.getAssignedAt())
                                && (assignment.getEndedAt() == null
                                || !history.getOccurredAt().isAfter(assignment.getEndedAt()))));
    }
}
