package com.swiftdrop.deliveryservice.sse;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.sse.config.SseProperties;
import com.swiftdrop.deliveryservice.sse.exception.SseConnectionLimitExceededException;
import com.swiftdrop.deliveryservice.sse.model.ParcelLifecycleSseEvent;
import com.swiftdrop.deliveryservice.sse.service.ParcelSseConnectionManager;
import com.swiftdrop.deliveryservice.sse.service.SseSubscriberSession;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParcelSseReliabilityTest {

    @Mock
    private DeliveryAssignmentRepository assignmentRepository;

    private SseProperties properties;
    private ParcelSseConnectionManager connectionManager;

    private static final UUID PARCEL_A = UUID.randomUUID();
    private static final UUID PARCEL_B = UUID.randomUUID();
    private static final String USER_1 = "user-alice";
    private static final String USER_2 = "user-bob";
    private static final String COURIER_1 = "courier-charlie";

    @BeforeEach
    void setUp() {
        properties = new SseProperties();
        properties.setMaxConnections(5);
        properties.setMaxConnectionsPerUser(2);
        properties.setMaxConnectionsPerParcel(2);
        properties.setHeartbeatIntervalSeconds(15);
        properties.setConnectionTimeoutSeconds(1800);

        connectionManager = new ParcelSseConnectionManager(properties, assignmentRepository);
    }

    @AfterEach
    void tearDown() {
        connectionManager.shutdown();
    }

    private ParcelLifecycleSseEvent createEvent(UUID parcelId, long version, String newStatus) {
        return new ParcelLifecycleSseEvent(
                UUID.randomUUID(),
                parcelId,
                "SWD-20261027-TEST",
                version,
                "CREATED",
                newStatus,
                null,
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. SseSubscriberSession enforces strict monotonic aggregate version sequencing (drops duplicate and stale events)")
    void testVersionDeduplication() {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        AtomicBoolean cleanedUp = new AtomicBoolean(false);

        SseSubscriberSession session = new SseSubscriberSession(
                PARCEL_A,
                USER_1,
                false,
                mockEmitter,
                Instant.now().plus(1, ChronoUnit.HOURS),
                s -> cleanedUp.set(true)
        );

        // 1. First event: version 2 -> accepted
        ParcelLifecycleSseEvent eventV2 = createEvent(PARCEL_A, 2L, "PICKED_UP");
        boolean deliveredV2 = session.sendEvent(eventV2);
        assertThat(deliveredV2).isTrue();
        assertThat(session.getLastDeliveredVersion()).isEqualTo(2L);

        // 2. Duplicate event: version 2 -> dropped
        boolean duplicateDelivered = session.sendEvent(eventV2);
        assertThat(duplicateDelivered).isFalse();
        assertThat(session.getLastDeliveredVersion()).isEqualTo(2L);

        // 3. Stale event: version 1 -> dropped
        ParcelLifecycleSseEvent eventV1 = createEvent(PARCEL_A, 1L, "CREATED");
        boolean staleDelivered = session.sendEvent(eventV1);
        assertThat(staleDelivered).isFalse();
        assertThat(session.getLastDeliveredVersion()).isEqualTo(2L);

        // 4. Newer event: version 3 -> accepted
        ParcelLifecycleSseEvent eventV3 = createEvent(PARCEL_A, 3L, "IN_TRANSIT");
        boolean deliveredV3 = session.sendEvent(eventV3);
        assertThat(deliveredV3).isTrue();
        assertThat(session.getLastDeliveredVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("2. SseSubscriberSession drops events and terminates when token expires")
    void testTokenExpirationTermination() {
        SseEmitter mockEmitter = mock(SseEmitter.class);
        AtomicBoolean cleanedUp = new AtomicBoolean(false);

        // Session created with token expiring in the past
        SseSubscriberSession session = new SseSubscriberSession(
                PARCEL_A,
                USER_1,
                false,
                mockEmitter,
                Instant.now().minus(5, ChronoUnit.SECONDS),
                s -> cleanedUp.set(true)
        );

        ParcelLifecycleSseEvent event = createEvent(PARCEL_A, 1L, "PICKED_UP");
        boolean delivered = session.sendEvent(event);

        assertThat(delivered).isFalse();
        assertThat(session.isClosed()).isTrue();
        assertThat(cleanedUp.get()).isTrue();
    }

    @Test
    @DisplayName("3. SseSubscriberSession sends keepalive comment heartbeat")
    void testHeartbeatEmission() throws Exception {
        SseEmitter mockEmitter = mock(SseEmitter.class);

        SseSubscriberSession session = new SseSubscriberSession(
                PARCEL_A,
                USER_1,
                false,
                mockEmitter,
                Instant.now().plus(1, ChronoUnit.HOURS),
                s -> {}
        );

        boolean heartbeatSent = session.sendHeartbeat();
        assertThat(heartbeatSent).isTrue();
        verify(mockEmitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("4. ParcelSseConnectionManager enforces global connection limit")
    void testGlobalConnectionLimit() {
        properties.setMaxConnections(2);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        connectionManager.registerSession(PARCEL_A, "user-a", false, future);
        connectionManager.registerSession(PARCEL_B, "user-b", false, future);

        assertThat(connectionManager.getGlobalConnectionCount()).isEqualTo(2);

        // 3rd attempt exceeds global limit 2
        assertThatThrownBy(() -> connectionManager.registerSession(PARCEL_A, "user-c", false, future))
                .isInstanceOf(SseConnectionLimitExceededException.class)
                .hasMessageContaining("Global SSE connection limit reached");

        assertThat(connectionManager.getGlobalConnectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("5. ParcelSseConnectionManager enforces per-user connection limit")
    void testPerUserConnectionLimit() {
        properties.setMaxConnections(10);
        properties.setMaxConnectionsPerUser(2);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        connectionManager.registerSession(PARCEL_A, USER_1, false, future);
        connectionManager.registerSession(PARCEL_B, USER_1, false, future);

        assertThat(connectionManager.getActiveCountForUser(USER_1)).isEqualTo(2);

        // 3rd connection for same user exceeds limit
        assertThatThrownBy(() -> connectionManager.registerSession(UUID.randomUUID(), USER_1, false, future))
                .isInstanceOf(SseConnectionLimitExceededException.class)
                .hasMessageContaining("Per-user SSE connection limit reached");

        assertThat(connectionManager.getActiveCountForUser(USER_1)).isEqualTo(2);
    }

    @Test
    @DisplayName("6. ParcelSseConnectionManager enforces per-parcel connection limit")
    void testPerParcelConnectionLimit() {
        properties.setMaxConnections(10);
        properties.setMaxConnectionsPerParcel(2);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        connectionManager.registerSession(PARCEL_A, USER_1, false, future);
        connectionManager.registerSession(PARCEL_A, USER_2, false, future);

        assertThat(connectionManager.getActiveCountForParcel(PARCEL_A)).isEqualTo(2);

        // 3rd connection for same parcel exceeds limit
        assertThatThrownBy(() -> connectionManager.registerSession(PARCEL_A, "user-3", false, future))
                .isInstanceOf(SseConnectionLimitExceededException.class)
                .hasMessageContaining("Per-parcel SSE connection limit reached");

        assertThat(connectionManager.getActiveCountForParcel(PARCEL_A)).isEqualTo(2);
    }

    @Test
    @DisplayName("7. Courier reassignment / revocation drops courier session dynamically upon broadcast")
    void testCourierRevocationDynamicDrop() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        // Register courier session
        when(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(PARCEL_A, COURIER_1)).thenReturn(false);

        connectionManager.registerSession(PARCEL_A, COURIER_1, true, future);
        assertThat(connectionManager.getActiveCountForParcel(PARCEL_A)).isEqualTo(1);

        // Broadcast event: connectionManager rechecks courier assignment and finds courier no longer active
        ParcelLifecycleSseEvent event = createEvent(PARCEL_A, 1L, "OUT_FOR_DELIVERY");
        connectionManager.broadcastToParcel(PARCEL_A, event);

        // Courier session must be closed and removed from subscribers
        assertThat(connectionManager.getActiveCountForParcel(PARCEL_A)).isEqualTo(0);
    }

    @Test
    @DisplayName("8. Client disconnect / cleanup decrements active counts cleanly")
    void testCleanSessionUnregister() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        connectionManager.registerSession(PARCEL_A, USER_1, false, future);
        assertThat(connectionManager.getGlobalConnectionCount()).isEqualTo(1);
        assertThat(connectionManager.getActiveCountForParcel(PARCEL_A)).isEqualTo(1);
        assertThat(connectionManager.getActiveCountForUser(USER_1)).isEqualTo(1);

        // Retrieve and close the session
        var subscribers = connectionManager.getSubscribersForParcel(PARCEL_A);
        assertThat(subscribers).hasSize(1);
        subscribers.get(0).close();

        // Counters must be updated
        assertThat(connectionManager.getGlobalConnectionCount()).isEqualTo(0);
        assertThat(connectionManager.getActiveCountForParcel(PARCEL_A)).isEqualTo(0);
        assertThat(connectionManager.getActiveCountForUser(USER_1)).isEqualTo(0);
    }
}
