package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProofCompensationService {

    private static final Logger log = LoggerFactory.getLogger(ProofCompensationService.class);

    private final ProofObjectStorage proofObjectStorage;
    private final ProofCleanupTaskRepository cleanupTaskRepository;
    private final ProofOfDeliveryRepository proofRepository;
    private final ProofCleanupTaskCoordinator taskCoordinator;

    public ProofCompensationService(
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            ProofCleanupTaskRepository cleanupTaskRepository) {
        this(proofObjectStorage, cleanupTaskRepository, null, null);
    }

    public ProofCompensationService(
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            ProofCleanupTaskRepository cleanupTaskRepository,
            @Autowired(required = false) ProofOfDeliveryRepository proofRepository) {
        this(proofObjectStorage, cleanupTaskRepository, proofRepository, null);
    }

    @Autowired
    public ProofCompensationService(
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            ProofCleanupTaskRepository cleanupTaskRepository,
            @Autowired(required = false) ProofOfDeliveryRepository proofRepository,
            @Autowired(required = false) ProofCleanupTaskCoordinator taskCoordinator) {
        this.proofObjectStorage = proofObjectStorage;
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.proofRepository = proofRepository;
        this.taskCoordinator = taskCoordinator != null ? taskCoordinator
                : (cleanupTaskRepository != null ? new ProofCleanupTaskCoordinator(cleanupTaskRepository) : null);
    }

    /**
     * Attempts synchronous compensation by deleting the orphan storage object.
     * If deletion fails, persists a durable cleanup task in a new transaction.
     */
    public void compensateOrphan(ProofObjectReference reference, String reason) {
        if (reference == null) {
            return;
        }
        // Safety check: do not delete an object that is still referenced by an active proof
        if (proofRepository != null && proofRepository.countByObjectReference(reference.value()) > 0) {
            log.warn("Storage object is still referenced by an active proof record. Skipping orphan compensation.");
            return;
        }
        if (proofObjectStorage != null) {
            try {
                proofObjectStorage.delete(reference);
                log.info("Synchronous compensation successfully deleted orphan storage object for reason: {}", reason);
                return;
            } catch (ProofObjectNotFoundException ex) {
                log.info("Orphan storage object was already absent during synchronous compensation for reason: {}", reason);
                return;
            } catch (Exception e) {
                log.warn("Synchronous storage deletion failed for reason: {}. Scheduling durable cleanup task.", reason);
            }
        }
        recordDurableCleanup(reference.value(), reason);
    }

    /**
     * Records a durable cleanup task via the transactional coordinator.
     */
    public void recordDurableCleanup(String objectReference, String reason) {
        if (taskCoordinator != null) {
            try {
                taskCoordinator.recordDurableCleanup(objectReference, reason);
            } catch (Exception e) {
                log.error("Failed to record durable cleanup task for reason: {}", reason);
            }
        } else if (cleanupTaskRepository != null) {
            try {
                ProofCleanupTask task = new ProofCleanupTask(UUID.randomUUID(), objectReference, reason);
                cleanupTaskRepository.saveAndFlush(task);
                log.info("Recorded durable proof cleanup task {} for reason: {}", task.getTaskId(), reason);
            } catch (Exception e) {
                log.error("Failed to record durable cleanup task for reason: {}", reason);
            }
        }
    }

    /**
     * Executes pending durable cleanup tasks with safety checks against active proof references.
     */
    @Transactional
    public int processPendingCleanups() {
        if (proofObjectStorage == null) {
            return 0;
        }
        List<ProofCleanupTask> pending = cleanupTaskRepository.findByStatus("PENDING");
        int completed = 0;
        for (ProofCleanupTask task : pending) {
            if (proofRepository != null && proofRepository.countByObjectReference(task.getObjectReference()) > 0) {
                log.warn("Pending cleanup task {} object is still referenced by an active proof record. Cancelling task.",
                        task.getTaskId());
                task.setStatus("FAILED");
                if (taskCoordinator != null) {
                    taskCoordinator.markFailed(task.getTaskId(), "STILL_REFERENCED");
                }
                if (cleanupTaskRepository != null) {
                    cleanupTaskRepository.save(task);
                }
                continue;
            }
            try {
                proofObjectStorage.delete(new ProofObjectReference(task.getObjectReference()));
                task.setStatus("COMPLETED");
                if (taskCoordinator != null) {
                    taskCoordinator.markCompleted(task.getTaskId());
                }
                if (cleanupTaskRepository != null) {
                    cleanupTaskRepository.save(task);
                }
                completed++;
                log.info("Pending cleanup task {} successfully executed and marked COMPLETED", task.getTaskId());
            } catch (ProofObjectNotFoundException e) {
                // Reconcile object already absent from storage
                task.setStatus("COMPLETED");
                if (taskCoordinator != null) {
                    taskCoordinator.markCompleted(task.getTaskId());
                }
                if (cleanupTaskRepository != null) {
                    cleanupTaskRepository.save(task);
                }
                completed++;
                log.info("Pending cleanup task {} reconciled: object was already deleted from storage", task.getTaskId());
            } catch (Exception e) {
                log.warn("Pending cleanup task {} execution failed for reason: RETRY_FAILED", task.getTaskId());
                task.incrementRetry();
                if (task.getRetryCount() >= 5) {
                    task.setStatus("FAILED");
                }
                if (taskCoordinator != null) {
                    taskCoordinator.recordFailureAttempt(task.getTaskId(), "RETRY_FAILED");
                }
                if (cleanupTaskRepository != null) {
                    cleanupTaskRepository.save(task);
                }
            }
        }
        return completed;
    }
}
