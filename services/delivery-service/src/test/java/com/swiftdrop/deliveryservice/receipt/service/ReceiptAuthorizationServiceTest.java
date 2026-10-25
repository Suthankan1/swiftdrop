package com.swiftdrop.deliveryservice.receipt.service;

import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptLineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptAuthorizationServiceTest {

    private ReceiptAuthorizationService authorizationService;
    private ReceiptData customerReceipt;
    private ReceiptData merchantReceipt;

    private static final String CUSTOMER_SUB = "auth0|customer-alice";
    private static final String OTHER_SUB = "auth0|customer-mallory";
    private static final UUID MERCHANT_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_MERCHANT_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String RECIPIENT_PHONE = "+94771234567";

    @BeforeEach
    void setUp() {
        authorizationService = new ReceiptAuthorizationService();

        customerReceipt = new ReceiptData(
                "RCP-SWD-20261025-CUST",
                UUID.randomUUID(),
                "SWD-20261025-CUST",
                CUSTOMER_SUB,
                null,
                "Bob Recipient",
                RECIPIENT_PHONE,
                "10 Galle Face, Colombo",
                "20 Flower Road, Colombo",
                "DELIVERED",
                Instant.now(),
                Instant.now(),
                "RATE-2026-V1",
                "TX-12345",
                true,
                "LKR",
                50000L,
                List.of(new ReceiptLineItem("Delivery", 50000L, "LKR"))
        );

        merchantReceipt = new ReceiptData(
                "RCP-SWD-20261025-MERCH",
                UUID.randomUUID(),
                "SWD-20261025-MERCH",
                "auth0|merchant-staff",
                MERCHANT_UUID,
                "Charlie Recipient",
                "+94779876543",
                "Warehouse A, Colombo",
                "Customer Home, Kandy",
                "DELIVERED",
                Instant.now(),
                Instant.now(),
                "RATE-2026-V1",
                "TX-99999",
                true,
                "LKR",
                75000L,
                List.of(new ReceiptLineItem("Commercial Delivery", 75000L, "LKR"))
        );
    }

    private Jwt createJwt(String sub, List<String> roles, Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject(sub)
                .claim("roles", roles);

        if (extraClaims != null) {
            extraClaims.forEach(builder::claim);
        }

        return builder.build();
    }

    @Test
    @DisplayName("Parcel creator is authorized to access own customer receipt")
    void creatorAuthorizedForCustomerReceipt() {
        Jwt jwt = createJwt(CUSTOMER_SUB, List.of("CUSTOMER"), null);
        assertThat(authorizationService.isAuthorized(jwt, customerReceipt)).isTrue();
    }

    @Test
    @DisplayName("Cross-customer access denied (IDOR protection)")
    void crossCustomerAccessDenied() {
        Jwt jwt = createJwt(OTHER_SUB, List.of("CUSTOMER"), null);
        assertThat(authorizationService.isAuthorized(jwt, customerReceipt)).isFalse();
    }

    @Test
    @DisplayName("Merchant tenant member is authorized for merchant parcel receipt")
    void merchantTenantAuthorized() {
        Jwt jwt = createJwt("auth0|merchant-user", List.of("MERCHANT"), Map.of("merchant_id", MERCHANT_UUID.toString()));
        assertThat(authorizationService.isAuthorized(jwt, merchantReceipt)).isTrue();
    }

    @Test
    @DisplayName("Cross-tenant merchant access denied")
    void crossTenantMerchantDenied() {
        Jwt jwt = createJwt("auth0|other-merchant", List.of("MERCHANT"), Map.of("merchant_id", OTHER_MERCHANT_UUID.toString()));
        assertThat(authorizationService.isAuthorized(jwt, merchantReceipt)).isFalse();
    }

    @Test
    @DisplayName("Verified recipient matching phone claim is authorized")
    void verifiedRecipientAuthorized() {
        Jwt jwt = createJwt("auth0|recipient-user", List.of("CUSTOMER"), Map.of("phone_number", RECIPIENT_PHONE));
        assertThat(authorizationService.isAuthorized(jwt, customerReceipt)).isTrue();
    }

    @Test
    @DisplayName("Recipient with non-matching phone claim is denied")
    void recipientWithWrongPhoneDenied() {
        Jwt jwt = createJwt("auth0|recipient-user", List.of("CUSTOMER"), Map.of("phone_number", "+94770000000"));
        assertThat(authorizationService.isAuthorized(jwt, customerReceipt)).isFalse();
    }

    @Test
    @DisplayName("Admin role is authorized to inspect any receipt for operational audit")
    void adminAuthorized() {
        Jwt jwt = createJwt("auth0|admin-user", List.of("ADMIN"), null);
        assertThat(authorizationService.isAuthorized(jwt, customerReceipt)).isTrue();
        assertThat(authorizationService.isAuthorized(jwt, merchantReceipt)).isTrue();
    }

    @Test
    @DisplayName("Dispatcher role is authorized to inspect any receipt for operational monitoring")
    void dispatcherAuthorized() {
        Jwt jwt = createJwt("auth0|dispatcher-user", List.of("DISPATCHER"), null);
        assertThat(authorizationService.isAuthorized(jwt, customerReceipt)).isTrue();
        assertThat(authorizationService.isAuthorized(jwt, merchantReceipt)).isTrue();
    }

    @Test
    @DisplayName("Courier role without ownership or staff privilege is denied")
    void courierDenied() {
        Jwt jwt = createJwt("auth0|courier-user", List.of("COURIER"), null);
        assertThat(authorizationService.isAuthorized(jwt, customerReceipt)).isFalse();
    }

    @Test
    @DisplayName("Null or blank subject fails closed")
    void nullOrBlankSubjectDenied() {
        assertThat(authorizationService.isAuthorized(null, customerReceipt)).isFalse();

        Jwt blankSubJwt = createJwt("   ", List.of("CUSTOMER"), null);
        assertThat(authorizationService.isAuthorized(blankSubJwt, customerReceipt)).isFalse();
    }
}
