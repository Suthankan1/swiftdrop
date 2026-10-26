package com.swiftdrop.deliveryservice.proof.retention;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.ProofOfDelivery;
import com.swiftdrop.deliveryservice.proof.ProofOfDeliveryRepository;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProofRetentionCleanupServiceTest {

    private static final Instant EVALUATION_TIME = Instant.parse("2026-10-26T12:00:00Z");
    private static final String VALID_OBJECT_REF = "proofs/v1/" + UUID.randomUUID();

    private ProofRetentionProperties properties;
    private ProofRetentionEvaluator evaluator;
    private ProofOfDeliveryRepository proofRepository;
    private ParcelRepository parcelRepository;
    private ProofObjectStorage proofObjectStorage;
    private DefaultProofHoldProvider holdProvider;
    private Clock fixedClock;

    private ProofRetentionCleanupService cleanupService;

    private UUID parcelId;
    private UUID proofId;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(EVALUATION_TIME, ZoneOffset.UTC);

        properties = new ProofRetentionProperties();
        properties.setEnabled(true);
        properties.setPolicyApproved(true);
        properties.setPolicyVersion("DEC-RET-01-TEST-V1");
        properties.setRetentionDays(90);
        properties.setClockStartEvent(RetentionClockStartEvent.PROOF_UPLOAD_TIME);
        properties.setEligibleTerminalStates(Set.of(ParcelStatus.DELIVERED, ParcelStatus.FAILED));
        properties.setBatchSize(50);

        evaluator = new ProofRetentionEvaluator(properties, fixedClock);
        proofRepository = mock(ProofOfDeliveryRepository.class);
        parcelRepository = mock(ParcelRepository.class);
        proofObjectStorage = mock(ProofObjectStorage.class);
        when(proofObjectStorage.supportsDeletion()).thenReturn(true);

        holdProvider = new DefaultProofHoldProvider();

        cleanupService = new ProofRetentionCleanupService(
                properties,
                evaluator,
                proofRepository,
                parcelRepository,
                proofObjectStorage,
                holdProvider,
                fixedClock
        );

        parcelId = UUID.randomUUID();
        proofId = UUID.randomUUID();
    }

    private Parcel createParcel(ParcelStatus status) {
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-TEST-1234",
                "customer-sub-1",
                null,
                UUID.randomUUID(),
                "2026.1",
                50000L,
                "LKR",
                new BigDecimal("1.250")
        );
        parcel.setStatus(status);
        parcel.setCreatedAt(EVALUATION_TIME);
        parcel.setUpdatedAt(EVALUATION_TIME);
        return parcel;
    }

    private ProofOfDelivery createProof(Instant uploadedAt) {
        return new ProofOfDelivery(
                proofId,
                parcelId,
                VALID_OBJECT_REF,
                "PHOTO",
                "image/jpeg",
                1024L,
                "Recipient Name",
                "Delivery Note",
                "courier-sub-1",
                uploadedAt
        );
    }

    @Nested
    @DisplayName("Safety Gating & Disabled Execution")
    class SafetyGatingTests {

        @Test
        @DisplayName("Execution returns disabled report and does zero work when enabled=false")
        void disabledExecution_skipped() {
            properties.setEnabled(false);

            RetentionCleanupBatchReport report = cleanupService.executeCleanupBatch();

            assertThat(report.executionAttempted()).isFalse();
            assertThat(report.purgedCount()).isZero();
            verifyNoInteractions(proofRepository);
            verifyNoInteractions(proofObjectStorage);
        }

        @Test
        @DisplayName("Execution returns disabled report and does zero work when policy is unapproved")
        void unapprovedPolicy_skipped() {
            properties.setPolicyApproved(false);

            RetentionCleanupBatchReport report = cleanupService.executeCleanupBatch();

            assertThat(report.executionAttempted()).isFalse();
            assertThat(report.purgedCount()).isZero();
            verifyNoInteractions(proofRepository);
            verifyNoInteractions(proofObjectStorage);
        }
    }

    @Nested
    @DisplayName("Physical Storage Deletion & Metadata Tombstoning")
    class CleanupExecutionTests {

        @Test
        @DisplayName("Eligible synthetic object deleted once and metadata safely tombstoned")
        void processCandidate_eligible_successfulPurge() {
            Instant expiredUpload = EVALUATION_TIME.minus(Duration.ofDays(100));
            ProofOfDelivery proof = createProof(expiredUpload);
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);

            when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(proofRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

            RetentionCleanupResult result = cleanupService.processCandidate(proofId);

            assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.PURGED);
            assertThat(proof.isPurged()).isTrue();
            assertThat(proof.getPurgedAt()).isEqualTo(EVALUATION_TIME);
            assertThat(proof.getObjectReference()).isNull();
            assertThat(proof.getRecipientName()).isNull();
            assertThat(proof.getDeliveryNote()).isNull();

            // Verify physical storage delete called exactly once
            ArgumentCaptor<ProofObjectReference> captor = ArgumentCaptor.forClass(ProofObjectReference.class);
            verify(proofObjectStorage, times(1)).delete(captor.capture());
            assertThat(captor.getValue().value()).isEqualTo(VALID_OBJECT_REF);

            // Verify database update saved
            verify(proofRepository).saveAndFlush(proof);
        }

        @Test
        @DisplayName("Repeated cleanup remains safe and idempotent (already purged proof is skipped)")
        void processCandidate_alreadyPurged_skippedIdempotently() {
            Instant expiredUpload = EVALUATION_TIME.minus(Duration.ofDays(100));
            ProofOfDelivery proof = createProof(expiredUpload);
            proof.markPurged(EVALUATION_TIME.minus(Duration.ofDays(1)));

            when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));

            RetentionCleanupResult result = cleanupService.processCandidate(proofId);

            assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.SKIPPED_ALREADY_PURGED);
            verify(proofObjectStorage, never()).delete(any());
            verify(proofRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("No-op storage adapter is rejected and proof is NOT marked purged")
        void processCandidate_noOpStorageAdapter_rejected() {
            when(proofObjectStorage.supportsDeletion()).thenReturn(false);

            Instant expiredUpload = EVALUATION_TIME.minus(Duration.ofDays(100));
            ProofOfDelivery proof = createProof(expiredUpload);
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);

            when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

            RetentionCleanupResult result = cleanupService.processCandidate(proofId);

            assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.FAILED_STORAGE_UNSUPPORTED);
            assertThat(proof.isPurged()).isFalse();
            assertThat(proof.getObjectReference()).isEqualTo(VALID_OBJECT_REF);
            verify(proofObjectStorage, never()).delete(any());
            verify(proofRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Storage failure leaves proof active and does not tombstone database")
        void processCandidate_storageFailure_leavesProofActive() {
            Instant expiredUpload = EVALUATION_TIME.minus(Duration.ofDays(100));
            ProofOfDelivery proof = createProof(expiredUpload);
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);

            when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            doThrow(new ProofStorageUnavailableException("Simulated S3 connection timeout", new RuntimeException("connection timeout")))
                    .when(proofObjectStorage).delete(any());

            RetentionCleanupResult result = cleanupService.processCandidate(proofId);

            assertThat(result.status()).isEqualTo(RetentionCleanupResult.Status.FAILED_STORAGE_ERROR);
            assertThat(proof.isPurged()).isFalse();
            assertThat(proof.getObjectReference()).isEqualTo(VALID_OBJECT_REF);
            verify(proofRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("Bounded Batch Execution")
    class BatchExecutionTests {

        @Test
        @DisplayName("executeCleanupBatch processes candidates within configured batchSize")
        void executeCleanupBatch_boundedProcessing() {
            Instant expiredUpload = EVALUATION_TIME.minus(Duration.ofDays(100));
            ProofOfDelivery proof1 = createProof(expiredUpload);
            UUID proof2Id = UUID.randomUUID();
            UUID parcel2Id = UUID.randomUUID();
            ProofOfDelivery proof2 = new ProofOfDelivery(
                    proof2Id, parcel2Id, "proofs/v1/" + UUID.randomUUID(), "PHOTO", "image/jpeg", 512L,
                    "Recipient 2", "Note 2", "courier", expiredUpload
            );

            when(proofRepository.findCandidatesForRetention(eq(RetentionStatus.ACTIVE), any(Instant.class), any()))
                    .thenReturn(List.of(proof1, proof2));

            when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof1));
            when(proofRepository.findByIdForUpdate(proof2Id)).thenReturn(Optional.of(proof2));

            Parcel parcel1 = createParcel(ParcelStatus.DELIVERED);
            Parcel parcel2 = new Parcel(parcel2Id, "SWD-TEST-2222", "cust", null, null, null, 100L, "LKR", BigDecimal.ONE);
            parcel2.setStatus(ParcelStatus.DELIVERED);
            parcel2.setCreatedAt(EVALUATION_TIME);
            parcel2.setUpdatedAt(EVALUATION_TIME);

            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel1));
            when(parcelRepository.findById(parcel2Id)).thenReturn(Optional.of(parcel2));

            RetentionCleanupBatchReport report = cleanupService.executeCleanupBatch();

            assertThat(report.executionAttempted()).isTrue();
            assertThat(report.totalCandidatesEvaluated()).isEqualTo(2);
            assertThat(report.purgedCount()).isEqualTo(2);
            assertThat(report.failedCount()).isZero();
            verify(proofObjectStorage, times(2)).delete(any());
        }
    }
}
