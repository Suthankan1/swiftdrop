package com.swiftdrop.communicationsservice.notification.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRetryPropertiesTest {

    @Test
    @DisplayName("Default properties pass validation successfully")
    void testDefaults_Valid() {
        NotificationRetryProperties props = new NotificationRetryProperties();
        props.validate();

        assertThat(props.getMaxAttempts()).isEqualTo(5);
        assertThat(props.getBaseBackoffSeconds()).isEqualTo(5);
        assertThat(props.getMaxBackoffSeconds()).isEqualTo(300);
        assertThat(props.isJitterEnabled()).isFalse();
        assertThat(props.getJitterFactor()).isEqualTo(0.2);
        assertThat(props.getTransportTimeoutMs()).isEqualTo(5000);
    }

    @Test
    @DisplayName("Zero or negative max attempts fails validation")
    void testInvalidMaxAttempts_Throws() {
        NotificationRetryProperties props = new NotificationRetryProperties();
        props.setMaxAttempts(0);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    @DisplayName("Base backoff less than 1 fails validation")
    void testInvalidBaseBackoff_Throws() {
        NotificationRetryProperties props = new NotificationRetryProperties();
        props.setBaseBackoffSeconds(0);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseBackoffSeconds");
    }

    @Test
    @DisplayName("Max backoff less than base backoff fails validation")
    void testMaxLessThanBase_Throws() {
        NotificationRetryProperties props = new NotificationRetryProperties();
        props.setBaseBackoffSeconds(30);
        props.setMaxBackoffSeconds(10);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoffSeconds must be >=");
    }

    @Test
    @DisplayName("Jitter factor outside 0.0-1.0 fails validation")
    void testInvalidJitterFactor_Throws() {
        NotificationRetryProperties props = new NotificationRetryProperties();
        props.setJitterFactor(1.5);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitterFactor");
    }
}
