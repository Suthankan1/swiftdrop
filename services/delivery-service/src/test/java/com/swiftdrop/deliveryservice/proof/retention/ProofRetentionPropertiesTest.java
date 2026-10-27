package com.swiftdrop.deliveryservice.proof.retention;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProofRetentionPropertiesTest {

    @Test
    @DisplayName("Defaults fail closed: disabled, unapproved, zero duration, empty terminal states")
    void defaults_failClosed() {
        ProofRetentionProperties props = new ProofRetentionProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.isPolicyApproved()).isFalse();
        assertThat(props.isSchedulerEnabled()).isFalse();
        assertThat(props.getPolicyVersion()).isNull();
        assertThat(props.getRetentionDays()).isNull();
        assertThat(props.getClockStartEvent()).isNull();
        assertThat(props.getEligibleTerminalStates()).isEmpty();
        assertThat(props.getBatchSize()).isEqualTo(50);

        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if enabled=true but policyApproved=false")
    void isValidAndApproved_missingApproval_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setPolicyApproved(false);

        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if policyApproved=true but enabled=false")
    void isValidAndApproved_disabled_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setEnabled(false);

        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if policyVersion is missing or blank")
    void isValidAndApproved_missingPolicyVersion_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setPolicyVersion("   ");

        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if retentionDays is null, zero, or negative")
    void isValidAndApproved_invalidRetentionDays_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setRetentionDays(null);
        assertThat(props.isValidAndApproved()).isFalse();

        props.setRetentionDays(0);
        assertThat(props.isValidAndApproved()).isFalse();

        props.setRetentionDays(-30);
        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if clockStartEvent is null")
    void isValidAndApproved_missingClockStartEvent_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setClockStartEvent(null);

        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if eligibleTerminalStates is null or empty")
    void isValidAndApproved_missingTerminalStates_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setEligibleTerminalStates(null);
        assertThat(props.isValidAndApproved()).isFalse();

        props.setEligibleTerminalStates(Set.of());
        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if eligibleTerminalStates contains a non-terminal state")
    void isValidAndApproved_nonTerminalStateInPolicy_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setEligibleTerminalStates(Set.of(ParcelStatus.DELIVERED, ParcelStatus.IN_TRANSIT));

        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Fails closed if batchSize is zero or negative")
    void isValidAndApproved_invalidBatchSize_failsClosed() {
        ProofRetentionProperties props = createValidSyntheticPolicy();
        props.setBatchSize(0);
        assertThat(props.isValidAndApproved()).isFalse();

        props.setBatchSize(-10);
        assertThat(props.isValidAndApproved()).isFalse();
    }

    @Test
    @DisplayName("Succeeds when fully approved and configured with valid terminal states")
    void isValidAndApproved_validApprovedPolicy_succeeds() {
        ProofRetentionProperties props = createValidSyntheticPolicy();

        assertThat(props.isValidAndApproved()).isTrue();
    }

    private ProofRetentionProperties createValidSyntheticPolicy() {
        ProofRetentionProperties props = new ProofRetentionProperties();
        props.setEnabled(true);
        props.setPolicyApproved(true);
        props.setPolicyVersion("DEC-RET-01-TEST-V1");
        props.setRetentionDays(90);
        props.setClockStartEvent(RetentionClockStartEvent.PROOF_UPLOAD_TIME);
        props.setEligibleTerminalStates(Set.of(ParcelStatus.DELIVERED, ParcelStatus.FAILED));
        props.setBatchSize(50);
        return props;
    }
}
