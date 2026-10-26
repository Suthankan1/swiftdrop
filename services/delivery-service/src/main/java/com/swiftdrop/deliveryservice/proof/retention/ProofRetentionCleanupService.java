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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for safe, bounded, privacy-preserving proof retention cleanup.
 *
 * <p>CRITICAL POLICY INVARIANT:
 * Retains proof objects until policy DEC-RET-01 is explicitly approved.
 * Bounded candidate processing, strict fail-closed evaluation, verified storage deletion,
 * privacy-safe logging, and crash-resilient tombstoning.</p>
 */
@Service
public class ProofRetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ProofRetentionCleanupService.class);

    private final ProofRetentionProperties properties;
    private final ProofRetentionEvaluator evaluator;
    private final ProofOfDeliveryRepository proofRepository;
    private final ParcelRepository parcelRepository;
    private final ProofObjectStorage proofObjectStorage;
    private final ProofHoldProvider holdProvider;
    private final Clock clock;

    public ProofRetentionCleanupService(
            ProofRetentionProperties properties,
            ProofRetentionEvaluator evaluator,
            ProofOfDeliveryRepository proofRepository,
            ParcelRepository parcelRepository,
            @Autowired(required = false) ProofObjectStorage proofObjectStorage,
            @Autowired(required = false) ProofHoldProvider holdProvider,
            @Autowired(required = false) Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
        this.proofRepository = Objects.requireNonNull(proofRepository, "proofRepository cannot be null");
        this.parcelRepository = Objects.requireNonNull(parcelRepository, "parcelRepository cannot be null");
        this.proofObjectStorage = proofObjectStorage;
        this.holdProvider = holdProvider != null ? holdProvider : new DefaultProofHoldProvider();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Executes one bounded batch of candidate proof cleanups if approved by policy.
     * Returns a summary report with execution metrics.
     */
    public RetentionCleanupBatchReport executeCleanupBatch() {
        if (!properties.isValidAndApproved()) {
            log.info("Proof retention cleanup execution skipped: policy DEC-RET-01 is unapproved or disabled.");
            return RetentionCleanupBatchReport.disabled();
        }

        Instant now = clock.instant();
        Instant olderThan = now.minus(Duration.ofDays(properties.getRetentionDays()));
        Pageable pageable = PageRequest.of(0, properties.getBatchSize());

        List<ProofOfDelivery> candidates = proofRepository.findCandidatesForRetention(
                RetentionStatus.ACTIVE, olderThan, pageable);

        if (candidates.isEmpty()) {
            log.debug("No active candidate proofs found older than {}", olderThan);
            return RetentionCleanupBatchReport.of("No candidate proofs found", Collections.emptyList());
        }

        log.info("Processing retention batch of {} candidate proof records", candidates.size());
        List<RetentionCleanupResult> results = new ArrayList<>();

        for (ProofOfDelivery candidate : candidates) {
            try {
                RetentionCleanupResult result = processCandidate(candidate.getProofId());
                results.add(result);
            } catch (Exception ex) {
                log.error("Unexpected failure processing retention candidate proof {}: {}",
                        candidate.getProofId(), ex.getMessage());
                results.add(RetentionCleanupResult.failedDatabase(
                        candidate.getProofId(), candidate.getParcelId(), ex.getMessage()));
            }
        }

        return RetentionCleanupBatchReport.of("Completed bounded cleanup batch", results);
    }

    /**
     * Atomically inspects, evaluates, physically purges, and tombstones a single candidate proof.
     * Operates in an isolated transaction to prevent batch poisoning.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetentionCleanupResult processCandidate(UUID proofId) {
        Objects.requireNonNull(proofId, "proofId cannot be null");

        // 1. Re-read under pessimistic lock or isolated transaction
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
        } catch (RuntimeException ex) {
            log.error("Physical storage deletion failed for proof {} (parcel {}): {}",
                    proofId, parcel.getParcelId(), ex.getMessage());
            return RetentionCleanupResult.failedStorage(proofId, parcel.getParcelId(), ex.getMessage());
        }

        // 6. Atomically persist privacy tombstone in database
        proof.markPurged(clock.instant());
        proofRepository.saveAndFlush(proof);
        log.info("Proof metadata tombstoned for proof {} (parcel {})", proofId, parcel.getParcelId());

        return RetentionCleanupResult.purged(proofId, parcel.getParcelId());
    }
}
