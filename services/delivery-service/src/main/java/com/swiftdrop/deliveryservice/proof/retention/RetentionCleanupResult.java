package com.swiftdrop.deliveryservice.proof.retention;

import java.util.UUID;

/**
 * Result of attempting retention cleanup on an individual proof record.
 */
public record RetentionCleanupResult(
        UUID proofId,
        UUID parcelId,
        Status status,
        String message
) {
    public enum Status {
        /**
         * Physical storage object permanently deleted and database record safely tombstoned.
         */
        PURGED,

        /**
         * Processing skipped because the proof was evaluated as ineligible.
         */
        SKIPPED_INELIGIBLE,

        /**
         * Processing skipped because the proof is already tombstoned.
         */
        SKIPPED_ALREADY_PURGED,

        /**
         * Physical deletion could not be executed because storage adapter does not support deletion.
         */
        FAILED_STORAGE_UNSUPPORTED,

        /**
         * Physical storage deletion failed or timed out.
         */
        FAILED_STORAGE_ERROR,

        /**
         * Database tombstoning failed after storage operation.
         */
        FAILED_DATABASE_ERROR
    }

    public static RetentionCleanupResult purged(UUID proofId, UUID parcelId) {
        return new RetentionCleanupResult(proofId, parcelId, Status.PURGED, "Proof successfully purged and tombstoned");
    }

    public static RetentionCleanupResult skippedIneligible(UUID proofId, UUID parcelId, String reason) {
        return new RetentionCleanupResult(proofId, parcelId, Status.SKIPPED_INELIGIBLE, reason);
    }

    public static RetentionCleanupResult skippedAlreadyPurged(UUID proofId, UUID parcelId) {
        return new RetentionCleanupResult(proofId, parcelId, Status.SKIPPED_ALREADY_PURGED, "Proof already purged");
    }

    public static RetentionCleanupResult failedStorageUnsupported(UUID proofId, UUID parcelId) {
        return new RetentionCleanupResult(proofId, parcelId, Status.FAILED_STORAGE_UNSUPPORTED, "Configured storage adapter does not support physical deletion");
    }

    public static RetentionCleanupResult failedStorage(UUID proofId, UUID parcelId, String error) {
        return new RetentionCleanupResult(proofId, parcelId, Status.FAILED_STORAGE_ERROR, error);
    }

    public static RetentionCleanupResult failedDatabase(UUID proofId, UUID parcelId, String error) {
        return new RetentionCleanupResult(proofId, parcelId, Status.FAILED_DATABASE_ERROR, error);
    }
}
