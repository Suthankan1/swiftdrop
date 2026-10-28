package com.swiftdrop.deliveryservice;

import com.swiftdrop.deliveryservice.security.ReceiptSecurityConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.FilterChainProxy;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryServiceApplicationTest {

    @Test
    @DisplayName("DeliveryServiceApplication declares independent SpringBootApplication entry point")
    void declaresIndependentBootApplication() {
        assertThat(DeliveryServiceApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }

    @Test
    @DisplayName("Security filter chains build without conflict and order properly")
    void securityFilterChainsOrderProperly() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class,
                        ServletWebSecurityAutoConfiguration.class,
                        OAuth2ResourceServerAutoConfiguration.class
                ))
                .withUserConfiguration(
                        BookingFoundationSecurity.class,
                        ReceiptSecurityConfiguration.class
                )
                .withPropertyValues(
                        "KEYCLOAK_JWKS_URI=http://localhost:8090/realms/swiftdrop/protocol/openid-connect/certs",
                        "KEYCLOAK_ISSUER_URI=http://localhost:8090/realms/swiftdrop",
                        "JWT_AUDIENCE=swiftdrop-api"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("foundationSecurity");
                    assertThat(context).hasBean("receiptSecurity");
                    assertThat(context).hasSingleBean(FilterChainProxy.class);
                });
    }
}
