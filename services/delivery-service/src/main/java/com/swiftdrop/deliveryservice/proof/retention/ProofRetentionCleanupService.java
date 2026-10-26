package com.swiftdrop.deliveryservice.proof.retention;

import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.proof.ProofOfDelivery;
import com.swiftdrop.deliveryservice.proof.ProofOfDeliveryRepository;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
    private final ProofRetentionCandidateProcessor candidateProcessor;
    private final ProofOfDeliveryRepository proofRepository;
    private final Clock clock;

    @Autowired
    public ProofRetentionCleanupService(
            ProofRetentionProperties properties,
            ProofRetentionCandidateProcessor candidateProcessor,
            ProofOfDeliveryRepository proofRepository,
            @Autowired(required = false) Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.candidateProcessor = Objects.requireNonNull(candidateProcessor, "candidateProcessor cannot be null");
        this.proofRepository = Objects.requireNonNull(proofRepository, "proofRepository cannot be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Backward-compatible constructor for testing seams.
     */
    public ProofRetentionCleanupService(
            ProofRetentionProperties properties,
            ProofRetentionEvaluator evaluator,
            ProofOfDeliveryRepository proofRepository,
            ParcelRepository parcelRepository,
            ProofObjectStorage proofObjectStorage,
            ProofHoldProvider holdProvider,
            Clock clock) {
        this(
                properties,
                new ProofRetentionCandidateProcessor(
                        proofRepository,
                        parcelRepository,
                        evaluator,
                        proofObjectStorage,
                        holdProvider,
                        clock
                ),
                proofRepository,
                clock
        );
    }

    /**
     * Executes one bounded batch of candidate proof cleanups on page 0 if approved by policy.
     *
     * @return summary report with execution metrics
     */
    public RetentionCleanupBatchReport executeCleanupBatch() {
        return executeCleanupBatch(0);
    }

    /**
     * Executes one bounded batch of candidate proof cleanups on the specified page if approved by policy.
     * Paginating across pages allows operators and schedulers to avoid candidate starvation when earlier
     * pages contain unexpired or held proof records.
     *
     * @param pageNumber the 0-based page index to evaluate
     * @return summary report with execution metrics
     */
    public RetentionCleanupBatchReport executeCleanupBatch(int pageNumber) {
        if (!properties.isValidAndApproved()) {
            log.info("Proof retention cleanup execution skipped: policy DEC-RET-01 is unapproved or disabled.");
            return RetentionCleanupBatchReport.disabled();
        }

        Instant now = clock.instant();
        Instant olderThan = now.minus(Duration.ofDays(properties.getRetentionDays()));
        Pageable pageable = PageRequest.of(Math.max(0, pageNumber), properties.getBatchSize());

        List<ProofOfDelivery> candidates = proofRepository.findCandidatesForRetention(
                RetentionStatus.ACTIVE, olderThan, pageable);

        if (candidates.isEmpty()) {
            log.debug("No active candidate proofs found older than {} (page {})", olderThan, pageNumber);
            return RetentionCleanupBatchReport.of("No candidate proofs found", Collections.emptyList());
        }

        log.info("Processing retention batch of {} candidate proof records (page {})", candidates.size(), pageNumber);
        List<RetentionCleanupResult> results = new ArrayList<>();

        for (ProofOfDelivery candidate : candidates) {
            try {
                // Invoked on injected Spring component: REQUIRES_NEW transaction proxy is active
                RetentionCleanupResult result = candidateProcessor.processCandidate(candidate.getProofId());
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
     * Delegates single candidate processing to the transactional candidate processor.
     */
    public RetentionCleanupResult processCandidate(UUID proofId) {
        return candidateProcessor.processCandidate(proofId);
    }
}
