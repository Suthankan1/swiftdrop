package com.swiftdrop.communicationsservice.notification.delivery;

public record NotificationAttemptResult(
        String outcome,
        String failureCategory,
        String safeErrorCode,
        long durationMs,
        boolean retryable
) {
    public static final String SUCCESS = "SUCCESS";
    public static final String TRANSIENT_FAILURE = "TRANSIENT_FAILURE";
    public static final String PERMANENT_FAILURE = "PERMANENT_FAILURE";
    public static final String SECURITY_FAILURE = "SECURITY_FAILURE";
    public static final String TIMEOUT = "TIMEOUT";

    public static NotificationAttemptResult success(long durationMs) {
        return new NotificationAttemptResult(SUCCESS, null, "DELIVERED_TO_TRANSPORT", durationMs, false);
    }

    public static NotificationAttemptResult transientFailure(String failureCategory, String safeErrorCode, long durationMs) {
        return new NotificationAttemptResult(TRANSIENT_FAILURE, failureCategory, safeErrorCode, durationMs, true);
    }

    public static NotificationAttemptResult permanentFailure(String failureCategory, String safeErrorCode, long durationMs) {
        return new NotificationAttemptResult(PERMANENT_FAILURE, failureCategory, safeErrorCode, durationMs, false);
    }

    public static NotificationAttemptResult securityFailure(String failureCategory, String safeErrorCode, long durationMs) {
        return new NotificationAttemptResult(SECURITY_FAILURE, failureCategory, safeErrorCode, durationMs, false);
    }

    public static NotificationAttemptResult timeout(String failureCategory, String safeErrorCode, long durationMs) {
        return new NotificationAttemptResult(TIMEOUT, failureCategory, safeErrorCode, durationMs, true);
    }

    public boolean isSuccess() {
        return SUCCESS.equals(outcome);
    }
}
