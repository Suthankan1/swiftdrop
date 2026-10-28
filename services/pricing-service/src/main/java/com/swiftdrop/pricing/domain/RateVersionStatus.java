package com.swiftdrop.pricing.domain;

/**
 * Lifecycle states for versioned rate cards.
 */
public enum RateVersionStatus {
    DRAFT,
    ACTIVE,
    SUPERSEDED,
    ARCHIVED;

    public boolean isPublished() {
        return this == ACTIVE || this == SUPERSEDED || this == ARCHIVED;
    }

    public boolean canMutateRules() {
        return this == DRAFT;
    }
}
