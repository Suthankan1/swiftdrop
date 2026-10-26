package com.swiftdrop.deliveryservice.proof;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProofCleanupTaskCoordinatorTest {

    private ProofCleanupTaskRepository cleanupTaskRepository;
    private ProofCleanupTaskCoordinator coordinator;

    private final UUID taskId = UUID.randomUUID();
    private final String objectRef = "proofs/v1/" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cleanupTaskRepository = mock(ProofCleanupTaskRepository.class);
        coordinator = new ProofCleanupTaskCoordinator(cleanupTaskRepository);
    }

    @Test
    @DisplayName("recordDurableCleanup creates and flushes pending task in isolated transaction")
    void recordDurableCleanup_createsAndFlushes() {
        when(cleanupTaskRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProofCleanupTask saved = coordinator.recordDurableCleanup(objectRef, "TEST_REASON");

        assertThat(saved).isNotNull();
        assertThat(saved.getObjectReference()).isEqualTo(objectRef);
        assertThat(saved.getReason()).isEqualTo("TEST_REASON");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getRetryCount()).isEqualTo(0);

        verify(cleanupTaskRepository).saveAndFlush(any(ProofCleanupTask.class));
    }

    @Test
    @DisplayName("recordDurableCleanup rejects null arguments")
    void recordDurableCleanup_nullValidation() {
        assertThatThrownBy(() -> coordinator.recordDurableCleanup(null, "REASON"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> coordinator.recordDurableCleanup(objectRef, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("markCompleted sets task status to COMPLETED under row lock")
    void markCompleted_setsCompletedStatus() {
        ProofCleanupTask task = new ProofCleanupTask(taskId, objectRef, "REPLACED");
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));

        coordinator.markCompleted(taskId);

        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        verify(cleanupTaskRepository).saveAndFlush(task);
    }

    @Test
    @DisplayName("markFailed sets task status to FAILED under row lock")
    void markFailed_setsFailedStatus() {
        ProofCleanupTask task = new ProofCleanupTask(taskId, objectRef, "REPLACED");
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));

        coordinator.markFailed(taskId, "STILL_REFERENCED");

        assertThat(task.getStatus()).isEqualTo("FAILED");
        verify(cleanupTaskRepository).saveAndFlush(task);
    }

    @Test
    @DisplayName("recordFailureAttempt increments retry count when below limit")
    void recordFailureAttempt_incrementsRetry() {
        ProofCleanupTask task = new ProofCleanupTask(taskId, objectRef, "REPLACED");
        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));

        coordinator.recordFailureAttempt(taskId, "TIMEOUT");

        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo("PENDING");
        verify(cleanupTaskRepository).saveAndFlush(task);
    }

    @Test
    @DisplayName("recordFailureAttempt marks task FAILED when retry count reaches threshold")
    void recordFailureAttempt_marksFailedOnLimit() {
        ProofCleanupTask task = new ProofCleanupTask(taskId, objectRef, "REPLACED");
        for (int i = 0; i < 4; i++) {
            task.incrementRetry();
        }
        assertThat(task.getRetryCount()).isEqualTo(4);

        when(cleanupTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));

        coordinator.recordFailureAttempt(taskId, "PERSISTENT_ERROR");

        assertThat(task.getRetryCount()).isEqualTo(5);
        assertThat(task.getStatus()).isEqualTo("FAILED");
        verify(cleanupTaskRepository).saveAndFlush(task);
    }

    @Test
    @DisplayName("Operations handle null task ID safely without throwing")
    void nullTaskId_handledSafely() {
        coordinator.markCompleted(null);
        coordinator.markFailed(null, "REASON");
        coordinator.recordFailureAttempt(null, "REASON");

        verifyNoInteractions(cleanupTaskRepository);
    }
}
