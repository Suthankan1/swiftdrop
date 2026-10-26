package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.dto.ProofResponse;
import com.swiftdrop.deliveryservice.proof.dto.UploadProofRequest;
import com.swiftdrop.deliveryservice.status.StatusTransitionService;
import com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse;
import com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProofPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("delivery_db")
            .withUsername("delivery_app")
            .withPassword("delivery_secret");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("swiftdrop.outbox.relay.enabled", () -> "false");
    }

    @TestConfiguration
    static class TestAssignmentConfig {
        @Bean
        @Primary
        public CourierAssignmentContract testCourierAssignment() {
            return new CourierAssignmentContract() {
                @Override
                public boolean hasActiveAssignment(UUID parcelId, String courierSub) {
                    return "assigned-courier-pg-it".equals(courierSub);
                }

                @Override
                public Optional<String> getActiveCourierSub(UUID parcelId) {
                    return Optional.of("assigned-courier-pg-it");
                }

                @Override
                public int getActiveCourierWorkload(String courierSub) {
                    return 1;
                }
            };
        }
    }

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private ProofOfDeliveryRepository proofRepository;

    @Autowired
    private ProofCleanupTaskRepository cleanupRepository;

    @Autowired
    private ProofService proofService;

    @Autowired
    private ProofMetadataService proofMetadataService;

    @Autowired
    private StatusTransitionService statusTransitionService;

    private UUID parcelId;
    private final String courierSub = "assigned-courier-pg-it";
    private final String customerSub = "customer-pg-it";

    @BeforeEach
    void setUp() {
        parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-PROOF-IT-" + UUID.randomUUID().toString().substring(0, 8),
                customerSub,
                null,
                UUID.randomUUID(),
                "2026.1",
                200000L,
                "LKR",
                new BigDecimal("3.500")
        );
        parcel.setStatus(ParcelStatus.OUT_FOR_DELIVERY);
        parcelRepository.saveAndFlush(parcel);
    }

    @Test
    @DisplayName("Persists proof metadata in PostgreSQL and validates via ProofMetadataContract")
    void recordProofMetadata_persistsAndValidatesInPostgres() {
        String ref = "proofs/v1/" + UUID.randomUUID();
        UploadProofRequest request = new UploadProofRequest("S. Ramanathan", ref, "Delivered to security");

        ProofResponse response = proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER"));

        assertThat(response).isNotNull();
        assertThat(response.parcelId()).isEqualTo(parcelId);

        // Verify entity persisted in PostgreSQL
        Optional<ProofOfDelivery> persisted = proofRepository.findByParcelId(parcelId);
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getObjectReference()).isEqualTo(ref);
        assertThat(persisted.get().getRecipientName()).isEqualTo("S. Ramanathan");
        assertThat(persisted.get().getEvidenceType()).isEqualTo("PHOTO");

        // Verify ProofMetadataContract validates successfully
        assertThat(proofMetadataService.isProofValid(parcelId, response.proofId())).isTrue();
        assertThat(proofMetadataService.isProofValid(parcelId, UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Unique constraint uq_proof_parcel_id enforces single canonical proof per parcel")
    void uniqueConstraint_preventsDuplicateParcelProof() {
        String ref1 = "proofs/v1/" + UUID.randomUUID();
        String ref2 = "proofs/v1/" + UUID.randomUUID();

        ProofOfDelivery proof1 = new ProofOfDelivery(
                UUID.randomUUID(), parcelId, ref1, "PHOTO", "image/jpeg", 100L, "Rec 1", "Note 1", courierSub, Instant.now()
        );
        proofRepository.saveAndFlush(proof1);

        ProofOfDelivery proof2 = new ProofOfDelivery(
                UUID.randomUUID(), parcelId, ref2, "PHOTO", "image/png", 200L, "Rec 2", "Note 2", courierSub, Instant.now()
        );

        assertThatThrownBy(() -> proofRepository.saveAndFlush(proof2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Durable cleanup task table persists and tracks task states")
    void proofCleanupTask_persistsInPostgres() {
        String orphanRef = "proofs/v1/" + UUID.randomUUID();
        ProofCleanupTask task = new ProofCleanupTask(UUID.randomUUID(), orphanRef, "SIMULATED_FAILURE");
        cleanupRepository.saveAndFlush(task);

        List<ProofCleanupTask> pending = cleanupRepository.findByStatus("PENDING");
        assertThat(pending).anyMatch(t -> t.getObjectReference().equals(orphanRef));

        task.setStatus("COMPLETED");
        cleanupRepository.saveAndFlush(task);

        List<ProofCleanupTask> pendingAfter = cleanupRepository.findByStatus("PENDING");
        assertThat(pendingAfter).noneMatch(t -> t.getObjectReference().equals(orphanRef));
    }

    @Test
    @DisplayName("Integrated flow: proof ingestion enables guarded transition to DELIVERED")
    void integratedFlow_proofEnablesDeliveredTransition() {
        String ref = "proofs/v1/" + UUID.randomUUID();
        UploadProofRequest request = new UploadProofRequest("Authorized Recipient", ref, "Handed over");
        ProofResponse proofResponse = proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER"));

        // Now transition to DELIVERED using the recorded proofId
        UpdateStatusRequest statusRequest = new UpdateStatusRequest(
                ParcelStatus.DELIVERED,
                1L, // expectedVersion
                "Delivered successfully",
                null,
                proofResponse.proofId()
        );

        ParcelStatusChangeResponse statusResponse = statusTransitionService.updateStatus(
                parcelId,
                statusRequest,
                courierSub,
                List.of("COURIER")
        );

        assertThat(statusResponse.currentStatus()).isEqualTo("DELIVERED");

        Parcel deliveredParcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(deliveredParcel.getStatus()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(deliveredParcel.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Transition to DELIVERED with unrecorded proofId fails closed")
    void transitionToDelivered_unrecordedProof_fails() {
        UUID fakeProofId = UUID.randomUUID();
        UpdateStatusRequest statusRequest = new UpdateStatusRequest(
                ParcelStatus.DELIVERED,
                1L,
                "Delivered",
                null,
                fakeProofId
        );

        assertThatThrownBy(() -> statusTransitionService.updateStatus(
                parcelId,
                statusRequest,
                courierSub,
                List.of("COURIER")
        )).hasMessageContaining("not found or does not belong to parcel");
    }

    @Test
    @DisplayName("V5 schema migration: proof persists with default retentionStatus ACTIVE and nullable tombstone")
    void proofRetentionTombstone_persistsAndTransitionsToPurgedSafely() {
        String ref = "proofs/v1/" + UUID.randomUUID();
        UploadProofRequest request = new UploadProofRequest("Test Recipient", ref, "Tombstone test note");
        ProofResponse proofResponse = proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER"));

        ProofOfDelivery saved = proofRepository.findById(proofResponse.proofId()).orElseThrow();
        assertThat(saved.getRetentionStatus()).isEqualTo(com.swiftdrop.deliveryservice.proof.retention.RetentionStatus.ACTIVE);
        assertThat(saved.getPurgedAt()).isNull();
        assertThat(saved.getObjectReference()).isEqualTo(ref);

        // Transition to PURGED via tombstoning
        Instant purgeTime = Instant.now();
        saved.markPurged(purgeTime);
        proofRepository.saveAndFlush(saved);

        ProofOfDelivery purged = proofRepository.findById(proofResponse.proofId()).orElseThrow();
        assertThat(purged.isPurged()).isTrue();
        assertThat(purged.getRetentionStatus()).isEqualTo(com.swiftdrop.deliveryservice.proof.retention.RetentionStatus.PURGED);
        assertThat(purged.getPurgedAt()).isNotNull();
        assertThat(purged.getObjectReference()).isNull();
        assertThat(purged.getRecipientName()).isNull();
        assertThat(purged.getDeliveryNote()).isNull();

        // Verify parcel master record remains intact
        Parcel parcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(parcel.getParcelId()).isEqualTo(parcelId);
        assertThat(parcel.getTrackingNumber()).startsWith("SWD-PROOF-IT-");
    }

    @Test
    @DisplayName("findCandidatesForRetention selects only ACTIVE unpurged proofs with object reference")
    void findCandidatesForRetention_selectsActiveOnly() {
        String ref = "proofs/v1/" + UUID.randomUUID();
        UploadProofRequest request = new UploadProofRequest("Recipient Candidate", ref, "Candidate note");
        ProofResponse proofResponse = proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER"));

        Instant threshold = Instant.now().plusSeconds(60);
        List<ProofOfDelivery> candidates = proofRepository.findCandidatesForRetention(
                com.swiftdrop.deliveryservice.proof.retention.RetentionStatus.ACTIVE,
                threshold,
                org.springframework.data.domain.PageRequest.of(0, 10)
        );

        assertThat(candidates).extracting(ProofOfDelivery::getProofId).contains(proofResponse.proofId());

        // Now mark purged and re-query
        ProofOfDelivery proof = proofRepository.findById(proofResponse.proofId()).orElseThrow();
        proof.markPurged(Instant.now());
        proofRepository.saveAndFlush(proof);

        List<ProofOfDelivery> candidatesAfterPurge = proofRepository.findCandidatesForRetention(
                com.swiftdrop.deliveryservice.proof.retention.RetentionStatus.ACTIVE,
                threshold,
                org.springframework.data.domain.PageRequest.of(0, 10)
        );

        assertThat(candidatesAfterPurge).extracting(ProofOfDelivery::getProofId).doesNotContain(proofResponse.proofId());
    }

    @Test
    @DisplayName("Fresh DefaultProofHoldProvider defaults to non-authoritative and fails closed on real PostgreSQL entities")
    void defaultProofHoldProvider_failsClosedOnRealPostgresEntities() {
        var holdProvider = new com.swiftdrop.deliveryservice.proof.retention.DefaultProofHoldProvider();
        assertThat(holdProvider.isAuthoritative()).isFalse();

        // When not authoritative, isUnderHold returns Optional.empty()
        var holdResult = holdProvider.isUnderHold(parcelId, UUID.randomUUID());
        assertThat(holdResult).isEmpty();
    }

    @Test
    @DisplayName("countByObjectReference accurately counts references in PostgreSQL to prevent deleting referenced objects")
    void countByObjectReference_accuratelyCountsInPostgres() {
        String sharedRef = "proofs/v1/" + UUID.randomUUID();
        UploadProofRequest request = new UploadProofRequest("Shared Recipient", sharedRef, "Shared note");
        proofService.recordProofMetadata(parcelId, request, courierSub, List.of("COURIER"));

        assertThat(proofRepository.countByObjectReference(sharedRef)).isEqualTo(1L);
        assertThat(proofRepository.countByObjectReference("non-existent-ref")).isEqualTo(0L);
    }
}
