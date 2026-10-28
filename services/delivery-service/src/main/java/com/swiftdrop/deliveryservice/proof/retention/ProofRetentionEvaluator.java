package com.swiftdrop.deliveryservice.proof.retention;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.proof.ProofOfDelivery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic, testable evaluator for Proof of Delivery retention eligibility.
 *
 * <p>Enforces all pre-conditions from DEC-RET-01 before any deletion may be contemplated.
 * Adheres strictly to fail-closed semantics: any missing context, indeterminate hold,
 * or unapproved policy configuration immediately rejects deletion.</p>
 */
@Component
public class ProofRetentionEvaluator {

    private static final Pattern OBJECT_REF_PATTERN = Pattern.compile(
            "proofs/v1/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final ProofRetentionProperties properties;
    private final Clock clock;

    public ProofRetentionEvaluator(
            ProofRetentionProperties properties,
            @Autowired(required = false) Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Evaluates a candidate proof of delivery and its authoritative parcel context against approved retention policy.
     *
     * @param proof        the proof of delivery record
     * @param parcel       the authoritative parcel entity
     * @param holdProvider provider for querying legal and operational holds
     * @return a {@link RetentionEvaluation} with explicit eligibility or detailed rejection reason
     */
    public RetentionEvaluation evaluate(
            ProofOfDelivery proof,
            Parcel parcel,
            ProofHoldProvider holdProvider) {

        // 1. Policy Gate: Check master switch and formal approval (DEC-RET-01)
        if (!properties.isEnabled() || !properties.isPolicyApproved()) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.POLICY_NOT_APPROVED,
                    "Retention policy DEC-RET-01 is unapproved or disabled");
        }

        // 2. Policy Configuration Validity Check
        if (!properties.isValidAndApproved()) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.POLICY_CONFIG_MISSING,
                    "Required policy configuration parameters are missing or invalid");
        }

        // 3. Null and Identity Checks
        if (proof == null) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.MISSING_PROOF_RECORD,
                    "Candidate proof record is null");
        }
        if (parcel == null) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.MISSING_PARCEL_CONTEXT,
                    "Authoritative parcel context is missing");
        }
        if (!Objects.equals(proof.getParcelId(), parcel.getParcelId())) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.PARCEL_ID_MISMATCH,
                    "Proof parcelId does not match authoritative parcel entity ID");
        }

        // 4. Current Lifecycle Status of Proof
        if (proof.isPurged()) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.ALREADY_PURGED,
                    "Proof of delivery has already been purged and tombstoned");
        }
        if (proof.getObjectReference() == null || proof.getObjectReference().isBlank()) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.MISSING_OBJECT_REFERENCE,
                    "Proof has no object reference recorded");
        }
        if (!OBJECT_REF_PATTERN.matcher(proof.getObjectReference().trim()).matches()) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.MALFORMED_OBJECT_REFERENCE,
                    "Proof object reference does not match required opaque storage key format");
        }

        // 5. Canonical Parcel Stage Eligibility
        if (parcel.getStatus() == null || !parcel.getStatus().isTerminal()) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.INELIGIBLE_PARCEL_STATE,
                    "Parcel is in non-terminal state: " + parcel.getStatus());
        }
        if (!properties.getEligibleTerminalStates().contains(parcel.getStatus())) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.INELIGIBLE_PARCEL_STATE,
                    "Parcel status " + parcel.getStatus() + " is not in the approved eligible terminal states list");
        }

        // 6. Authoritative Legal and Operational Hold Check
        if (holdProvider == null) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.HOLD_STATUS_INDETERMINATE,
                    "No hold provider available to verify hold status; failing closed");
        }
        Optional<Boolean> holdStatus = holdProvider.isUnderHold(parcel.getParcelId(), proof.getProofId());
        if (holdStatus.isEmpty()) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.HOLD_STATUS_INDETERMINATE,
                    "Hold status cannot be authoritatively determined; failing closed");
        }
        if (Boolean.TRUE.equals(holdStatus.get())) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.ACTIVE_LEGAL_OR_OPERATIONAL_HOLD,
                    "Parcel or proof is currently under an active legal or operational hold");
        }

        // 7. Retention Clock and Duration Evaluation
        Instant clockStart = switch (properties.getClockStartEvent()) {
            case PROOF_UPLOAD_TIME -> proof.getCreatedAt();
            case PARCEL_TERMINAL_TIME -> parcel.getUpdatedAt();
        };

        Instant now = clock.instant();
        if (clockStart == null || clockStart.isAfter(now)) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.INVALID_CLOCK_TIMESTAMP,
                    "Retention clock start timestamp is null or in the future");
        }

        Instant expiryInstant = clockStart.plus(Duration.ofDays(properties.getRetentionDays()));
        if (now.isBefore(expiryInstant)) {
            return RetentionEvaluation.ineligible(
                    RetentionRejectionReason.RETENTION_PERIOD_NOT_ELAPSED,
                    "Configured retention period of " + properties.getRetentionDays()
                            + " days has not elapsed; expires at " + expiryInstant);
        }

        // Satisfies all policy, lifecycle, privacy, and duration criteria
        return RetentionEvaluation.eligible(expiryInstant);
    }
}
