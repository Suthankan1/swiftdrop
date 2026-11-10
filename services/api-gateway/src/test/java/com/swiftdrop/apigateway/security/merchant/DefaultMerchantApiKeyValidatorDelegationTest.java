package com.swiftdrop.apigateway.security.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** With delegation on, the gateway forwards well-formed keys and Delivery Operations verifies them. */
class DefaultMerchantApiKeyValidatorDelegationTest {

    private static final String WELL_FORMED_KEY = "swd_live_0123456789abcdefABCDEF0123456789";

    private DefaultMerchantApiKeyValidator validator(boolean delegate) {
        DefaultMerchantApiKeyValidator validator = new DefaultMerchantApiKeyValidator();
        ReflectionTestUtils.setField(validator, "delegateToService", delegate);
        return validator;
    }

    @Test
    void forwardsWellFormedUnknownKeyWhenDelegating() {
        MerchantApiKeyValidationResult result = validator(true).validateKey(WELL_FORMED_KEY, "parcels:create").block();

        assertThat(result.authenticated()).isTrue();
        assertThat(result.authorized()).isTrue();
        assertThat(result.merchantId()).isEqualTo("unverified:swd_live_0123");
        assertThat(result.keyPrefix()).isEqualTo("swd_live_0123");
    }

    @Test
    void stillRejectsMalformedKeysWhenDelegating() {
        MerchantApiKeyValidationResult result = validator(true).validateKey("not-a-key", "parcels:create").block();

        assertThat(result.authenticated()).isFalse();
    }

    @Test
    void rejectsUnknownKeysWhenNotDelegating() {
        MerchantApiKeyValidationResult result = validator(false).validateKey(WELL_FORMED_KEY, "parcels:create").block();

        assertThat(result.authenticated()).isFalse();
    }
}
