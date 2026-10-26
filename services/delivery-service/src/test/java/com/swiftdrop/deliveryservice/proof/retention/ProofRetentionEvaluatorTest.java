package com.swiftdrop.deliveryservice.proof.retention;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.ProofOfDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

class ProofRetentionEvaluatorTest {

    private static final Instant EVALUATION_TIME = Instant.parse("2026-10-26T12:00:00Z");
    private static final String VALID_OBJECT_REF = "proofs/v1/" + UUID.randomUUID();

    private Clock fixedClock;
    private ProofRetentionProperties approvedPolicy;
    private DefaultProofHoldProvider holdProvider;
    private ProofRetentionEvaluator evaluator;

    private UUID parcelId;
    private UUID proofId;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(EVALUATION_TIME, ZoneOffset.UTC);
        approvedPolicy = new ProofRetentionProperties();
        approvedPolicy.setEnabled(true);
        approvedPolicy.setPolicyApproved(true);
        approvedPolicy.setPolicyVersion("DEC-RET-01-TEST-V1");
        approvedPolicy.setRetentionDays(90);
        approvedPolicy.setClockStartEvent(RetentionClockStartEvent.PROOF_UPLOAD_TIME);
        approvedPolicy.setEligibleTerminalStates(Set.of(ParcelStatus.DELIVERED, ParcelStatus.FAILED));
        approvedPolicy.setBatchSize(50);

        holdProvider = new DefaultProofHoldProvider();
        evaluator = new ProofRetentionEvaluator(approvedPolicy, fixedClock);

