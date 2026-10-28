package com.swiftdrop.deliveryservice.sse.service;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.sse.config.SseProperties;
import com.swiftdrop.deliveryservice.sse.exception.SseConnectionLimitExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ParcelSseMetricsTest {

    @Mock
    private DeliveryAssignmentRepository assignmentRepository;

    private SseProperties properties;
    private MeterRegistry registry;
    private ParcelSseConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        properties = new SseProperties();
        properties.setMaxConnections(2);
        properties.setMaxConnectionsPerUser(1);
        properties.setMaxConnectionsPerParcel(2);
        properties.setHeartbeatIntervalSeconds(15);
        properties.setConnectionTimeoutSeconds(1800);

        registry = new SimpleMeterRegistry();
        connectionManager = new ParcelSseConnectionManager(properties, assignmentRepository, registry);
    }

    @AfterEach
    void tearDown() {
        connectionManager.shutdown();
    }

    @Test
    @DisplayName("Active connections gauge increases on connect and decreases on disconnect")
    void activeConnectionsGauge_UpdatesAccurately() {
        UUID parcelId = UUID.randomUUID();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);

        double initialActive = registry.get("swiftdrop.sse.connections.active").gauge().value();
        assertThat(initialActive).isEqualTo(0.0);

        SseEmitter emitter = connectionManager.registerSession(parcelId, "user-1", false, expiry);

        double activeAfterRegister = registry.get("swiftdrop.sse.connections.active").gauge().value();
        double acceptedTotal = registry.get("swiftdrop.sse.connections.accepted.total").counter().count();

        assertThat(activeAfterRegister).isEqualTo(1.0);
        assertThat(acceptedTotal).isEqualTo(1.0);

        // Close session
        var subscribers = connectionManager.getSubscribersForParcel(parcelId);
        assertThat(subscribers).hasSize(1);
        subscribers.get(0).close();

        double activeAfterClose = registry.get("swiftdrop.sse.connections.active").gauge().value();
        double closedTotal = registry.get("swiftdrop.sse.connections.closed.total").counter().count();

        assertThat(activeAfterClose).isEqualTo(0.0);
        assertThat(closedTotal).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Rejected connections increment bounded counter with reason tag")
    void rejectedConnections_IncrementReasonCounter() {
        UUID parcelId = UUID.randomUUID();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);

        // First connection succeeds
        connectionManager.registerSession(parcelId, "user-1", false, expiry);

        // Second connection with same user exceeds maxConnectionsPerUser=1
        assertThatThrownBy(() -> connectionManager.registerSession(parcelId, "user-1", false, expiry))
                .isInstanceOf(SseConnectionLimitExceededException.class);

        double rejectedUserLimit = registry.get("swiftdrop.sse.connections.rejected.total")
                .tag("reason", "limit_user").counter().count();
        assertThat(rejectedUserLimit).isEqualTo(1.0);

        // Active gauge remains 1
        double active = registry.get("swiftdrop.sse.connections.active").gauge().value();
        assertThat(active).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Repeated cleanup on same session does not decrement gauge below zero")
    void doubleCleanup_DoesNotProduceNegativeGauge() {
        UUID parcelId = UUID.randomUUID();
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);

        connectionManager.registerSession(parcelId, "user-1", false, expiry);
        var subscribers = connectionManager.getSubscribersForParcel(parcelId);
        assertThat(subscribers).hasSize(1);
        SseSubscriberSession session = subscribers.get(0);
        session.close();
        session.close();

        double active = registry.get("swiftdrop.sse.connections.active").gauge().value();
        double closed = registry.get("swiftdrop.sse.connections.closed.total").counter().count();

        assertThat(active).isEqualTo(0.0);
        assertThat(closed).isEqualTo(1.0);
    }
}
