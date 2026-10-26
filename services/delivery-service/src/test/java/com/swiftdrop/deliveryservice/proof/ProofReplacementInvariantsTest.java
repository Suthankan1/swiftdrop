package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.dto.ProofResponse;
import com.swiftdrop.deliveryservice.proof.exception.IllegalLifecycleStageException;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProofReplacementInvariantsTest {

    private ParcelRepository parcelRepository;
    private ProofOfDeliveryRepository proofRepository;
    private CourierAssignmentContract courierAssignmentContract;
    private ProofObjectStorage proofObjectStorage;
    private ProofCleanupTaskRepository cleanupTaskRepository;
    private ProofCompensationService compensationService;
    private ProofService proofService;

    private final UUID parcelId = UUID.randomUUID();
    private final String courierSub = "courier-sub-123";
    private final String oldRef = "proofs/v1/" + UUID.randomUUID();
    private final String newRef = "proofs/v1/" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        parcelRepository = mock(ParcelRepository.class);
        proofRepository = mock(ProofOfDeliveryRepository.class);
        courierAssignmentContract = mock(CourierAssignmentContract.class);
        proofObjectStorage = mock(ProofObjectStorage.class);
        cleanupTaskRepository = mock(ProofCleanupTaskRepository.class);

        compensationService = new ProofCompensationService(proofObjectStorage, cleanupTaskRepository);
        proofService = new ProofService(
                parcelRepository,
                proofRepository,
                courierAssignmentContract,
                proofObjectStorage,
                compensationService
        );
    }

    private Parcel createParcel(ParcelStatus status) {
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-TEST-1234",
                "customer-sub-789",
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

    @Test
    @DisplayName("Replacement proof updates database reference to new object and compensates old object")
    void uploadProofContent_replacement_updatesDbAndCompensatesOldOnly() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        ProofOfDelivery existingProof = new ProofOfDelivery(
                UUID.randomUUID(), parcelId, oldRef, "PHOTO", "image/jpeg", 1024L,
                "Old Recipient", "Old Note", courierSub, Instant.now()
        );
        when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(existingProof));

        ProofObjectReference newObjectReference = new ProofObjectReference(newRef);
        when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(newObjectReference, 2048L, "image/png"));
        when(proofRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        byte[] content = new byte[2048];
        ProofResponse response = proofService.uploadProofContent(
                parcelId, new ByteArrayInputStream(content), 2048L, "image/png",
                "New Recipient", "New Note", courierSub, List.of("COURIER")
        );

        assertThat(response).isNotNull();

        // 1. Verify DB reference updated to new object
        assertThat(existingProof.getObjectReference()).isEqualTo(newRef);
        assertThat(existingProof.getContentType()).isEqualTo("image/png");
        assertThat(existingProof.getContentLength()).isEqualTo(2048L);
        assertThat(existingProof.getRecipientName()).isEqualTo("New Recipient");
        assertThat(existingProof.getDeliveryNote()).isEqualTo("New Note");

        // 2. Verify newly uploaded proof is NOT deleted
        verify(proofObjectStorage, never()).delete(newObjectReference);

        // 3. Verify OLD reference was compensated
        ArgumentCaptor<ProofObjectReference> deleteCaptor = ArgumentCaptor.forClass(ProofObjectReference.class);
        verify(proofObjectStorage).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().value()).isEqualTo(oldRef);
    }

    @Test
    @DisplayName("Failed DB persistence during replacement compensates the NEW upload and preserves existing record")
    void uploadProofContent_failedDbWrite_preservesExistingAndCompensatesNewOnly() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        ProofOfDelivery existingProof = new ProofOfDelivery(
                UUID.randomUUID(), parcelId, oldRef, "PHOTO", "image/jpeg", 1024L,
                "Old Recipient", "Old Note", courierSub, Instant.now()
        );
        when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(existingProof));

        ProofObjectReference newObjectReference = new ProofObjectReference(newRef);
        when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(newObjectReference, 2048L, "image/jpeg"));
        when(proofRepository.saveAndFlush(any())).thenThrow(new RuntimeException("Simulated DB error on update"));

        byte[] content = new byte[2048];
        assertThatThrownBy(() -> proofService.uploadProofContent(
                parcelId, new ByteArrayInputStream(content), 2048L, "image/jpeg",
                "New Recipient", "New Note", courierSub, List.of("COURIER")
        )).isInstanceOf(RuntimeException.class);

        // Verify the compensation deleted the newly stored object, NOT the old active object
        ArgumentCaptor<ProofObjectReference> deleteCaptor = ArgumentCaptor.forClass(ProofObjectReference.class);
        verify(proofObjectStorage).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().value()).isEqualTo(newRef);
    }

    @Test
    @DisplayName("Cannot upload replacement proof for a purged proof record")
    void uploadProofContent_existingPurged_rejected() {
        Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

        ProofOfDelivery purgedProof = new ProofOfDelivery(
                UUID.randomUUID(), parcelId, oldRef, "PHOTO", "image/jpeg", 1024L,
                "Old Recipient", "Old Note", courierSub, Instant.now()
        );
        purgedProof.markPurged(Instant.now());
        when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(purgedProof));

        byte[] content = new byte[1024];
        assertThatThrownBy(() -> proofService.uploadProofContent(
                parcelId, new ByteArrayInputStream(content), 1024L, "image/jpeg",
                "New Recipient", "New Note", courierSub, List.of("COURIER")
        )).isInstanceOf(IllegalLifecycleStageException.class)
                .hasMessageContaining("purged per retention policy");

        verifyNoInteractions(proofObjectStorage);
    }
}