        parcelId = UUID.randomUUID();
        proofId = UUID.randomUUID();
    }

    private Parcel createParcel(ParcelStatus status, Instant terminalAt) {
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
        if (terminalAt != null) {
            parcel.setCreatedAt(terminalAt);
            parcel.setUpdatedAt(terminalAt);
        }
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

    @Nested
    @DisplayName("Policy Gating & Approval Checks")
    class PolicyGatingTests {

        @Test
        @DisplayName("Rejects evaluation when retention policy is unapproved")
        void policyUnapproved_rejected() {
            approvedPolicy.setPolicyApproved(false);
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.POLICY_NOT_APPROVED);
        }

        @Test
        @DisplayName("Rejects evaluation when retention master switch is disabled")
        void policyDisabled_rejected() {
            approvedPolicy.setEnabled(false);
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.POLICY_NOT_APPROVED);
        }

        @Test
        @DisplayName("Fails closed when policy configuration is missing required parameters")
        void policyIncomplete_failsClosed() {
            approvedPolicy.setRetentionDays(null);
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.POLICY_CONFIG_MISSING);
        }
    }

    @Nested
    @DisplayName("Null & Entity Identity Checks")
    class IdentityAndNullChecks {

        @Test
        @DisplayName("Fails closed when proof record is null")
        void nullProof_failsClosed() {
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(null, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.MISSING_PROOF_RECORD);
        }

        @Test
        @DisplayName("Fails closed when parcel context is null")
        void nullParcel_failsClosed() {
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, null, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.MISSING_PARCEL_CONTEXT);
        }

        @Test
        @DisplayName("Fails closed when parcelId on proof does not match parcel context")
        void mismatchedParcelId_failsClosed() {
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel differentParcel = new Parcel(
                    UUID.randomUUID(), "SWD-DIFF-9999", "cust", null, null, null, 100L, "LKR", BigDecimal.ONE);
            differentParcel.setStatus(ParcelStatus.DELIVERED);

            RetentionEvaluation result = evaluator.evaluate(proof, differentParcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.PARCEL_ID_MISMATCH);
        }
    }

    @Nested
    @DisplayName("Proof Status & Storage Reference Validation")
    class ProofStatusAndReferenceTests {

        @Test
        @DisplayName("Rejects evaluation if proof is already purged")
        void alreadyPurged_rejected() {
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            proof.markPurged(EVALUATION_TIME.minus(Duration.ofDays(1)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.ALREADY_PURGED);
        }

        @Test
        @DisplayName("Rejects evaluation if object reference is null or blank")
        void missingObjectReference_rejected() {
            ProofOfDelivery proof = new ProofOfDelivery(
                    proofId, parcelId, VALID_OBJECT_REF, "PHOTO", "image/jpeg", 1024L,
                    "Recipient", "Note", "courier", EVALUATION_TIME.minus(Duration.ofDays(100))
            );
            // Simulate missing reference
            proof.markPurged(null); // clears reference

            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
        }

        @Test
        @DisplayName("Rejects evaluation if object reference has malformed/non-opaque format")
        void malformedObjectReference_rejected() {
            ProofOfDelivery proof = new ProofOfDelivery(
                    proofId, parcelId, "https://s3.amazonaws.com/bucket/photo.jpg", "PHOTO", "image/jpeg", 1024L,
                    "Recipient", "Note", "courier", EVALUATION_TIME.minus(Duration.ofDays(100))
            );
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.MALFORMED_OBJECT_REFERENCE);
        }
    }

    @Nested
    @DisplayName("Parcel Lifecycle Stage Eligibility")
    class ParcelLifecycleStageTests {

        @Test
        @DisplayName("Preserves proof when parcel is in non-terminal state OUT_FOR_DELIVERY")
        void nonTerminalState_outForDelivery_preserved() {
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.OUT_FOR_DELIVERY, null);

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.INELIGIBLE_PARCEL_STATE);
        }

        @Test
        @DisplayName("Preserves proof when parcel is in non-terminal state IN_TRANSIT")
        void nonTerminalState_inTransit_preserved() {
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, null);

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.INELIGIBLE_PARCEL_STATE);
        }

        @Test
        @DisplayName("Preserves proof when parcel terminal state is excluded from policy (e.g. policy excludes FAILED)")
        void terminalStateExcludedByPolicy_preserved() {
            approvedPolicy.setEligibleTerminalStates(Set.of(ParcelStatus.DELIVERED)); // only DELIVERED
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.FAILED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.INELIGIBLE_PARCEL_STATE);
        }
    }

    @Nested
    @DisplayName("Legal & Operational Hold Protection")
    class HoldProtectionTests {

        @Test
        @DisplayName("Preserves proof when parcel is under active legal hold")
        void activeParcelHold_preserved() {
            holdProvider.addParcelHold(parcelId);
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.ACTIVE_LEGAL_OR_OPERATIONAL_HOLD);
        }

        @Test
        @DisplayName("Preserves proof when proofId is under active operational hold")
        void activeProofHold_preserved() {
            holdProvider.addProofHold(proofId);
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.ACTIVE_LEGAL_OR_OPERATIONAL_HOLD);
        }

        @Test
        @DisplayName("Fails closed when hold provider reports indeterminate hold status")
        void indeterminateHoldStatus_failsClosed() {
            holdProvider.setAuthoritative(false); // cannot authoritatively determine hold
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.HOLD_STATUS_INDETERMINATE);
        }

        @Test
        @DisplayName("Fails closed when hold provider is null")
        void nullHoldProvider_failsClosed() {
            ProofOfDelivery proof = createProof(EVALUATION_TIME.minus(Duration.ofDays(100)));
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, EVALUATION_TIME.minus(Duration.ofDays(100)));

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, null);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.HOLD_STATUS_INDETERMINATE);
        }
    }

    @Nested
    @DisplayName("Retention Clock & Duration Boundary Evaluation")
    class RetentionClockBoundaryTests {

        @Test
        @DisplayName("Preserves unexpired proof when duration has not elapsed (89 days < 90 days)")
        void unexpiredProof_preserved() {
            Instant uploadedAt = EVALUATION_TIME.minus(Duration.ofDays(89));
            ProofOfDelivery proof = createProof(uploadedAt);
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, uploadedAt);

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.RETENTION_PERIOD_NOT_ELAPSED);
        }

        @Test
        @DisplayName("Selects proof exactly at retention boundary (90 days elapsed)")
        void proofAtBoundary_eligible() {
            Instant uploadedAt = EVALUATION_TIME.minus(Duration.ofDays(90));
            ProofOfDelivery proof = createProof(uploadedAt);
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, uploadedAt);

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isTrue();
            assertThat(result.eligibleExpiryInstant()).isEqualTo(uploadedAt.plus(Duration.ofDays(90)));
        }

        @Test
        @DisplayName("Selects expired eligible synthetic proof (100 days > 90 days)")
        void expiredEligibleProof_eligible() {
            Instant uploadedAt = EVALUATION_TIME.minus(Duration.ofDays(100));
            ProofOfDelivery proof = createProof(uploadedAt);
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, uploadedAt);

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isTrue();
            assertThat(result.eligibleExpiryInstant()).isEqualTo(uploadedAt.plus(Duration.ofDays(90)));
        }

        @Test
        @DisplayName("Fails closed when retention clock start timestamp is in the future")
        void clockStartInFuture_failsClosed() {
            Instant futureUpload = EVALUATION_TIME.plus(Duration.ofHours(2));
            ProofOfDelivery proof = createProof(futureUpload);
            Parcel parcel = createParcel(ParcelStatus.DELIVERED, futureUpload);

            RetentionEvaluation result = evaluator.evaluate(proof, parcel, holdProvider);

            assertThat(result.eligible()).isFalse();
            assertThat(result.rejectionReason()).isEqualTo(RetentionRejectionReason.INVALID_CLOCK_TIMESTAMP);
        }
    }
}
