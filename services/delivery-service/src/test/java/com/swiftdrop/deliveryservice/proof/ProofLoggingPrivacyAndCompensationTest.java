package com.swiftdrop.deliveryservice.proof;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.retention.ProofRetentionCandidateProcessor;
import com.swiftdrop.deliveryservice.proof.retention.ProofRetentionCleanupService;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProofLoggingPrivacyAndCompensationTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger proofServiceLogger;
    private Logger compensationLogger;
    private Logger candidateProcessorLogger;
    private Logger cleanupServiceLogger;
    private Logger coordinatorLogger;

    private ParcelRepository parcelRepository;
    private ProofOfDeliveryRepository proofRepository;
    private CourierAssignmentContract courierAssignmentContract;
    private ProofObjectStorage proofObjectStorage;
    private ProofCleanupTaskRepository cleanupTaskRepository;
    private ProofCleanupTaskCoordinator taskCoordinator;
    private ProofCompensationService compensationService;
    private ProofService proofService;

    private final UUID parcelId = UUID.randomUUID();
    private final String courierSub = "courier-logger-sub";
    private final String secretOldKey = "proofs/v1/" + UUID.randomUUID();
    private final String secretNewKey = "proofs/v1/" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listAppender = new ListAppender<>();
        listAppender.start();

        proofServiceLogger = (Logger) LoggerFactory.getLogger(ProofService.class);
        compensationLogger = (Logger) LoggerFactory.getLogger(ProofCompensationService.class);
        candidateProcessorLogger = (Logger) LoggerFactory.getLogger(ProofRetentionCandidateProcessor.class);
        cleanupServiceLogger = (Logger) LoggerFactory.getLogger(ProofRetentionCleanupService.class);
        coordinatorLogger = (Logger) LoggerFactory.getLogger(ProofCleanupTaskCoordinator.class);

        proofServiceLogger.addAppender(listAppender);
        compensationLogger.addAppender(listAppender);
        candidateProcessorLogger.addAppender(listAppender);
        cleanupServiceLogger.addAppender(listAppender);
        coordinatorLogger.addAppender(listAppender);

        parcelRepository = mock(ParcelRepository.class);
        proofRepository = mock(ProofOfDeliveryRepository.class);
        courierAssignmentContract = mock(CourierAssignmentContract.class);
        proofObjectStorage = mock(ProofObjectStorage.class);
        cleanupTaskRepository = mock(ProofCleanupTaskRepository.class);
        taskCoordinator = mock(ProofCleanupTaskCoordinator.class);

        compensationService = new ProofCompensationService(
                proofObjectStorage, cleanupTaskRepository, proofRepository, taskCoordinator
        );

        proofService = new ProofService(
                parcelRepository,
                proofRepository,
                courierAssignmentContract,
                proofObjectStorage,
                compensationService,
                cleanupTaskRepository,
                taskCoordinator
        );
    }

    @AfterEach
    void tearDown() {
        proofServiceLogger.detachAppender(listAppender);
        compensationLogger.detachAppender(listAppender);
        candidateProcessorLogger.detachAppender(listAppender);
        cleanupServiceLogger.detachAppender(listAppender);
        coordinatorLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    private Parcel createParcel(ParcelStatus status) {
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-TEST-LOGS",
                "customer-sub",
                null,
                UUID.randomUUID(),
                "2026.1",
                100000L,
                "LKR",
                new BigDecimal("2.500")
        );
        parcel.setStatus(status);
        return parcel;
    }

    private void assertNoStorageKeysInLogs() {
        for (ILoggingEvent event : listAppender.list) {
            String formatted = event.getFormattedMessage();
            assertThat(formatted)
                    .as("Log message must never leak private storage object keys: %s", formatted)
                    .doesNotContain(secretOldKey)
                    .doesNotContain(secretNewKey);
        }
    }

    @Test
    @DisplayName("Proof replacement logs contain no private storage object keys")
    void proofReplacement_logsContainNoStorageKeys() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        ProofOfDelivery existingProof = new ProofOfDelivery(
                UUID.randomUUID(), parcelId, secretOldKey, "PHOTO", "image/jpeg", 1024L,
                "Recipient", "Note", courierSub, Instant.now()
        );
        when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(existingProof));
        when(proofRepository.countByObjectReference(secretOldKey)).thenReturn(0L);

        ProofObjectReference newObjectReference = new ProofObjectReference(secretNewKey);
        when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(newObjectReference, 2048L, "image/png"));
        when(proofRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        proofService.uploadProofContent(
                parcelId, new ByteArrayInputStream(new byte[2048]), 2048L, "image/png",
                "New Recipient", "New Note", courierSub, List.of("COURIER")
        );

        assertThat(listAppender.list).isNotEmpty();
        assertNoStorageKeysInLogs();
    }

    @Test
    @DisplayName("Rollback compensation logs contain no private storage object keys")
    void rollbackCompensation_logsContainNoStorageKeys() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        ProofObjectReference newObjectReference = new ProofObjectReference(secretNewKey);
        when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(newObjectReference, 2048L, "image/jpeg"));
        when(proofRepository.saveAndFlush(any())).thenThrow(new RuntimeException("Simulated commit failure"));

        try {
            proofService.uploadProofContent(
                    parcelId, new ByteArrayInputStream(new byte[2048]), 2048L, "image/jpeg",
                    "Recipient", "Note", courierSub, List.of("COURIER")
            );
        } catch (Exception ignored) {
        }

        assertThat(listAppender.list).isNotEmpty();
        assertNoStorageKeysInLogs();
    }

    @Test
    @DisplayName("Failed old-object deletion logs contain no private storage object keys")
    void failedOldObjectDeletion_logsContainNoStorageKeys() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        ProofOfDelivery existingProof = new ProofOfDelivery(
                UUID.randomUUID(), parcelId, secretOldKey, "PHOTO", "image/jpeg", 1024L,
                "Recipient", "Note", courierSub, Instant.now()
        );
        when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(existingProof));
        when(proofRepository.countByObjectReference(secretOldKey)).thenReturn(0L);

        ProofObjectReference newObjectReference = new ProofObjectReference(secretNewKey);
        when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(newObjectReference, 2048L, "image/png"));
        when(proofRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // Simulate storage deletion throwing an exception
        doThrow(new RuntimeException("Simulated S3 connection timeout"))
                .when(proofObjectStorage).delete(any());

        proofService.uploadProofContent(
                parcelId, new ByteArrayInputStream(new byte[2048]), 2048L, "image/png",
                "New Recipient", "New Note", courierSub, List.of("COURIER")
        );

        assertThat(listAppender.list).isNotEmpty();
        assertNoStorageKeysInLogs();
    }

    @Test
    @DisplayName("Durable retry processing logs remain privacy-safe and handle absent objects cleanly")
    void durableRetryProcessing_logsContainNoStorageKeys() {
        ProofCleanupTask task = new ProofCleanupTask(UUID.randomUUID(), secretOldKey, "RETRY_TEST");
        when(cleanupTaskRepository.findByStatus("PENDING")).thenReturn(List.of(task));
        when(proofRepository.countByObjectReference(secretOldKey)).thenReturn(0L);

        // Simulate object already absent from storage (404)
        doThrow(new ProofObjectNotFoundException("Private proof object was not found"))
                .when(proofObjectStorage).delete(any());

        int processed = compensationService.processPendingCleanups();

        assertThat(processed).isEqualTo(1);
        verify(taskCoordinator).markCompleted(task.getTaskId());

        assertThat(listAppender.list).isNotEmpty();
        assertNoStorageKeysInLogs();
    }

    @Test
    @DisplayName("Synchronous orphan compensation logs contain no private storage keys")
    void synchronousCompensation_logsContainNoStorageKeys() {
        ProofObjectReference reference = new ProofObjectReference(secretNewKey);
        when(proofRepository.countByObjectReference(secretNewKey)).thenReturn(0L);

        compensationService.compensateOrphan(reference, "TEST_ORPHAN");

        assertThat(listAppender.list).isNotEmpty();
        assertNoStorageKeysInLogs();
    }
}
