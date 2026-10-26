package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.dto.ProofDetailResponse;
import com.swiftdrop.deliveryservice.proof.dto.ProofResponse;
import com.swiftdrop.deliveryservice.proof.dto.UploadProofRequest;
import com.swiftdrop.deliveryservice.proof.exception.IllegalLifecycleStageException;
import com.swiftdrop.deliveryservice.proof.exception.ProofAccessDeniedException;
import com.swiftdrop.deliveryservice.proof.exception.ProofNotFoundException;
import com.swiftdrop.deliveryservice.proof.exception.ProofValidationException;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProofServiceTest {

    private ParcelRepository parcelRepository;
    private ProofOfDeliveryRepository proofRepository;
    private CourierAssignmentContract courierAssignmentContract;
    private ProofObjectStorage proofObjectStorage;
    private ProofCleanupTaskRepository cleanupTaskRepository;
    private ProofCompensationService compensationService;
    private ProofService proofService;
    private ProofMetadataService proofMetadataService;

    private final UUID parcelId = UUID.randomUUID();
    private final String courierSub = "courier-sub-123";
    private final String otherCourierSub = "courier-sub-456";
    private final String customerSub = "customer-sub-789";
    private final String validRef = "proofs/v1/" + UUID.randomUUID();

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
        proofMetadataService = new ProofMetadataService(proofRepository);
    }

    private Parcel createParcel(ParcelStatus status) {
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-TEST-1234",
                customerSub,
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

    @Nested
    @DisplayName("Metadata Ingestion (JSON)")
    class MetadataIngestionTests {

        @Test
        @DisplayName("Assigned courier can ingest valid proof metadata when OUT_FOR_DELIVERY")
        void recordProofMetadata_success() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.empty());

            ProofOfDelivery savedEntity = new ProofOfDelivery(
                    UUID.randomUUID(), parcelId, validRef, "PHOTO", "image/jpeg", 0L, "K. Silva", "Gate delivery", courierSub, Instant.now()
            );
            when(proofRepository.saveAndFlush(any(ProofOfDelivery.class))).thenReturn(savedEntity);

            UploadProofRequest request = new UploadProofRequest("K. Silva", validRef, "Gate delivery");
            ProofResponse response = proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER"));

            assertThat(response).isNotNull();
            assertThat(response.parcelId()).isEqualTo(parcelId);
            assertThat(response.uploadedBySub()).isEqualTo(courierSub);
            verify(proofRepository).saveAndFlush(any(ProofOfDelivery.class));
        }

        @Test
        @DisplayName("Duplicate metadata submission returns existing proof idempotently")
        void recordProofMetadata_idempotentDuplicate() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

            UUID existingProofId = UUID.randomUUID();
            ProofOfDelivery existing = new ProofOfDelivery(
                    existingProofId, parcelId, validRef, "PHOTO", "image/jpeg", 1024L, "K. Silva", "Note", courierSub, Instant.now()
            );
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(existing));

            UploadProofRequest request = new UploadProofRequest("K. Silva", validRef, "Note");
            ProofResponse response = proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER"));

            assertThat(response.proofId()).isEqualTo(existingProofId);
            verify(proofRepository, never()).saveAndFlush(any(ProofOfDelivery.class));
        }

        @Test
        @DisplayName("Rejects upload if courier is not actively assigned to parcel")
        void recordProofMetadata_unassignedCourier_rejected() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, otherCourierSub)).thenReturn(false);

            UploadProofRequest request = new UploadProofRequest("K. Silva", validRef, "Note");
            assertThatThrownBy(() -> proofService.recordProofMetadata(parcelId, request, otherCourierSub, List.of("COURIER")))
                    .isInstanceOf(ProofAccessDeniedException.class)
                    .hasMessageContaining("not actively assigned");
        }

        @Test
        @DisplayName("Rejects upload if actor lacks COURIER role")
        void recordProofMetadata_nonCourierRole_rejected() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

            UploadProofRequest request = new UploadProofRequest("K. Silva", validRef, "Note");
            assertThatThrownBy(() -> proofService.recordProofMetadata(parcelId, request, customerSub, List.of("CUSTOMER")))
                    .isInstanceOf(ProofAccessDeniedException.class)
                    .hasMessageContaining("Only couriers with role COURIER");
        }

        @Test
        @DisplayName("Rejects upload if parcel is in terminal state")
        void recordProofMetadata_terminalState_rejected() {
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

            UploadProofRequest request = new UploadProofRequest("K. Silva", validRef, "Note");
            assertThatThrownBy(() -> proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER")))
                    .isInstanceOf(IllegalLifecycleStageException.class)
                    .hasMessageContaining("terminal parcel in stage DELIVERED");
        }

        @Test
        @DisplayName("Rejects upload before parcel pickup (stage CREATED)")
        void recordProofMetadata_createdStage_rejected() {
            Parcel parcel = createParcel(ParcelStatus.CREATED);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

            UploadProofRequest request = new UploadProofRequest("K. Silva", validRef, "Note");
            assertThatThrownBy(() -> proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER")))
                    .isInstanceOf(IllegalLifecycleStageException.class)
                    .hasMessageContaining("before parcel pickup");
        }

        @Test
        @DisplayName("Rejects invalid object reference format")
        void recordProofMetadata_invalidReferenceFormat() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

            UploadProofRequest request = new UploadProofRequest("K. Silva", "../malicious/path.png", "Note");
            assertThatThrownBy(() -> proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER")))
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("Multipart Binary Upload & Storage Compensation")
    class BinaryUploadCompensationTests {

        @Test
        @DisplayName("Successful binary upload stores object and persists metadata")
        void uploadProofContent_success() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.empty());

            ProofObjectReference ref = new ProofObjectReference(validRef);
            when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(ref, 1024L, "image/jpeg"));

            ProofOfDelivery saved = new ProofOfDelivery(
                    UUID.randomUUID(), parcelId, validRef, "PHOTO", "image/jpeg", 1024L, "Recipient", "Delivered", courierSub, Instant.now()
            );
            when(proofRepository.saveAndFlush(any())).thenReturn(saved);

            byte[] bytes = new byte[1024];
            ProofResponse response = proofService.uploadProofContent(
                    parcelId, new ByteArrayInputStream(bytes), 1024L, "image/jpeg", "Recipient", "Delivered", courierSub, List.of("COURIER")
            );

            assertThat(response).isNotNull();
            assertThat(response.parcelId()).isEqualTo(parcelId);
            verify(proofObjectStorage).store(any());
            verify(proofRepository).saveAndFlush(any());
            verify(proofObjectStorage, never()).delete(any());
        }

        @Test
        @DisplayName("DB failure after storage write triggers compensation delete")
        void uploadProofContent_dbFailure_compensatesDelete() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.empty());

            ProofObjectReference ref = new ProofObjectReference(validRef);
            when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(ref, 1024L, "image/jpeg"));
            when(proofRepository.saveAndFlush(any())).thenThrow(new RuntimeException("Simulated DB connection failure"));

            byte[] bytes = new byte[1024];
            assertThatThrownBy(() -> proofService.uploadProofContent(
                    parcelId, new ByteArrayInputStream(bytes), 1024L, "image/jpeg", "Recipient", "Delivered", courierSub, List.of("COURIER")
            )).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated DB connection failure");

            // Verify compensation delete was invoked on private storage
            verify(proofObjectStorage).delete(ref);
        }

        @Test
        @DisplayName("Compensation delete failure records durable cleanup task")
        void uploadProofContent_deleteFailure_recordsDurableCleanup() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.empty());

            ProofObjectReference ref = new ProofObjectReference(validRef);
            when(proofObjectStorage.store(any())).thenReturn(new StoredProofObject(ref, 1024L, "image/jpeg"));
            when(proofRepository.saveAndFlush(any())).thenThrow(new RuntimeException("Simulated DB error"));
            doThrow(new RuntimeException("Storage delete timeout")).when(proofObjectStorage).delete(ref);

            byte[] bytes = new byte[1024];
            assertThatThrownBy(() -> proofService.uploadProofContent(
                    parcelId, new ByteArrayInputStream(bytes), 1024L, "image/jpeg", "Recipient", "Delivered", courierSub, List.of("COURIER")
            )).isInstanceOf(RuntimeException.class);

            // Verify durable cleanup task was saved in repository
            verify(cleanupTaskRepository).saveAndFlush(any(ProofCleanupTask.class));
        }

        @Test
        @DisplayName("Rejects file exceeding 5MB limit")
        void uploadProofContent_sizeLimitExceeded() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

            long oversized = 6 * 1024 * 1024; // 6MB
            assertThatThrownBy(() -> proofService.uploadProofContent(
                    parcelId, new ByteArrayInputStream(new byte[10]), oversized, "image/jpeg", "Rec", "Note", courierSub, List.of("COURIER")
            )).isInstanceOf(ProofValidationException.class).hasMessageContaining("between 1 and 5242880 bytes");
        }

        @Test
        @DisplayName("Rejects unsupported MIME types")
        void uploadProofContent_unsupportedMime() {
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
            when(courierAssignmentContract.hasActiveAssignment(parcelId, courierSub)).thenReturn(true);

            assertThatThrownBy(() -> proofService.uploadProofContent(
                    parcelId, new ByteArrayInputStream(new byte[10]), 10L, "application/pdf", "Rec", "Note", courierSub, List.of("COURIER")
            )).isInstanceOf(ProofValidationException.class).hasMessageContaining("Unsupported proof content type");
        }
    }

    @Nested
    @DisplayName("Authorized Proof Retrieval & Privacy")
    class ProofRetrievalTests {

        @Test
        @DisplayName("Parcel sender customer can retrieve proof metadata")
        void getProofMetadata_customerSender_authorized() {
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

            ProofOfDelivery proof = new ProofOfDelivery(
                    UUID.randomUUID(), parcelId, validRef, "PHOTO", "image/png", 2048L, "Nimal", "Front porch", courierSub, Instant.now()
            );
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(proof));

            ProofDetailResponse detail = proofService.getProofMetadata(parcelId, customerSub, List.of("CUSTOMER"));
            assertThat(detail).isNotNull();
            assertThat(detail.parcelId()).isEqualTo(parcelId);
            assertThat(detail.objectReference()).isEqualTo(validRef);
            assertThat(detail.recipientName()).isEqualTo("Nimal");
        }

        @Test
        @DisplayName("Dispatcher role can retrieve proof metadata")
        void getProofMetadata_dispatcher_authorized() {
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

            ProofOfDelivery proof = new ProofOfDelivery(
                    UUID.randomUUID(), parcelId, validRef, "PHOTO", "image/png", 2048L, "Nimal", "Front porch", courierSub, Instant.now()
            );
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(proof));

            ProofDetailResponse detail = proofService.getProofMetadata(parcelId, "dispatcher-123", List.of("DISPATCHER"));
            assertThat(detail).isNotNull();
        }

        @Test
        @DisplayName("Other customer is denied with 403 Forbidden")
        void getProofMetadata_otherCustomer_forbidden() {
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

            assertThatThrownBy(() -> proofService.getProofMetadata(parcelId, "unrelated-customer-sub", List.of("CUSTOMER")))
                    .isInstanceOf(ProofAccessDeniedException.class)
                    .hasMessageContaining("not authorized to access proof");
        }

        @Test
        @DisplayName("Retrieval streams private bytes without exposing public URLs")
        void getProofContent_streaming_authorized() throws Exception {
            Parcel parcel = createParcel(ParcelStatus.DELIVERED);
            when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

            ProofOfDelivery proof = new ProofOfDelivery(
                    UUID.randomUUID(), parcelId, validRef, "PHOTO", "image/jpeg", 512L, "Recipient", "Note", courierSub, Instant.now()
            );
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(proof));

            byte[] expectedData = new byte[]{1, 2, 3, 4};
            RetrievedProofObject retrieved = new RetrievedProofObject(new ByteArrayInputStream(expectedData), 4L, "image/jpeg");
            when(proofObjectStorage.retrieve(new ProofObjectReference(validRef))).thenReturn(retrieved);

            try (RetrievedProofObject streamObj = proofService.getProofContent(parcelId, customerSub, List.of("CUSTOMER"))) {
                assertThat(streamObj).isNotNull();
                assertThat(streamObj.contentLength()).isEqualTo(4L);
                assertThat(streamObj.contentType()).contains("image/jpeg");
                assertThat(streamObj.content().readAllBytes()).containsExactly(expectedData);
            }
        }
    }

    @Nested
    @DisplayName("ProofMetadataContract Validation")
    class ProofMetadataContractTests {

        @Test
        @DisplayName("isProofValid returns true when matching proof exists")
        void isProofValid_matches() {
            UUID proofId = UUID.randomUUID();
            ProofOfDelivery proof = new ProofOfDelivery(
                    proofId, parcelId, validRef, "PHOTO", "image/jpeg", 512L, "K.", "Note", courierSub, Instant.now()
            );
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.of(proof));

            assertThat(proofMetadataService.isProofValid(parcelId, proofId)).isTrue();
        }

        @Test
        @DisplayName("isProofValid returns false when proof ID differs or not found")
        void isProofValid_mismatch() {
            when(proofRepository.findByParcelId(parcelId)).thenReturn(Optional.empty());
            assertThat(proofMetadataService.isProofValid(parcelId, UUID.randomUUID())).isFalse();
        }
    }

    @Nested
    @DisplayName("Durable Cleanup Recovery")
    class DurableCleanupRecoveryTests {

        @Test
        @DisplayName("processPendingCleanups processes pending tasks and marks completed")
        void processPendingCleanups_success() {
            ProofCleanupTask task = new ProofCleanupTask(UUID.randomUUID(), validRef, "DB_FAILURE");
            when(cleanupTaskRepository.findByStatus("PENDING")).thenReturn(List.of(task));

            int completed = compensationService.processPendingCleanups();

            assertThat(completed).isEqualTo(1);
            assertThat(task.getStatus()).isEqualTo("COMPLETED");
            verify(proofObjectStorage).delete(new ProofObjectReference(validRef));
            verify(cleanupTaskRepository).save(task);
        }
    }
}
