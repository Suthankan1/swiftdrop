package com.swiftdrop.deliveryservice.proof.retention;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.proof.ProofOfDelivery;
import com.swiftdrop.deliveryservice.proof.ProofOfDeliveryRepository;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated transactional worker for processing individual proof retention candidates.
 *
 * <p>CRITICAL TRANSACTION INVARIANT:
 * Executed as a distinct Spring-managed component to ensure {@link Propagation#REQUIRES_NEW}
 * transaction proxy interception is active on every candidate call. Bounded outer batch coordination
 * invokes this processor without proxy self-invocation bypass.</p>
 */
@Component
public class ProofRetentionCandidateProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProofRetentionCandidateProcessor.class);

    private final ProofOfDeliveryRepository proofRepository;
    private final ParcelRepository parcelRepository;
    private final ProofRetentionEvaluator evaluator;
    private final ProofObjectStorage proofObjectStorage;
    private final ProofHoldProvider holdProvider;
    private final Clock clock;

    public ProofRetentionCandidateProcessor(
            ProofOfDeliveryRepository proofRepository,
            ParcelRepository parcelRepository,
            ProofRetentionEvaluator evaluator,
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            @Autowired(required = false) ProofHoldProvider holdProvider,
            @Autowired(required = false) Clock clock) {
        this.proofRepository = Objects.requireNonNull(proofRepository, "proofRepository cannot be null");
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "parcelRepository cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
        this.proofObjectStorage = proofObjectStorage;
        this.holdProvider = holdProvider != null ? holdProvider : new DefaultProofHoldProvider();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Atomically inspects, evaluates, physically purges, and tombstones a single candidate proof.
     * Operates in an isolated {@link Propagation#REQUIRES_NEW} transaction to prevent batch poisoning.
     * Pessimistic row locking is acquired inside this transaction boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetentionCleanupResult processCandidate(UUID proofId) {
        Objects.requireNonNull(proofId, "proofId cannot be null");

        // 1. Re-read under pessimistic write lock inside this isolated transaction
        Optional<ProofOfDelivery> proofOpt = proofRepository.findByIdForUpdate(proofId);
        if (proofOpt.isEmpty()) {
            return RetentionCleanupResult.skippedAlreadyPurged(proofId, null);
        }
        ProofOfDelivery proof = proofOpt.get();

        if (proof.isPurged() || proof.getObjectReference() == null) {
            return RetentionCleanupResult.skippedAlreadyPurged(proofId, proof.getParcelId());
        }

        // 2. Authoritative parcel context
        Optional<Parcel> parcelOpt = parcelRepository.findById(proof.getParcelId());
        if (parcelOpt.isEmpty()) {
            log.warn("Authoritative parcel not found for proof {}. Ineligible.", proofId);
            return RetentionCleanupResult.skippedIneligible(
                    proofId, proof.getParcelId(), "Authoritative parcel record not found");
        }
        Parcel parcel = parcelOpt.get();

        // 3. Strict eligibility evaluation
        RetentionEvaluation evaluation = evaluator.evaluate(proof, parcel, holdProvider);
        if (!evaluation.eligible()) {
            log.info("Retention cleanup ineligible for proof {} (parcel {}): {}",
                    proofId, parcel.getParcelId(), evaluation.rejectionDetails());
            return RetentionCleanupResult.skippedIneligible(
                    proofId, parcel.getParcelId(), evaluation.rejectionDetails());
        }

        // 4. Storage capability verification (never accept no-op adapter as deletion)
        if (proofObjectStorage == null || !proofObjectStorage.supportsDeletion()) {
            log.warn("Storage deletion unsupported for proof {}. Adapter cannot guarantee physical removal.", proofId);
            return RetentionCleanupResult.failedStorageUnsupported(proofId, parcel.getParcelId());
        }

        // 5. Execute physical storage deletion
        ProofObjectReference reference = new ProofObjectReference(proof.getObjectReference());
        try {
            proofObjectStorage.delete(reference);
            log.info("Physical storage object deleted for proof {} (parcel {})", proofId, parcel.getParcelId());
        } catch (com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException notFound) {
            log.info("Physical storage object was already absent for proof {} (parcel {})", proofId, parcel.getParcelId());
        } catch (RuntimeException ex) {
            log.error("Physical storage deletion failed for proof {} (parcel {})", proofId, parcel.getParcelId());
            // Roll back database transaction so proof metadata remains ACTIVE
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (org.springframework.transaction.NoTransactionException ignored) {
                // Running without Spring transaction context (e.g. standalone unit test)
            }
            String sanitizedMessage = sanitizeStorageMessage(ex.getMessage());
            return RetentionCleanupResult.failedStorage(proofId, parcel.getParcelId(), sanitizedMessage);
        }

        // 6. Atomically persist privacy tombstone in database
        proof.markPurged(clock.instant());
        proofRepository.saveAndFlush(proof);
        log.info("Proof metadata tombstoned for proof {} (parcel {})", proofId, parcel.getParcelId());

        return RetentionCleanupResult.purged(proofId, parcel.getParcelId());
    }

    private String sanitizeStorageMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Physical storage deletion failed";
        }
        return message.replaceAll("proofs/v1/[0-9a-fA-F-]+", "[REDACTED_OBJECT_REF]");
    }
}
