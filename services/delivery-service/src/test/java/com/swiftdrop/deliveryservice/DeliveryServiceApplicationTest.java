package com.swiftdrop.deliveryservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryServiceApplicationTest {

    @Test
    @DisplayName("DeliveryServiceApplication declares independent SpringBootApplication entry point")
    void declaresIndependentBootApplication() {
        assertThat(DeliveryServiceApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }
}
