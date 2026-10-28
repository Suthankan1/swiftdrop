package com.swiftdrop.deliveryservice.sse.service;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.sse.config.SseProperties;
import com.swiftdrop.deliveryservice.sse.exception.SseConnectionLimitExceededException;
import com.swiftdrop.deliveryservice.sse.model.ParcelLifecycleSseEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages active Server-Sent Events (SSE) connections for single-replica Delivery Service.
 * Tracks concurrency limits, schedules heartbeats and token-expiration termination,
 * re-validates courier assignment authorization before delivery, and routes Kafka lifecycle
 * events to in-memory parcel subscribers.
 */
@Service
public class ParcelSseConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ParcelSseConnectionManager.class);

    private final SseProperties properties;
    private final DeliveryAssignmentRepository assignmentRepository;

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseSubscriberSession>> parcelSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> userConnectionCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SseSubscriberSession> activeSessions = new ConcurrentHashMap<>();
    private final AtomicInteger globalConnectionCount = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            2,
            new CustomizableThreadFactory("sse-manager-")
    );

    private final MeterRegistry meterRegistry;

    @Autowired
    public ParcelSseConnectionManager(
            SseProperties properties,
            DeliveryAssignmentRepository assignmentRepository,
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(properties, assignmentRepository, meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null);
    }

    public ParcelSseConnectionManager(
            SseProperties properties,
            DeliveryAssignmentRepository assignmentRepository,
            MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.assignmentRepository = Objects.requireNonNull(assignmentRepository, "assignmentRepository cannot be null");
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            Gauge.builder("swiftdrop.sse.connections.active", activeSessions, ConcurrentHashMap::size)
                    .description("Current active SSE connections")
                    .register(meterRegistry);
            meterRegistry.counter("swiftdrop.sse.connections.accepted.total");
            meterRegistry.counter("swiftdrop.sse.connections.closed.total");
        }
    }

    public ParcelSseConnectionManager(SseProperties properties, DeliveryAssignmentRepository assignmentRepository) {
        this(properties, assignmentRepository, (MeterRegistry) null);
    }

    @PostConstruct
    public void startHeartbeatScheduler() {
        int interval = Math.max(1, properties.getHeartbeatIntervalSeconds());
        scheduler.scheduleWithFixedDelay(
                this::sendHeartbeats,
                interval,
                interval,
                TimeUnit.SECONDS
        );
        log.info("Started SSE heartbeat scheduler at interval {}s", interval);
    }

    /**
     * Registers an authenticated subscriber session for the given parcel.
     * Enforces global, per-user, and per-parcel connection limits.
     *
     * @param parcelId target parcel identifier
     * @param userSub Keycloak JWT subject (caller identity)
     * @param isCourier whether caller is authenticated as assigned courier
     * @param tokenExpiresAt JWT expiration timestamp
     * @return configured SseEmitter
     */
    public SseEmitter registerSession(UUID parcelId, String userSub, boolean isCourier, Instant tokenExpiresAt) {
        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(userSub, "userSub cannot be null");
        Objects.requireNonNull(tokenExpiresAt, "tokenExpiresAt cannot be null");

        Instant now = Instant.now();
        if (!tokenExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Cannot establish SSE stream with already expired JWT token");
        }

        // 1. Enforce global connection limit
        int currentGlobal = globalConnectionCount.incrementAndGet();
        if (currentGlobal > properties.getMaxConnections()) {
            globalConnectionCount.decrementAndGet();
            recordRejected("limit_global");
            log.warn("Rejected SSE connection: global limit {} reached", properties.getMaxConnections());
            throw new SseConnectionLimitExceededException(
                    "GLOBAL",
                    properties.getMaxConnections(),
                    "Global SSE connection limit reached (max: " + properties.getMaxConnections() + ")"
            );
        }

        // 2. Enforce per-user connection limit
        AtomicInteger userCount = userConnectionCounts.computeIfAbsent(userSub, k -> new AtomicInteger(0));
        int currentUserCount = userCount.incrementAndGet();
        if (currentUserCount > properties.getMaxConnectionsPerUser()) {
            userCount.decrementAndGet();
            globalConnectionCount.decrementAndGet();
            recordRejected("limit_user");
            log.warn("Rejected SSE connection for user {}: user limit {} reached", userSub, properties.getMaxConnectionsPerUser());
            throw new SseConnectionLimitExceededException(
                    "USER",
                    properties.getMaxConnectionsPerUser(),
                    "Per-user SSE connection limit reached (max: " + properties.getMaxConnectionsPerUser() + ")"
            );
        }

        // 3. Enforce per-parcel connection limit
        CopyOnWriteArrayList<SseSubscriberSession> subscribers =
                parcelSubscribers.computeIfAbsent(parcelId, k -> new CopyOnWriteArrayList<>());
        synchronized (subscribers) {
            if (subscribers.size() >= properties.getMaxConnectionsPerParcel()) {
                userCount.decrementAndGet();
                globalConnectionCount.decrementAndGet();
                recordRejected("limit_parcel");
                log.warn("Rejected SSE connection for parcel {}: parcel limit {} reached", parcelId, properties.getMaxConnectionsPerParcel());
                throw new SseConnectionLimitExceededException(
                        "PARCEL",
                        properties.getMaxConnectionsPerParcel(),
                        "Per-parcel SSE connection limit reached (max: " + properties.getMaxConnectionsPerParcel() + ")"
                );
            }
        }

        // 4. Calculate timeout bounded by properties and token expiration
        long tokenRemainingMs = Math.max(1000L, Duration.between(now, tokenExpiresAt).toMillis());
        long configuredTimeoutMs = Math.max(1000L, properties.getConnectionTimeoutSeconds() * 1000L);
        long sessionTimeoutMs = Math.min(configuredTimeoutMs, tokenRemainingMs);

        SseEmitter emitter = new SseEmitter(sessionTimeoutMs);
        SseSubscriberSession session = new SseSubscriberSession(
                parcelId,
                userSub,
                isCourier,
                emitter,
                tokenExpiresAt,
                this::unregisterSession
        );

        // 5. Wire emitter callbacks
        emitter.onCompletion(session::close);
        emitter.onTimeout(session::close);
        emitter.onError(t -> {
            log.debug("SSE emitter error on session {}: {}", session.getSessionId(), t.getMessage());
            session.close();
        });

        // 6. Schedule token expiration task
        ScheduledFuture<?> expiryFuture = scheduler.schedule(
                () -> {
                    log.info("SSE session {} reached token expiration. Closing stream.", session.getSessionId());
                    session.close();
                },
                tokenRemainingMs,
                TimeUnit.MILLISECONDS
        );
        session.setExpiryTask(expiryFuture);

        // 7. Store session in subscriber registry
        activeSessions.put(session.getSessionId(), session);
        subscribers.add(session);

        // 8. Send initial keepalive comment to confirm connection to client
        session.sendHeartbeat();
        recordAccepted();

        log.info("Registered SSE session {} for parcel {} (user: {}, isCourier: {}, activeParcelSubscribers: {}, totalGlobal: {})",
                session.getSessionId(), parcelId, userSub, isCourier, subscribers.size(), globalConnectionCount.get());

        return emitter;
    }

    /**
     * Registers an anonymous public subscriber session for a parcel's public tracking reference.
     * Enforces global and per-parcel connection limits, sets idle/connection timeout,
     * wires heartbeat pings, and returns an unauthenticated SseEmitter.
     *
     * @param parcelId target parcel identifier
     * @param trackingNumber public tracking reference (e.g. SWD-20261023-A1B2C3)
     * @return configured SseEmitter
     */
    public SseEmitter registerPublicSession(UUID parcelId, String trackingNumber) {
        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(trackingNumber, "trackingNumber cannot be null");

        // 1. Enforce global connection limit
        int currentGlobal = globalConnectionCount.incrementAndGet();
        if (currentGlobal > properties.getMaxConnections()) {
            globalConnectionCount.decrementAndGet();
            recordRejected("limit_global");
            log.warn("Rejected public SSE connection: global limit {} reached", properties.getMaxConnections());
            throw new SseConnectionLimitExceededException(
                    "GLOBAL",
                    properties.getMaxConnections(),
                    "Global SSE connection limit reached (max: " + properties.getMaxConnections() + ")"
            );
        }

        // 2. Enforce per-parcel connection limit
        CopyOnWriteArrayList<SseSubscriberSession> subscribers =
                parcelSubscribers.computeIfAbsent(parcelId, k -> new CopyOnWriteArrayList<>());
        synchronized (subscribers) {
            if (subscribers.size() >= properties.getMaxConnectionsPerParcel()) {
                globalConnectionCount.decrementAndGet();
                recordRejected("limit_parcel");
                log.warn("Rejected public SSE connection for tracking {}: parcel limit {} reached",
                        trackingNumber, properties.getMaxConnectionsPerParcel());
                throw new SseConnectionLimitExceededException(
                        "PARCEL",
                        properties.getMaxConnectionsPerParcel(),
                        "Per-parcel SSE connection limit reached (max: " + properties.getMaxConnectionsPerParcel() + ")"
                );
            }
        }

        // 3. Configure bounded session timeout
        long sessionTimeoutMs = Math.max(1000L, properties.getConnectionTimeoutSeconds() * 1000L);
        Instant timeoutExpiresAt = Instant.now().plusSeconds(properties.getConnectionTimeoutSeconds());

        SseEmitter emitter = new SseEmitter(sessionTimeoutMs);
        SseSubscriberSession session = new SseSubscriberSession(
                parcelId,
                "public:" + trackingNumber,
                false,
                true,
                emitter,
                timeoutExpiresAt,
                this::unregisterSession
        );

        // 4. Wire emitter completion and timeout callbacks
        emitter.onCompletion(session::close);
        emitter.onTimeout(session::close);
        emitter.onError(t -> {
            log.debug("Public SSE emitter error on session {}: {}", session.getSessionId(), t.getMessage());
            session.close();
        });

        // 5. Schedule timeout cleanup task
        ScheduledFuture<?> expiryFuture = scheduler.schedule(
                () -> {
                    log.info("Public SSE session {} reached connection timeout. Closing stream.", session.getSessionId());
                    session.close();
                },
                sessionTimeoutMs,
                TimeUnit.MILLISECONDS
        );
        session.setExpiryTask(expiryFuture);

        // 6. Register active session
        activeSessions.put(session.getSessionId(), session);
        subscribers.add(session);

        // 7. Send initial keepalive comment to client
        session.sendHeartbeat();
        recordAccepted();

        log.info("Registered public SSE session {} for tracking {} (activeParcelSubscribers: {}, totalGlobal: {})",
                session.getSessionId(), trackingNumber, subscribers.size(), globalConnectionCount.get());

        return emitter;
    }

    /**
     * Unregisters and cleans up a subscriber session.
     */
    void unregisterSession(SseSubscriberSession session) {
        if (session == null) {
            return;
        }

        UUID sessionId = session.getSessionId();
        if (activeSessions.remove(sessionId) != null) {
            UUID parcelId = session.getParcelId();
            String userSub = session.getUserSub();

            CopyOnWriteArrayList<SseSubscriberSession> subscribers = parcelSubscribers.get(parcelId);
            if (subscribers != null) {
                subscribers.remove(session);
                if (subscribers.isEmpty()) {
                    parcelSubscribers.remove(parcelId, subscribers);
                }
            }

            if (!session.isPublic()) {
                userConnectionCounts.computeIfPresent(userSub, (k, count) -> {
                    int val = count.decrementAndGet();
                    return val <= 0 ? null : count;
                });
            }

            int remainingGlobal = globalConnectionCount.decrementAndGet();
            recordClosed();
            log.info("Unregistered SSE session {} for parcel {} (isPublic: {}, remainingGlobal: {})",
                    sessionId, parcelId, session.isPublic(), Math.max(0, remainingGlobal));
        }
    }

    private void recordAccepted() {
        if (meterRegistry != null) {
            meterRegistry.counter("swiftdrop.sse.connections.accepted.total").increment();
        }
    }

    private void recordRejected(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("swiftdrop.sse.connections.rejected.total", "reason", reason).increment();
        }
    }

    private void recordClosed() {
        if (meterRegistry != null) {
            meterRegistry.counter("swiftdrop.sse.connections.closed.total").increment();
        }
    }

    /**
     * Broadcasts a validated lifecycle status transition event to all active subscribers for the parcel.
     * Rechecks courier assignment if subscriber is a courier to ensure revoked couriers receive no events.
     * For public subscribers, translates event to strictly redacted PublicTrackingSseEvent.
     *
     * @param parcelId target parcel ID
     * @param event domain SSE payload
     */
    public void broadcastToParcel(UUID parcelId, ParcelLifecycleSseEvent event) {
        if (parcelId == null || event == null) {
            return;
        }

        CopyOnWriteArrayList<SseSubscriberSession> subscribers = parcelSubscribers.get(parcelId);
        if (subscribers == null || subscribers.isEmpty()) {
            log.debug("No active SSE subscribers for parcel {}", parcelId);
            return;
        }

        log.debug("Broadcasting lifecycle event [status={}, version={}] to {} subscribers for parcel {}",
                event.newStatus(), event.aggregateVersion(), subscribers.size(), parcelId);

        for (SseSubscriberSession session : subscribers) {
            if (session.isClosed()) {
                continue;
            }

            if (session.isPublic()) {
                String coarseZone = event.cityOrZone() != null ? event.cityOrZone() : coarseZoneForStatus(event.newStatus());
                com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingSseEvent publicEvent =
                        new com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingSseEvent(
                                event.eventId(),
                                event.trackingNumber(),
                                event.aggregateVersion(),
                                event.oldStatus(),
                                event.newStatus(),
                                coarseZone,
                                event.occurredAt()
                        );
                session.sendPublicEvent(publicEvent);
                continue;
            }

            // Re-validate courier assignment if subscriber connected with courier role
            if (session.isCourier()) {
                boolean courierActive = assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(
                        parcelId,
                        session.getUserSub()
                );
                if (!courierActive) {
                    log.info("Revoking SSE session {} - courier {} is no longer active assignee for parcel {}",
                            session.getSessionId(), session.getUserSub(), parcelId);
                    session.close();
                    continue;
                }
            }

            session.sendEvent(event);
        }
    }

    public static String coarseZoneForStatus(String statusStr) {
        if (statusStr == null) {
            return "Western Province Hub";
        }
        return switch (statusStr) {
            case "CREATED" -> "Order Confirmed (Colombo)";
            case "PICKED_UP" -> "Picked Up (Western Province Sorting Hub)";
            case "IN_TRANSIT" -> "In Transit (Western Province Sorting Hub)";
            case "OUT_FOR_DELIVERY" -> "Out for Delivery (Local Zone)";
            case "DELIVERED" -> "Delivered";
            case "FAILED" -> "Delivery Attempted (Western Province)";
            default -> "Transit Checkpoint (" + statusStr + ")";
        };
    }

    /**
     * Sends keepalive heartbeats to all active sessions.
     */
    private void sendHeartbeats() {
        if (activeSessions.isEmpty()) {
            return;
        }
        for (SseSubscriberSession session : activeSessions.values()) {
            if (!session.isClosed()) {
                session.sendHeartbeat();
            }
        }
    }

    public int getGlobalConnectionCount() {
        return globalConnectionCount.get();
    }

    public int getActiveCountForParcel(UUID parcelId) {
        CopyOnWriteArrayList<SseSubscriberSession> subscribers = parcelSubscribers.get(parcelId);
        return subscribers != null ? subscribers.size() : 0;
    }

    public int getActiveCountForUser(String userSub) {
        AtomicInteger count = userConnectionCounts.get(userSub);
        return count != null ? count.get() : 0;
    }

    public List<SseSubscriberSession> getSubscribersForParcel(UUID parcelId) {
        CopyOnWriteArrayList<SseSubscriberSession> subscribers = parcelSubscribers.get(parcelId);
        return subscribers != null ? Collections.unmodifiableList(subscribers) : Collections.emptyList();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SSE Connection Manager, closing {} active sessions...", activeSessions.size());
        for (SseSubscriberSession session : activeSessions.values()) {
            session.close();
        }
        activeSessions.clear();
        parcelSubscribers.clear();
        userConnectionCounts.clear();
        globalConnectionCount.set(0);

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
