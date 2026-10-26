package com.swiftdrop.pricing.config;

import com.swiftdrop.pricing.domain.PricingStrategy;
import com.swiftdrop.pricing.domain.TieredWeightPricingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration registering core pricing strategy and algorithm beans.
 */
@Configuration(proxyBeanMethods = false)
public class PricingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PricingStrategy pricingStrategy() {
        return new TieredWeightPricingStrategy();
    }
}
