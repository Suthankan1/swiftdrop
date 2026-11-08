package com.swiftdrop.security.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Delivery alone may vouch for a tenant it verified through an API key, and only as MERCHANT. */
class MachineMerchantDelegationTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final String SUBJECT = ActorDelegationValidator.MACHINE_MERCHANT_PREFIX + TENANT;

    private final ActorDelegationValidator validator = new ActorDelegationValidator();
    private final Jwt serviceJwt = Jwt.withTokenValue("service")
            .header("alg", "none")
            .subject("service-account")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

    @Test
    void deliveryCanVouchForVerifiedMerchantTenant() {
        var result = validator.validateDelegation(SUBJECT, "MERCHANT", null, serviceJwt,
                SwiftDropServiceIdentity.DELIVERY_SERVICE);

        assertThat(result.isValid()).isTrue();
        assertThat(result.context().tenantId()).isEqualTo(TENANT);
        assertThat(result.context().isMerchant()).isTrue();
    }

    @Test
    void otherServicesCannotVouch() {
        assertThat(validator.validateDelegation(SUBJECT, "MERCHANT", null, serviceJwt,
                SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE).isValid()).isFalse();
        assertThat(validator.validateDelegation(SUBJECT, "MERCHANT", null, serviceJwt,
                SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE).isValid()).isFalse();
    }

    @Test
    void cannotClaimAnyOtherRole() {
        assertThat(validator.validateDelegation(SUBJECT, "ADMIN", null, serviceJwt,
                SwiftDropServiceIdentity.DELIVERY_SERVICE).isValid()).isFalse();
        assertThat(validator.validateDelegation(SUBJECT, "MERCHANT,ADMIN", null, serviceJwt,
                SwiftDropServiceIdentity.DELIVERY_SERVICE).isValid()).isFalse();
    }

    @Test
    void ordinarySubjectsAndMalformedTenantsStayRejected() {
        assertThat(validator.validateDelegation("some-user-sub", "MERCHANT", null, serviceJwt,
                SwiftDropServiceIdentity.DELIVERY_SERVICE).isValid()).isFalse();
        assertThat(validator.validateDelegation("merchant:not-a-uuid", "MERCHANT", null, serviceJwt,
                SwiftDropServiceIdentity.DELIVERY_SERVICE).isValid()).isFalse();
    }

    @Test
    void requiresVerifiedServiceToken() {
        assertThat(validator.validateDelegation(SUBJECT, "MERCHANT", null, null,
                SwiftDropServiceIdentity.DELIVERY_SERVICE).isValid()).isFalse();
    }
}
