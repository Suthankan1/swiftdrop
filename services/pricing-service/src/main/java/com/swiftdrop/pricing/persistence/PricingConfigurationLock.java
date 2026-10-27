package com.swiftdrop.pricing.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Common first lock for configuration writes and quote issuance. Released at transaction end. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class PricingConfigurationLock {
    private final JdbcTemplate jdbc;

    public PricingConfigurationLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void forCommand() {
        jdbc.queryForObject("SELECT id FROM pricing_configuration_lock WHERE id = 1 FOR UPDATE", Short.class);
    }

    public void forQuote() {
        jdbc.queryForObject("SELECT id FROM pricing_configuration_lock WHERE id = 1 FOR SHARE", Short.class);
    }
}
