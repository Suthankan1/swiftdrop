package com.swiftdrop.deliveryservice;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * SwiftDrop Delivery Operations Service Application Entry Point.
 *
 * Owns canonical parcel lifecycle states, immutable status audit history,
 * courier assignments, and transactional outbox event persistence.
 */
@SpringBootApplication
public class DeliveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
