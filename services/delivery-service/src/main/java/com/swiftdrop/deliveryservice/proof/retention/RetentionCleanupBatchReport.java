package com.swiftdrop.deliveryservice.proof.retention;

import java.util.Collections;
import java.util.List;

/**
 * Summary metrics of a bounded proof retention cleanup execution batch.
 */
public record RetentionCleanupBatchReport(
        boolean executionAttempted,
        String policyStatusMessage,
        int totalCandidatesEvaluated,
        int purgedCount,
        int skippedCount,
        int failedCount,
        List<RetentionCleanupResult> results
) {
    public static RetentionCleanupBatchReport disabled() {
        return new RetentionCleanupBatchReport(
                false,
                "Retention execution skipped: DEC-RET-01 policy is unapproved or disabled",
                0, 0, 0, 0,
                Collections.emptyList()
        );
    }

    public static RetentionCleanupBatchReport of(
            String policyStatusMessage,
            List<RetentionCleanupResult> results
    ) {
        int purged = 0;
        int skipped = 0;
        int failed = 0;
        for (RetentionCleanupResult result : results) {
            switch (result.status()) {
                case PURGED -> purged++;
                case SKIPPED_INELIGIBLE, SKIPPED_ALREADY_PURGED -> skipped++;
                case FAILED_STORAGE_UNSUPPORTED, FAILED_STORAGE_ERROR, FAILED_DATABASE_ERROR -> failed++;
            }
        }
        return new RetentionCleanupBatchReport(
                true,
                policyStatusMessage,
                results.size(),
                purged,
                skipped,
                failed,
                List.copyOf(results)
        );
    }
}
