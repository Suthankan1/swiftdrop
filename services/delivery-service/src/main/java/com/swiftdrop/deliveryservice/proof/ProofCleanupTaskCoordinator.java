package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
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
    static final int MAXIMUM_ATTEMPTS = 5;

    private final ProofCleanupTaskRepository cleanupTaskRepository;
    private final ProofOfDeliveryRepository proofRepository;
    private final ProofObjectStorage proofObjectStorage;

    @Autowired
    public ProofCleanupTaskCoordinator(
            ProofCleanupTaskRepository cleanupTaskRepository,
            ProofOfDeliveryRepository proofRepository,
            @Autowired(required = false) ProofObjectStorage proofObjectStorage) {
        this.cleanupTaskRepository = Objects.requireNonNull(cleanupTaskRepository, "cleanupTaskRepository cannot be null");
        this.proofRepository = Objects.requireNonNull(proofRepository, "proofRepository cannot be null");
        this.proofObjectStorage = proofObjectStorage;
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
     * Processes one task under a pessimistic row lock. The storage side effect and its single
     * authoritative database transition share this isolated transaction. If the process stops
     * after deletion but before commit, the still-pending task safely reconciles the absent object
     * on its next attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessingResult processTask(UUID taskId) {
        if (taskId == null) {
            return ProcessingResult.SKIPPED;
        }

        ProofCleanupTask task = cleanupTaskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !task.isPending()) {
            return ProcessingResult.SKIPPED;
        }

        if (proofRepository.countByObjectReference(task.getObjectReference()) > 0) {
            task.markFailed();
            cleanupTaskRepository.saveAndFlush(task);
            log.warn("Cleanup task {} blocked because its object is still referenced by active proof metadata", taskId);
            return ProcessingResult.TERMINAL_FAILURE;
        }

        if (proofObjectStorage == null || !proofObjectStorage.supportsDeletion()) {
            log.warn("Cleanup task {} was not attempted because authoritative physical deletion is unavailable", taskId);
            return ProcessingResult.SKIPPED;
        }

        try {
            proofObjectStorage.delete(new ProofObjectReference(task.getObjectReference()));
        } catch (ProofObjectNotFoundException alreadyAbsent) {
            log.info("Cleanup task {} reconciled an object already absent from storage", taskId);
        } catch (Exception deletionFailure) {
            task.recordFailedAttempt(MAXIMUM_ATTEMPTS);
            cleanupTaskRepository.saveAndFlush(task);
            if (task.isPending()) {
                log.warn("Cleanup task {} deletion attempt failed; retry count is {}", taskId, task.getRetryCount());
                return ProcessingResult.RETRYABLE_FAILURE;
            }
            log.warn("Cleanup task {} reached the maximum deletion attempts and is now FAILED", taskId);
            return ProcessingResult.TERMINAL_FAILURE;
        }

        task.markCompleted();
        cleanupTaskRepository.saveAndFlush(task);
        log.info("Cleanup task {} marked as COMPLETED", taskId);
        return ProcessingResult.COMPLETED;
    }

    public enum ProcessingResult {
        COMPLETED,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE,
        SKIPPED
    }
}
