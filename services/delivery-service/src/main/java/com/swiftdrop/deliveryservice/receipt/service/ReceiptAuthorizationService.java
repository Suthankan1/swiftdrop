package com.swiftdrop.deliveryservice.receipt.service;

import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.security.KeycloakRoleExtractor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Enforces strict object-level authorization (BOLA/IDOR protection) for PDF delivery receipts.
 * Accessible only by:
 * 1. The customer who booked/created the parcel (matching JWT 'sub').
 * 2. The merchant tenant who owns the parcel (matching merchantId claim or creator 'sub').
 * 3. The verified recipient (matching phone claim, if present).
 * 4. Authorized platform staff (ADMIN or DISPATCHER roles).
 */
@Service
public class ReceiptAuthorizationService {

    public boolean isAuthorized(Jwt jwt, ReceiptData receipt) {
        if (jwt == null || receipt == null) {
            return false;
        }

        String callerSub = jwt.getSubject();
        if (callerSub == null || callerSub.isBlank()) {
            return false;
        }

        List<String> roles = KeycloakRoleExtractor.extractApprovedRoles(jwt);

        // 1. Staff override: Admin and Dispatcher hold operational review authorization
        if (roles.contains("ADMIN") || roles.contains("DISPATCHER")) {
            return true;
        }

        // 2. Creator / Owner match (Customer or Merchant creator)
        if (callerSub.equals(receipt.createdBySub())) {
            return true;
        }

        // 3. Merchant tenant authorization
        if (roles.contains("MERCHANT") && receipt.merchantId() != null) {
            Object merchantClaim = jwt.getClaim("merchant_id");
            if (merchantClaim != null && receipt.merchantId().toString().equalsIgnoreCase(merchantClaim.toString())) {
                return true;
            }
        }

        // 4. Recipient verification (if recipient phone claim is present in JWT)
        String phoneClaim = jwt.getClaimAsString("phone_number");
        if (phoneClaim != null && !phoneClaim.isBlank() && phoneClaim.equals(receipt.recipientPhone())) {
            return true;
        }

        // Fail closed
        return false;
    }
}
