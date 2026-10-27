package com.swiftdrop.communicationsservice.webhook.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSchedulerPropertiesTest {

    @Test
    @DisplayName("WebhookSchedulerProperties has sensible production defaults")
    void defaults_AreCorrect() {
        WebhookSchedulerProperties props = new WebhookSchedulerProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getPollIntervalMs()).isEqualTo(5000L);
        assertThat(props.getRecoveryIntervalMs()).isEqualTo(60000L);
        assertThat(props.getLeaseTimeoutSeconds()).isEqualTo(300L);
        assertThat(props.getBatchSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("WebhookSchedulerProperties mutators update values correctly")
    void mutators_UpdateValues() {
        WebhookSchedulerProperties props = new WebhookSchedulerProperties();

        props.setEnabled(false);
        props.setPollIntervalMs(10000L);
        props.setRecoveryIntervalMs(120000L);
        props.setLeaseTimeoutSeconds(600L);
        props.setBatchSize(100);

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getPollIntervalMs()).isEqualTo(10000L);
        assertThat(props.getRecoveryIntervalMs()).isEqualTo(120000L);
        assertThat(props.getLeaseTimeoutSeconds()).isEqualTo(600L);
        assertThat(props.getBatchSize()).isEqualTo(100);
    }
}
