package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProofCleanupTaskCoordinatorTest {

    private ProofCleanupTaskRepository cleanupTaskRepository;
    private ProofOfDeliveryRepository proofRepository;
    private ProofObjectStorage proofObjectStorage;
    private ProofCleanupTaskCoordinator coordinator;

    private final UUID taskId = UUID.randomUUID();
    private final String objectRef = "proofs/v1/" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cleanupTaskRepository = mock(ProofCleanupTaskRepository.class);
        proofRepository = mock(ProofOfDeliveryRepository.class);
        proofObjectStorage = mock(ProofObjectStorage.class);
        coordinator = new ProofCleanupTaskCoordinator(cleanupTaskRepository, proofRepository, proofObjectStorage);
        when(proofObjectStorage.supportsDeletion()).thenReturn(true);
        when(proofRepository.countByObjectReference(objectRef)).thenReturn(0L);
    }

    @Test
    @DisplayName("recordDurableCleanup creates and flushes a pending task")
    void recordDurableCleanup_createsAndFlushes() {
        when(cleanupTaskRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProofCleanupTask saved = coordinator.recordDurableCleanup(objectRef, "TEST_REASON");

        assertThat(saved.getStatus()).isEqualTo(ProofCleanupTask.STATUS_PENDING);
        assertThat(saved.getRetryCount()).isZero();
        verify(cleanupTaskRepository).saveAndFlush(any(ProofCleanupTask.class));
    }

    @Test
    @DisplayName("first and second deletion failures each increment retry count exactly once")
    void processTask_eachFailureIncrementsExactlyOnce() {
        ProofCleanupTask task = pendingTask();
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        doThrow(new RuntimeException("timeout")).when(proofObjectStorage).delete(any());

        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.RETRYABLE_FAILURE);
        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.RETRYABLE_FAILURE);
        assertThat(task.getRetryCount()).isEqualTo(2);
        verify(cleanupTaskRepository, times(2)).saveAndFlush(task);
    }

    @Test
    @DisplayName("the fifth failed deletion reaches the exact maximum and becomes terminal")
    void processTask_exactMaximumAttemptsIsRespected() {
        ProofCleanupTask task = pendingTask();
        for (int attempt = 0; attempt < 4; attempt++) {
            task.recordFailedAttempt(ProofCleanupTaskCoordinator.MAXIMUM_ATTEMPTS);
        }
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        doThrow(new RuntimeException("persistent failure")).when(proofObjectStorage).delete(any());

        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.TERMINAL_FAILURE);
        assertThat(task.getRetryCount()).isEqualTo(5);
        assertThat(task.getStatus()).isEqualTo(ProofCleanupTask.STATUS_FAILED);
    }

    @Test
    @DisplayName("successful deletion records one completed transition without a retry")
    void processTask_successMarksCompleted() {
        ProofCleanupTask task = pendingTask();
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));

        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.COMPLETED);
        assertThat(task.getStatus()).isEqualTo(ProofCleanupTask.STATUS_COMPLETED);
        assertThat(task.getRetryCount()).isZero();
        verify(proofObjectStorage).delete(new ProofObjectReference(objectRef));
        verify(cleanupTaskRepository).saveAndFlush(task);
    }

    @Test
    @DisplayName("an already absent object reconciles to completed")
    void processTask_absentObjectMarksCompleted() {
        ProofCleanupTask task = pendingTask();
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        doThrow(new ProofObjectNotFoundException("absent")).when(proofObjectStorage).delete(any());

        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.COMPLETED);
        assertThat(task.getStatus()).isEqualTo(ProofCleanupTask.STATUS_COMPLETED);
        assertThat(task.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("completed and failed tasks are never processed again")
    void processTask_terminalTasksAreSkipped() {
        ProofCleanupTask completed = pendingTask();
        completed.markCompleted();
        ProofCleanupTask failed = new ProofCleanupTask(UUID.randomUUID(), objectRef, "TEST");
        failed.markFailed();
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(completed));
        when(cleanupTaskRepository.findByIdForUpdate(failed.getTaskId())).thenReturn(Optional.of(failed));

        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.SKIPPED);
        assertThat(coordinator.processTask(failed.getTaskId())).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.SKIPPED);
        verifyNoInteractions(proofObjectStorage);
    }

    @Test
    @DisplayName("an active object reference is never deleted and records a terminal blocked outcome")
    void processTask_activeReferenceCannotBeDeleted() {
        ProofCleanupTask task = pendingTask();
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(proofRepository.countByObjectReference(objectRef)).thenReturn(1L);

        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.TERMINAL_FAILURE);
        assertThat(task.getStatus()).isEqualTo(ProofCleanupTask.STATUS_FAILED);
        assertThat(task.getRetryCount()).isZero();
        verifyNoInteractions(proofObjectStorage);
    }

    @Test
    @DisplayName("a storage adapter without authoritative deletion cannot report completion")
    void processTask_unsupportedDeletionIsSkipped() {
        ProofCleanupTask task = pendingTask();
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(proofObjectStorage.supportsDeletion()).thenReturn(false);

        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.SKIPPED);
        assertThat(task.getStatus()).isEqualTo(ProofCleanupTask.STATUS_PENDING);
        verify(proofObjectStorage, never()).delete(any());
        verify(cleanupTaskRepository, never()).saveAndFlush(task);
    }

    @Test
    @DisplayName("database failure propagates and cannot be reported as successful")
    void processTask_databaseFailureCannotProduceFalseSuccess() {
        ProofCleanupTask task = pendingTask();
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(cleanupTaskRepository.saveAndFlush(task)).thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> coordinator.processTask(taskId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");
    }

    @Test
    @DisplayName("a null or missing task is safely skipped")
    void processTask_nullOrMissingIsSkipped() {
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.empty());

        assertThat(coordinator.processTask(null)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.SKIPPED);
        assertThat(coordinator.processTask(taskId)).isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.SKIPPED);
        verifyNoInteractions(proofObjectStorage);
    }

    private ProofCleanupTask pendingTask() {
        return new ProofCleanupTask(taskId, objectRef, "TEST");
    }
}
