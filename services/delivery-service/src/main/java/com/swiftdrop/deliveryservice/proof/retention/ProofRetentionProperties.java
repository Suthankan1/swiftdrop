package com.swiftdrop.deliveryservice.proof.retention;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration properties for Proof of Delivery retention and privacy-safe cleanup.
 *
 * <p>CRITICAL POLICY INVARIANT:
 * Retention policy DEC-RET-01 remains PROPOSED — AWAITING REVIEW.
 * By default, retention execution is completely DISABLED and requires explicit policy approval
 * plus validated configuration before any deletion operation can execute.</p>
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.delivery.retention")
public class ProofRetentionProperties {

    /**
     * Master switch for proof retention cleanup execution.
     * Default: false (strictly disabled).
     */
    private boolean enabled = false;

    /**
     * Formal approval flag for the retention policy (DEC-RET-01).
     * Must be explicitly set to true after legal/privacy and platform sign-off.
     * Default: false (strictly unapproved).
     */
    private boolean policyApproved = false;

    /**
     * Policy version identifier (e.g., "DEC-RET-01-V1").
     * Must be non-blank when approved.
     */
    private String policyVersion;

    /**
     * Proof retention duration in days.
     * MUST NOT default to 90 days in production until DEC-RET-01 is officially approved.
     * Default: null (must be explicitly configured).
     */
    private Integer retentionDays;

    /**
     * The event that starts the retention clock.
     * Default: null (must be explicitly configured).
     */
    private RetentionClockStartEvent clockStartEvent;

    /**
     * The canonical terminal parcel states eligible for proof retention cleanup.
     * Default: empty (must be explicitly configured).
     */
    private Set<ParcelStatus> eligibleTerminalStates = Collections.emptySet();

    /**
     * Maximum number of candidate proofs to process per cleanup execution run.
     * Default: 50.
     */
    private int batchSize = 50;

    /**
     * Whether scheduled background cleanup execution is enabled.
     * Default: false (strictly disabled).
     */
    private boolean schedulerEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPolicyApproved() {
        return policyApproved;
    }

    public void setPolicyApproved(boolean policyApproved) {
        this.policyApproved = policyApproved;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public RetentionClockStartEvent getClockStartEvent() {
        return clockStartEvent;
    }

    public void setClockStartEvent(RetentionClockStartEvent clockStartEvent) {
        this.clockStartEvent = clockStartEvent;
    }

    public Set<ParcelStatus> getEligibleTerminalStates() {
        return eligibleTerminalStates;
    }

    public void setEligibleTerminalStates(Set<ParcelStatus> eligibleTerminalStates) {
        this.eligibleTerminalStates = eligibleTerminalStates != null ? Set.copyOf(eligibleTerminalStates) : Collections.emptySet();
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    /**
     * Evaluates if the current policy configuration is fully approved and structurally valid
     * to safely permit cleanup execution.
     * Fails closed if any required parameter is missing, non-positive, or unapproved.
     */
    public boolean isValidAndApproved() {
        if (!enabled || !policyApproved) {
            return false;
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            return false;
        }
        if (retentionDays == null || retentionDays <= 0) {
            return false;
        }
        if (clockStartEvent == null) {
            return false;
        }
        if (eligibleTerminalStates == null || eligibleTerminalStates.isEmpty()) {
            return false;
        }
        // Verify that all configured eligible states are actually terminal parcel states
        for (ParcelStatus status : eligibleTerminalStates) {
            if (status == null || !status.isTerminal()) {
                return false;
            }
        }
        return batchSize > 0;
    }
}
