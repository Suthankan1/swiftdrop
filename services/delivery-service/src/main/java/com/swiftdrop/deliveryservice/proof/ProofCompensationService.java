package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProofCompensationService {

    private static final Logger log = LoggerFactory.getLogger(ProofCompensationService.class);

    private final ProofObjectStorage proofObjectStorage;
    private final ProofCleanupTaskRepository cleanupTaskRepository;

    public ProofCompensationService(
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            ProofCleanupTaskRepository cleanupTaskRepository) {
        this.proofObjectStorage = proofObjectStorage;
        this.cleanupTaskRepository = cleanupTaskRepository;
    }

    /**
     * Attempts synchronous compensation by deleting the orphan storage object.
     * If deletion fails, persists a durable cleanup task in a new transaction.
     */
    public void compensateOrphan(ProofObjectReference reference, String reason) {
        if (reference == null) {
            return;
        }
        if (proofObjectStorage != null) {
            try {
                proofObjectStorage.delete(reference);
                log.info("Synchronous compensation deleted orphan storage object {}", reference.value());
                return;
            } catch (Exception e) {
                log.warn("Synchronous storage deletion failed for {}: {}. Scheduling durable cleanup task.",
                        reference.value(), e.getMessage());
            }
        }
        recordDurableCleanup(reference.value(), reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDurableCleanup(String objectReference, String reason) {
        try {
            ProofCleanupTask task = new ProofCleanupTask(UUID.randomUUID(), objectReference, reason);
            cleanupTaskRepository.saveAndFlush(task);
            log.info("Recorded durable proof cleanup task for {}", objectReference);
        } catch (Exception e) {
            log.error("Failed to record durable cleanup task for {}: {}", objectReference, e.getMessage(), e);
        }
    }

    /**
     * Executes pending durable cleanup tasks.
     */
    @Transactional
    public int processPendingCleanups() {
        if (proofObjectStorage == null) {
            return 0;
        }
        List<ProofCleanupTask> pending = cleanupTaskRepository.findByStatus("PENDING");
        int completed = 0;
        for (ProofCleanupTask task : pending) {
            try {
                proofObjectStorage.delete(new ProofObjectReference(task.getObjectReference()));
                task.setStatus("COMPLETED");
                completed++;
            } catch (Exception e) {
                task.incrementRetry();
                if (task.getRetryCount() >= 5) {
                    task.setStatus("FAILED");
                }
            }
            cleanupTaskRepository.save(task);
        }
        return completed;
    }
}
