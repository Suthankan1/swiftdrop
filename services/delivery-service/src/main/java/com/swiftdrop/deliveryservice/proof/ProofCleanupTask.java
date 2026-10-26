package com.swiftdrop.deliveryservice.proof;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable record for orphan storage object cleanup compensation.
 * Created when synchronous compensation fails, ensuring safe background retry.
 */
@Entity
@Table(name = "proof_cleanup_tasks")
public class ProofCleanupTask {

    @Id
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "object_reference", nullable = false, length = 128)
    private String objectReference;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    protected ProofCleanupTask() {
    }

    public ProofCleanupTask(UUID taskId, String objectReference, String reason) {
        this.taskId = Objects.requireNonNull(taskId, "taskId cannot be null");
        this.objectReference = Objects.requireNonNull(objectReference, "objectReference cannot be null");
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
        this.status = "PENDING";
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getObjectReference() {
        return objectReference;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetry() {
        this.retryCount++;
        this.lastAttemptAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }
}
