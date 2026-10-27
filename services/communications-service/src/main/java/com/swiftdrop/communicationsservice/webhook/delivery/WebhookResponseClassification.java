package com.swiftdrop.communicationsservice.webhook.delivery;

/**
 * Encapsulates the classified outcome of a webhook delivery attempt.
 *
 * @param outcomeCategory classified outcome category
 * @param isSuccess true if response was 2xx
 * @param isRetryable true if attempt can be safely retried with backoff
 * @param httpStatusCode HTTP status code received, or null if transport/timeout failure
 * @param responseTimeMs measured attempt latency in milliseconds
 * @param errorSummary sanitized diagnostic message (max 500 characters, no sensitive third-party bodies)
 * @param retryAfterSeconds parsed Retry-After recommendation in seconds, or null
 */
public record WebhookResponseClassification(
        WebhookOutcomeCategory outcomeCategory,
        boolean isSuccess,
        boolean isRetryable,
        Integer httpStatusCode,
        Integer responseTimeMs,
        String errorSummary,
        Long retryAfterSeconds
) {
    public static WebhookResponseClassification success(int httpStatusCode, int responseTimeMs) {
        return new WebhookResponseClassification(
                WebhookOutcomeCategory.SUCCESS,
                true,
                false,
                httpStatusCode,
                responseTimeMs,
                null,
                null
        );
    }
}
