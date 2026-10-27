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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProofRetentionCandidateProcessorTest {

    private static final Instant EVALUATION_TIME = Instant.parse("2026-10-26T12:00:00Z");
    private static final String VALID_OBJECT_REF = "proofs/v1/" + UUID.randomUUID();

    private ProofOfDeliveryRepository proofRepository;
    private ParcelRepository parcelRepository;
    private ProofRetentionEvaluator evaluator;
    private ProofObjectStorage proofObjectStorage;
    private DefaultProofHoldProvider holdProvider;
    private Clock fixedClock;

    private ProofRetentionCandidateProcessor processor;

    private UUID parcelId;
    private UUID proofId;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(EVALUATION_TIME, ZoneOffset.UTC);

        ProofRetentionProperties properties = new ProofRetentionProperties();
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

        holdProvider = new DefaultProofHoldProvider(true);

        processor = new ProofRetentionCandidateProcessor(
                proofRepository,
                parcelRepository,
                evaluator,
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
                "Gate delivery note",
                "courier-sub-1",
                uploadedAt
        );
    }

    @Test
    @DisplayName("Successfully purges eligible candidate: calls physical delete and tombstones in database")
    void processCandidate_eligible_purgesSuccessfully() {
        Instant uploadedAt = EVALUATION_TIME.minus(Duration.ofDays(100));
        ProofOfDelivery proof = createProof(uploadedAt);
        Parcel parcel = createParcel(ParcelStatus.DELIVERED);

        when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(proofRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RetentionCleanupResult result = processor.processCandidate(proofId);

        assertThat(result.isPurged()).isTrue();
        assertThat(result.proofId()).isEqualTo(proofId);
        assertThat(result.parcelId()).isEqualTo(parcelId);

        // Verify storage deletion
        verify(proofObjectStorage).delete(new ProofObjectReference(VALID_OBJECT_REF));

        // Verify entity tombstoned
        assertThat(proof.isPurged()).isTrue();
        assertThat(proof.getObjectReference()).isNull();
        assertThat(proof.getRecipientName()).isNull();
        assertThat(proof.getDeliveryNote()).isNull();
        assertThat(proof.getPurgedAt()).isEqualTo(EVALUATION_TIME);
        verify(proofRepository).saveAndFlush(proof);
    }

    @Test
    @DisplayName("Idempotent skip when proof is already purged")
    void processCandidate_alreadyPurged_skipped() {
        ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
        proof.markPurged(EVALUATION_TIME.minus(Duration.ofDays(1)));

        when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));

        RetentionCleanupResult result = processor.processCandidate(proofId);

        assertThat(result.isPurged()).isFalse();
        assertThat(result.isSkipped()).isTrue();
        verifyNoInteractions(proofObjectStorage);
        verify(proofRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Skips ineligible candidate and preserves record without storage deletion")
    void processCandidate_ineligible_skipped() {
        ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(10))); // Only 10 days old
        Parcel parcel = createParcel(ParcelStatus.DELIVERED);

        when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        RetentionCleanupResult result = processor.processCandidate(proofId);

        assertThat(result.isPurged()).isFalse();
        assertThat(result.isSkipped()).isTrue();
        verifyNoInteractions(proofObjectStorage);
        verify(proofRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Fails closed when storage adapter does not support physical deletion")
    void processCandidate_unsupportedStorage_failsClosed() {
        when(proofObjectStorage.supportsDeletion()).thenReturn(false);

        ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
        Parcel parcel = createParcel(ParcelStatus.DELIVERED);

        when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        RetentionCleanupResult result = processor.processCandidate(proofId);

        assertThat(result.isPurged()).isFalse();
        assertThat(result.message()).containsIgnoringCase("support");
        verify(proofObjectStorage, never()).delete(any());
        verify(proofRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Handles storage exception by reporting failure and avoiding database tombstone")
    void processCandidate_storageFailure_reportsFailureAndPreservesProof() {
        ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
        Parcel parcel = createParcel(ParcelStatus.DELIVERED);

        when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        doThrow(new ProofStorageUnavailableException("MinIO connection reset", new RuntimeException("connection reset")))
                .when(proofObjectStorage).delete(any());

        RetentionCleanupResult result = processor.processCandidate(proofId);

        assertThat(result.isPurged()).isFalse();
        assertThat(result.message()).contains("MinIO connection reset");

        // DB entity is NOT tombstoned
        assertThat(proof.isPurged()).isFalse();
        assertThat(proof.getObjectReference()).isEqualTo(VALID_OBJECT_REF);
        verify(proofRepository, never()).saveAndFlush(proof);
    }
}
