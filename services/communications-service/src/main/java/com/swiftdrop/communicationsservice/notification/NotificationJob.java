package com.swiftdrop.communicationsservice.notification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationJob {

    @Id
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "recipient_sub", nullable = false, length = 255)
    private String recipientSub;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    private NotificationChannel channel;

    @Column(name = "destination_ref", nullable = false, length = 255)
    private String destinationRef;

    @Column(name = "template", nullable = false, length = 100)
    private String template;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    public NotificationJob() {
    }

    public NotificationJob(UUID notificationId, UUID eventId, String recipientSub, UUID parcelId,
                           NotificationChannel channel, String destinationRef, String template) {
        this.notificationId = notificationId != null ? notificationId : UUID.randomUUID();
        this.eventId = eventId;
        this.recipientSub = recipientSub;
        this.parcelId = parcelId;
        this.channel = channel;
        this.destinationRef = destinationRef;
        this.template = template;
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    public void prePersist() {
        if (this.notificationId == null) {
            this.notificationId = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(UUID notificationId) {
        this.notificationId = notificationId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getRecipientSub() {
        return recipientSub;
    }

    public void setRecipientSub(String recipientSub) {
        this.recipientSub = recipientSub;
    }

    public UUID getParcelId() {
        return parcelId;
    }

    public void setParcelId(UUID parcelId) {
        this.parcelId = parcelId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
    }

    public String getDestinationRef() {
        return destinationRef;
    }

    public void setDestinationRef(String destinationRef) {
        this.destinationRef = destinationRef;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationStatus status) {
        this.status = status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
