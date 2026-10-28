package com.swiftdrop.deliveryservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryConfigurationNegativeTest {

    @Test
    @DisplayName("Application fails startup when datasource configuration is completely missing")
    void startupFailsWhenDatasourceConfigurationMissing() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(DeliveryServiceApplication.class)
                .properties(
                        "server.port=0",
                        "spring.flyway.enabled=false"
                );

        assertThatThrownBy(builder::run)
                .isNotNull();
    }

    @Test
    @DisplayName("Application fails startup when PostgreSQL database is unreachable")
    void startupFailsWhenPostgresDatabaseUnreachable() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(DeliveryServiceApplication.class)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=jdbc:postgresql://localhost:59998/non_existent_delivery_db",
                        "spring.datasource.username=invalid_user",
                        "spring.datasource.password=invalid_pass",
                        "spring.datasource.hikari.connection-timeout=1000",
                        "spring.flyway.enabled=true"
                );

        assertThatThrownBy(builder::run)
                .isNotNull();
    }
}
