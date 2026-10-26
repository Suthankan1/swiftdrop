package com.swiftdrop.deliveryservice.proof;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated transactional coordinator for durable proof cleanup task persistence and status updates.
 *
 * <p>SAFETY INVARIANTS:
 * 1. Status updates execute in an isolated {@link Propagation#REQUIRES_NEW} transaction so that
 *    post-commit {@code afterCommit} hooks and rollback compensation hooks genuinely commit their database changes.
 * 2. Database transactions are isolated from the main proof upload/replacement transaction.
 * 3. Raw storage keys and private object references are NEVER logged.
 * 4. Concurrent task updates acquire pessimistic row locks to prevent race conditions.</p>
 */
@Service
public class ProofCleanupTaskCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProofCleanupTaskCoordinator.class);

    private final ProofCleanupTaskRepository cleanupTaskRepository;

    @Autowired
    public ProofCleanupTaskCoordinator(ProofCleanupTaskRepository cleanupTaskRepository) {
        this.cleanupTaskRepository = Objects.requireNonNull(cleanupTaskRepository, "cleanupTaskRepository cannot be null");
    }

    /**
     * Persists a new durable cleanup task in an isolated new transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProofCleanupTask recordDurableCleanup(String objectReference, String reason) {
        Objects.requireNonNull(objectReference, "objectReference cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        ProofCleanupTask task = new ProofCleanupTask(UUID.randomUUID(), objectReference, reason);
        ProofCleanupTask saved = cleanupTaskRepository.saveAndFlush(task);
        log.info("Recorded durable proof cleanup task {} for reason: {}", saved.getTaskId(), reason);
        return saved;
    }

    /**
     * Records task status as COMPLETED in an isolated new transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID taskId) {
        if (taskId == null) {
            return;
        }
        Optional<ProofCleanupTask> taskOpt = cleanupTaskRepository.findByIdForUpdate(taskId);
        if (taskOpt.isPresent()) {
            ProofCleanupTask task = taskOpt.get();
            task.setStatus("COMPLETED");
            cleanupTaskRepository.saveAndFlush(task);
            log.info("Cleanup task {} marked as COMPLETED", taskId);
        } else {
            cleanupTaskRepository.findById(taskId).ifPresent(task -> {
                task.setStatus("COMPLETED");
                cleanupTaskRepository.saveAndFlush(task);
                log.info("Cleanup task {} marked as COMPLETED", taskId);
            });
        }
    }

    /**
     * Records task status as FAILED in an isolated new transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID taskId, String reason) {
        if (taskId == null) {
            return;
        }
        Optional<ProofCleanupTask> taskOpt = cleanupTaskRepository.findByIdForUpdate(taskId);
        if (taskOpt.isPresent()) {
            ProofCleanupTask task = taskOpt.get();
            task.setStatus("FAILED");
            cleanupTaskRepository.saveAndFlush(task);
            log.warn("Cleanup task {} marked as FAILED for reason: {}", taskId, reason);
        } else {
            cleanupTaskRepository.findById(taskId).ifPresent(task -> {
                task.setStatus("FAILED");
                cleanupTaskRepository.saveAndFlush(task);
                log.warn("Cleanup task {} marked as FAILED for reason: {}", taskId, reason);
            });
        }
    }

    /**
     * Records a failed deletion attempt, incrementing retry count or failing if limit reached.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailureAttempt(UUID taskId, String reason) {
        if (taskId == null) {
            return;
        }
        Optional<ProofCleanupTask> taskOpt = cleanupTaskRepository.findByIdForUpdate(taskId);
        ProofCleanupTask task = taskOpt.orElseGet(() -> cleanupTaskRepository.findById(taskId).orElse(null));
        if (task != null) {
            task.incrementRetry();
            if (task.getRetryCount() >= 5) {
                task.setStatus("FAILED");
                log.warn("Cleanup task {} exceeded maximum retry attempts; marked as FAILED for reason: {}", taskId, reason);
            } else {
                log.warn("Cleanup task {} attempt incremented (retry count: {}) for reason: {}", taskId, task.getRetryCount(), reason);
            }
            cleanupTaskRepository.saveAndFlush(task);
        }
    }
}
