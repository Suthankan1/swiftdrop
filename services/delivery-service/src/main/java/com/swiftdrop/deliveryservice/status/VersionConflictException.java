package com.swiftdrop.deliveryservice.status;

/**
 * Thrown when an optimistic concurrency check fails because expectedVersion does not match current version.
 */
public class VersionConflictException extends RuntimeException {

    private final Long expectedVersion;
    private final Long actualVersion;

    public VersionConflictException(Long expectedVersion, Long actualVersion) {
        super(String.format("Optimistic lock failure: expected version %d but was %d", expectedVersion, actualVersion));
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public Long getExpectedVersion() {
        return expectedVersion;
    }

    public Long getActualVersion() {
        return actualVersion;
    }
}
