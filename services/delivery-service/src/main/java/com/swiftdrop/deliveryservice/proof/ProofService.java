package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.dto.ProofDetailResponse;
import com.swiftdrop.deliveryservice.proof.dto.ProofResponse;
import com.swiftdrop.deliveryservice.proof.dto.UploadProofRequest;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.proof.exception.IllegalLifecycleStageException;
import com.swiftdrop.deliveryservice.proof.exception.ProofAccessDeniedException;
import com.swiftdrop.deliveryservice.proof.exception.ProofNotFoundException;
import com.swiftdrop.deliveryservice.proof.exception.ProofValidationException;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectUpload;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProofService {

    private static final Logger log = LoggerFactory.getLogger(ProofService.class);
    private static final long MAX_PROOF_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final ParcelRepository parcelRepository;
    private final ProofOfDeliveryRepository proofRepository;
    private final CourierAssignmentContract courierAssignmentContract;
    private final ProofObjectStorage proofObjectStorage;
    private final ProofCompensationService compensationService;
    private final ProofCleanupTaskRepository cleanupTaskRepository;
    private final ProofCleanupTaskCoordinator taskCoordinator;

    public ProofService(
            ParcelRepository parcelRepository,
            ProofOfDeliveryRepository proofRepository,
            @Autowired(required = false) CourierAssignmentContract courierAssignmentContract,
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            ProofCompensationService compensationService) {
        this(parcelRepository, proofRepository, courierAssignmentContract, proofObjectStorage, compensationService, null, null);
    }

    public ProofService(
            ParcelRepository parcelRepository,
            ProofOfDeliveryRepository proofRepository,
            @Autowired(required = false) CourierAssignmentContract courierAssignmentContract,
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            ProofCompensationService compensationService,
            @Autowired(required = false) ProofCleanupTaskRepository cleanupTaskRepository) {
        this(parcelRepository, proofRepository, courierAssignmentContract, proofObjectStorage, compensationService, cleanupTaskRepository, null);
    }

    @Autowired
    public ProofService(
            ParcelRepository parcelRepository,
            ProofOfDeliveryRepository proofRepository,
            @Autowired(required = false) CourierAssignmentContract courierAssignmentContract,
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            ProofCompensationService compensationService,
            @Autowired(required = false) ProofCleanupTaskRepository cleanupTaskRepository,
            @Autowired(required = false) ProofCleanupTaskCoordinator taskCoordinator) {
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "parcelRepository cannot be null");
        this.proofRepository = Objects.requireNonNull(proofRepository, "proofRepository cannot be null");
        this.courierAssignmentContract = courierAssignmentContract;
        this.proofObjectStorage = proofObjectStorage;
        this.compensationService = Objects.requireNonNull(compensationService, "compensationService cannot be null");
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.taskCoordinator = taskCoordinator != null ? taskCoordinator
                : (cleanupTaskRepository != null ? new ProofCleanupTaskCoordinator(cleanupTaskRepository) : null);
    }

    /**
     * Ingests proof-of-delivery metadata per OpenAPI schema (IF-REST-08).
     */
    @Transactional
    public ProofResponse recordProofMetadata(
            UUID parcelId,
            UploadProofRequest request,
            String actorSub,
            Collection<String> roles) {

        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(actorSub, "actorSub cannot be null");

        Parcel parcel = getParcelOrThrow(parcelId);
        authorizeCourierForUpload(parcelId, actorSub, roles);
        validateLifecycleStageForUpload(parcel);

        if (request.photoObjectKey() == null || request.photoObjectKey().isBlank()) {
            throw new ProofValidationException("photoObjectKey is required");
        }

        // Validate safe opaque object reference format
        ProofObjectReference reference = new ProofObjectReference(request.photoObjectKey().trim());

        // Idempotency check: if proof already exists for this parcel
        Optional<ProofOfDelivery> existing = proofRepository.findByParcelId(parcelId);
        if (existing.isPresent()) {
            ProofOfDelivery existingProof = existing.get();
            if (existingProof.isPurged()) {
                throw new IllegalLifecycleStageException("Cannot update proof: existing proof has been purged per retention policy");
            }
            if (Objects.equals(existingProof.getObjectReference(), reference.value())) {
                log.info("Duplicate proof submission for parcel {}: returning existing proof {}", parcelId, existingProof.getProofId());
                return toResponse(existingProof);
            }
            // Update metadata if still in OUT_FOR_DELIVERY
            existingProof.updateMetadata(request.recipientName(), request.deliveryNote());
            proofRepository.saveAndFlush(existingProof);
            return toResponse(existingProof);
        }

        ProofOfDelivery proof = new ProofOfDelivery(
                UUID.randomUUID(),
                parcelId,
                reference.value(),
                "PHOTO",
                "image/jpeg",
                0L,
                request.recipientName(),
                request.deliveryNote(),
                actorSub,
                Instant.now()
        );

        ProofOfDelivery saved = proofRepository.saveAndFlush(proof);
        log.info("Recorded proof metadata {} for parcel {}", saved.getProofId(), parcelId);
        return toResponse(saved);
    }

    /**
     * Uploads proof binary stream into private storage and records metadata with transaction-safe compensation.
     *
     * <p>SAFETY INVARIANTS:
     * 1. Replaced old object references are NEVER deleted before database commit.
     * 2. If the transaction rolls back, the old object reference and storage blob remain intact; the newly
     *    uploaded storage object is compensated to prevent orphan leakage.
     * 3. A durable {@link ProofCleanupTask} is registered in the transaction for crash recovery.
     * 4. Objects still referenced by another proof record are never deleted.</p>
     */
    @Transactional
    public ProofResponse uploadProofContent(
            UUID parcelId,
            InputStream content,
            long contentLength,
            String contentType,
            String recipientName,
            String deliveryNote,
            String actorSub,
            Collection<String> roles) {

        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
        Objects.requireNonNull(actorSub, "actorSub cannot be null");

        Parcel parcel = getParcelOrThrow(parcelId);
        authorizeCourierForUpload(parcelId, actorSub, roles);
        validateLifecycleStageForUpload(parcel);

        // Size verification
        if (contentLength <= 0 || contentLength > MAX_PROOF_SIZE_BYTES) {
            throw new ProofValidationException("Proof content length must be between 1 and " + MAX_PROOF_SIZE_BYTES + " bytes");
        }

        // MIME verification
        String normalizedType = normalizeContentType(contentType);
        if (!ALLOWED_MIME_TYPES.contains(normalizedType)) {
            throw new ProofValidationException("Unsupported proof content type: " + contentType + ". Allowed: " + ALLOWED_MIME_TYPES);
        }

        if (proofObjectStorage == null) {
            throw new IllegalStateException("ProofObjectStorage is not configured");
        }

        // Check if proof already recorded for this parcel
        Optional<ProofOfDelivery> existing = proofRepository.findByParcelId(parcelId);
        if (existing.isPresent() && existing.get().isPurged()) {
            throw new IllegalLifecycleStageException("Cannot upload proof: existing proof has been purged per retention policy");
        }

        // 1. Store new private object
        ProofObjectUpload upload = new ProofObjectUpload(content, contentLength, normalizedType);
        StoredProofObject stored = proofObjectStorage.store(upload);
        ProofObjectReference newRef = stored.reference();

        // Register rollback hook: if the database transaction rolls back, delete newly uploaded object from S3
        registerRollbackCompensation(newRef, parcelId);

        // 2. Persist in database with transaction-safe replacement lifecycle
        try {
            if (existing.isPresent()) {
                ProofOfDelivery existingProof = existing.get();
                String oldRef = existingProof.getObjectReference();
                boolean isDifferentObject = oldRef != null && !oldRef.equals(newRef.value());

                existingProof.replaceEvidence(
                        newRef.value(),
                        normalizedType,
                        contentLength,
                        recipientName,
                        deliveryNote
                );
                ProofOfDelivery updated = proofRepository.saveAndFlush(existingProof);

                if (isDifferentObject) {
                    // Persist durable cleanup task inside the SAME transaction.
                    // If the transaction commits, this task is committed. If it rolls back, the task is rolled back.
                    UUID taskId = UUID.randomUUID();
                    if (cleanupTaskRepository != null) {
                        ProofCleanupTask task = new ProofCleanupTask(taskId, oldRef, "REPLACED_PROOF");
                        cleanupTaskRepository.save(task);
                    }

                    // Register afterCommit hook to perform old object deletion ONLY after DB commit
                    registerPostCommitOldObjectCleanup(taskId, oldRef);
                }

                return toResponse(updated);
            }

            ProofOfDelivery proof = new ProofOfDelivery(
                    UUID.randomUUID(),
                    parcelId,
                    newRef.value(),
                    "PHOTO",
                    normalizedType,
                    contentLength,
                    recipientName,
                    deliveryNote,
                    actorSub,
                    Instant.now()
            );

            ProofOfDelivery saved = proofRepository.saveAndFlush(proof);
            log.info("Uploaded proof {} for parcel {}", saved.getProofId(), parcelId);
            return toResponse(saved);
        } catch (RuntimeException e) {
            log.error("Database write failed after proof upload for parcel {}. Triggering compensation.", parcelId);
            if (newRef != null) {
                compensationService.compensateOrphan(newRef, "DB_PERSISTENCE_FAILURE");
            }
            throw e;
        }
    }

    private void registerRollbackCompensation(ProofObjectReference newRef, UUID parcelId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        log.info("Transaction rolled back: compensating newly uploaded orphan proof object for parcel {}", parcelId);
                        compensationService.compensateOrphan(newRef, "TRANSACTION_ROLLBACK");
                    }
                }
            });
        }
    }

    private void registerPostCommitOldObjectCleanup(UUID taskId, String oldRef) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executeOldObjectCleanup(taskId, oldRef);
                }
            });
        } else {
            // When not running in a transaction synchronization context (e.g. standalone test)
            executeOldObjectCleanup(taskId, oldRef);
        }
    }

    private void executeOldObjectCleanup(UUID taskId, String oldRef) {
        try {
            // Re-verify that no active proof record in the DB still points to oldRef
            if (proofRepository.countByObjectReference(oldRef) > 0) {
                log.warn("Replaced proof object is still referenced by another proof record. Skipping deletion for task {}", taskId);
                if (taskCoordinator != null && taskId != null) {
                    taskCoordinator.markFailed(taskId, "STILL_REFERENCED");
                } else if (cleanupTaskRepository != null && taskId != null) {
                    cleanupTaskRepository.findById(taskId).ifPresent(t -> {
                        t.setStatus("FAILED");
                        cleanupTaskRepository.save(t);
                    });
                }
                return;
            }
            if (proofObjectStorage != null) {
                try {
                    proofObjectStorage.delete(new ProofObjectReference(oldRef));
                    log.info("Post-commit successfully deleted replaced proof object for task {}", taskId);
                } catch (ProofObjectNotFoundException ex) {
                    log.info("Replaced proof object was already absent from storage for task {}", taskId);
                }

                if (taskCoordinator != null && taskId != null) {
                    taskCoordinator.markCompleted(taskId);
                } else if (cleanupTaskRepository != null && taskId != null) {
                    cleanupTaskRepository.findById(taskId).ifPresent(t -> {
                        t.setStatus("COMPLETED");
                        cleanupTaskRepository.save(t);
                    });
                }
            }
        } catch (Exception ex) {
            log.warn("Post-commit synchronous deletion of replaced proof failed for task {}. Cleanup task remains durable for retry.",
                    taskId);
            if (taskCoordinator != null && taskId != null) {
                taskCoordinator.recordFailureAttempt(taskId, "POST_COMMIT_FAILED");
            }
        }
    }

    /**
     * Retrieves proof metadata for authorized actors.
     */
    @Transactional(readOnly = true)
    public ProofDetailResponse getProofMetadata(
            UUID parcelId,
            String actorSub,
            Collection<String> roles) {

        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(actorSub, "actorSub cannot be null");

        Parcel parcel = getParcelOrThrow(parcelId);
        authorizeActorForRead(parcel, actorSub, roles);

        ProofOfDelivery proof = proofRepository.findByParcelId(parcelId)
                .orElseThrow(() -> new ProofNotFoundException(parcelId));

        if (proof.isPurged()) {
            throw new ProofNotFoundException(parcelId);
        }

        return new ProofDetailResponse(
                proof.getProofId(),
                proof.getParcelId(),
                proof.getObjectReference(),
                proof.getEvidenceType(),
                proof.getContentType(),
                proof.getContentLength(),
                proof.getRecipientName(),
                proof.getDeliveryNote(),
                proof.getUploadedBySub(),
                proof.getCreatedAt()
        );
    }

    /**
     * Retrieves the streaming proof binary from private object storage.
     */
    @Transactional(readOnly = true)
    public RetrievedProofObject getProofContent(
            UUID parcelId,
            String actorSub,
            Collection<String> roles) {

        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(actorSub, "actorSub cannot be null");

        Parcel parcel = getParcelOrThrow(parcelId);
        authorizeActorForRead(parcel, actorSub, roles);

        ProofOfDelivery proof = proofRepository.findByParcelId(parcelId)
                .orElseThrow(() -> new ProofNotFoundException(parcelId));

        if (proof.isPurged() || proof.getObjectReference() == null) {
            throw new ProofNotFoundException(parcelId);
        }

        if (proofObjectStorage == null) {
            throw new IllegalStateException("ProofObjectStorage is not configured");
        }

        ProofObjectReference reference = new ProofObjectReference(proof.getObjectReference());
        return proofObjectStorage.retrieve(reference);
    }

    private Parcel getParcelOrThrow(UUID parcelId) {
        return parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ParcelNotFoundException(parcelId));
    }

    private void authorizeCourierForUpload(UUID parcelId, String actorSub, Collection<String> roles) {
        boolean hasCourierRole = roles != null && roles.stream()
                .anyMatch(r -> r.equalsIgnoreCase("COURIER") || r.equalsIgnoreCase("ROLE_COURIER"));
        if (!hasCourierRole) {
            throw new ProofAccessDeniedException("Forbidden: Only couriers with role COURIER may upload proof");
        }

        if (courierAssignmentContract != null) {
            boolean isAssigned = courierAssignmentContract.hasActiveAssignment(parcelId, actorSub);
            if (!isAssigned) {
                throw new ProofAccessDeniedException("Forbidden: Courier " + actorSub + " is not actively assigned to parcel " + parcelId);
            }
        }
    }

    private void authorizeActorForRead(Parcel parcel, String actorSub, Collection<String> roles) {
        // 1. Dispatcher or Admin
        if (roles != null && roles.stream().anyMatch(r ->
                r.equalsIgnoreCase("DISPATCHER") || r.equalsIgnoreCase("ROLE_DISPATCHER") ||
                r.equalsIgnoreCase("ADMIN") || r.equalsIgnoreCase("ROLE_ADMIN"))) {
            return;
        }

        // 2. Customer creator
        if (Objects.equals(parcel.getSenderSub(), actorSub)) {
            return;
        }

        // 3. Assigned courier
        if (courierAssignmentContract != null) {
            boolean isAssigned = courierAssignmentContract.hasActiveAssignment(parcel.getParcelId(), actorSub);
            if (isAssigned) {
                return;
            }
        }

        // 4. Uploader courier
        Optional<ProofOfDelivery> proof = proofRepository.findByParcelId(parcel.getParcelId());
        if (proof.isPresent() && Objects.equals(proof.get().getUploadedBySub(), actorSub)) {
            return;
        }

        throw new ProofAccessDeniedException("Forbidden: Caller " + actorSub + " is not authorized to access proof for parcel " + parcel.getParcelId());
    }

    private void validateLifecycleStageForUpload(Parcel parcel) {
        ParcelStatus status = parcel.getStatus();
        if (status.isTerminal()) {
            throw new IllegalLifecycleStageException("Cannot upload proof for terminal parcel in stage " + status);
        }
        if (status == ParcelStatus.CREATED) {
            throw new IllegalLifecycleStageException("Cannot upload proof before parcel pickup. Current stage: " + status);
        }
        // Allowed stages: OUT_FOR_DELIVERY (primary stage for delivery proof), or in-transit
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "image/jpeg";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon > 0 ? contentType.substring(0, semicolon) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    private ProofResponse toResponse(ProofOfDelivery proof) {
        return new ProofResponse(
                proof.getProofId(),
                proof.getParcelId(),
                proof.getUploadedBySub(),
                proof.getCreatedAt()
        );
    }
}
