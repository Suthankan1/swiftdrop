package com.swiftdrop.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for SwiftDrop Pricing & Zones microservice.
 *
 * Owns pricing_db, zone hierarchies, immutable rate cards,
 * and deterministic delivery fee calculations.
 */
@SpringBootApplication
public class PricingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PricingServiceApplication.class, args);
    }
}
