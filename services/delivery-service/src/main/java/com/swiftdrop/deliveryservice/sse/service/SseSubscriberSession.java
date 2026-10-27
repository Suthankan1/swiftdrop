package com.swiftdrop.deliveryservice.sse.service;

import com.swiftdrop.deliveryservice.sse.model.ParcelLifecycleSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Manages an individual active SSE subscriber session.
 * Tracks per-connection aggregate version state to prevent duplicate or out-of-order event delivery,
 * enforces token expiry boundary, and handles safe cleanup on disconnect.
 */
public class SseSubscriberSession {

    private static final Logger log = LoggerFactory.getLogger(SseSubscriberSession.class);

    private final UUID sessionId;
    private final UUID parcelId;
    private final String userSub;
    private final boolean courier;
    private final SseEmitter emitter;
    private final Instant connectedAt;
    private final Instant tokenExpiresAt;
    private final AtomicLong lastDeliveredVersion;
    private final AtomicBoolean closed;
    private final Consumer<SseSubscriberSession> cleanupCallback;

    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> expiryTask;

    public SseSubscriberSession(
            UUID parcelId,
            String userSub,
            boolean courier,
            SseEmitter emitter,
            Instant tokenExpiresAt,
            Consumer<SseSubscriberSession> cleanupCallback) {
        this.sessionId = UUID.randomUUID();
        this.parcelId = Objects.requireNonNull(parcelId, "parcelId cannot be null");
        this.userSub = Objects.requireNonNull(userSub, "userSub cannot be null");
        this.courier = courier;
        this.emitter = Objects.requireNonNull(emitter, "emitter cannot be null");
        this.connectedAt = Instant.now();
        this.tokenExpiresAt = Objects.requireNonNull(tokenExpiresAt, "tokenExpiresAt cannot be null");
        this.lastDeliveredVersion = new AtomicLong(0);
        this.closed = new AtomicBoolean(false);
        this.cleanupCallback = Objects.requireNonNull(cleanupCallback, "cleanupCallback cannot be null");
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getParcelId() {
        return parcelId;
    }

    public String getUserSub() {
        return userSub;
    }

    public boolean isCourier() {
        return courier;
    }

    public SseEmitter getEmitter() {
        return emitter;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public long getLastDeliveredVersion() {
        return lastDeliveredVersion.get();
    }

    public void setLastDeliveredVersion(long version) {
        this.lastDeliveredVersion.set(version);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void setHeartbeatTask(ScheduledFuture<?> heartbeatTask) {
        this.heartbeatTask = heartbeatTask;
    }

    public void setExpiryTask(ScheduledFuture<?> expiryTask) {
        this.expiryTask = expiryTask;
    }

    /**
     * Sends an authoritative status transition event to this subscriber.
     * Enforces monotonically increasing aggregate version checks and drops stale/duplicate events.
     *
     * @param event domain lifecycle event
     * @return true if successfully delivered, false if dropped or delivery failed
     */
    public boolean sendEvent(ParcelLifecycleSseEvent event) {
        if (closed.get()) {
            return false;
        }

        // 1. Verify token expiry boundary
        if (Instant.now().isAfter(tokenExpiresAt)) {
            log.info("SSE session {} expired for user {}. Terminating stream.", sessionId, userSub);
            close();
            return false;
        }

        // 2. Enforce version sequencing: drop duplicate or out-of-order earlier versions
        long currentVersion = lastDeliveredVersion.get();
        if (event.aggregateVersion() <= currentVersion) {
            log.debug("Dropping stale/duplicate event [version={}, lastDelivered={}] for session {}",
                    event.aggregateVersion(), currentVersion, sessionId);
            return false;
        }

        // 3. Detect version gap (missed events): log warning for client REST recovery
        if (currentVersion > 0 && event.aggregateVersion() > currentVersion + 1) {
            log.warn("Detected aggregate version gap for session {} on parcel {} [lastDelivered={}, received={}]. Emitting event; client must reconcile via canonical REST GET /api/v1/parcels/{}.",
                    sessionId, parcelId, currentVersion, event.aggregateVersion(), parcelId);
        }

        // 4. Emit SSE formatted message
        try {
            lastDeliveredVersion.set(event.aggregateVersion());
            emitter.send(SseEmitter.event()
                    .id(event.eventId().toString())
                    .name("parcel.status.changed")
                    .data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Failed to deliver SSE event to session {} (client likely disconnected): {}", sessionId, ex.getMessage());
            close();
            return false;
        }
    }

    /**
     * Sends a periodic heartbeat comment (:keepalive) to keep the HTTP connection alive
     * through intermediate proxies without buffering or timeout.
     */
    public boolean sendHeartbeat() {
        if (closed.get()) {
            return false;
        }

        if (Instant.now().isAfter(tokenExpiresAt)) {
            close();
            return false;
        }

        try {
            emitter.send(SseEmitter.event().comment("keepalive"));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Heartbeat failed for SSE session {}. Closing session.", sessionId);
            close();
            return false;
        }
    }

    /**
     * Idempotently closes this subscriber session, cancels scheduled tasks, and triggers cleanup.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(true);
            }
            if (expiryTask != null) {
                expiryTask.cancel(true);
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            cleanupCallback.accept(this);
        }
    }
}
