package com.swiftdrop.communicationsservice.webhook.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration enabling periodic background scheduling for webhook delivery when enabled.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "swiftdrop.webhooks.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebhookSchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebhookSchedulerConfiguration.class);

    public WebhookSchedulerConfiguration() {
        log.info("Webhook background scheduling enabled.");
    }
}
