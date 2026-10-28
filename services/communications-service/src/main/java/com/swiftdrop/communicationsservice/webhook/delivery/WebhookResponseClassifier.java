package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Classifies HTTP response status codes and transport exceptions into structured WebhookResponseClassification.
 * Enforces:
 * 1. 2xx -> SUCCESS (terminal success, no retry).
 * 2. 3xx -> REDIRECT (terminal failure; redirect following disabled to protect against SSRF and HTTPS downgrade).
 * 3. 4xx (except 408, 429) -> CLIENT_ERROR (fail-fast, non-retryable endpoint misconfiguration).
 * 4. 408 / 429 -> RATE_LIMITED / TIMEOUT (retryable with backoff, parses Retry-After header).
 * 5. 5xx -> SERVER_ERROR (retryable transient server failure).
 * 6. Transport / Timeout exceptions -> TIMEOUT / NETWORK_FAILURE (retryable, null status code).
 * 7. TLS / Security exceptions -> SECURITY_FAILURE (non-retryable, null status code).
 * 8. Strict sanitization: error_summary is capped at 500 characters, control characters are stripped,
 *    and secrets or full response bodies are strictly omitted.
 */
@Component
public class WebhookResponseClassifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookResponseClassifier.class);

    public static final int MAX_ERROR_SUMMARY_LENGTH = 500;
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(swd_whsec_[0-9a-f]{64}|Bearer\\s+[A-Za-z0-9._~+/-]+=*)");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\r\n\t]");

    /**
     * Classifies an HTTP response received from a webhook endpoint.
     */
    public WebhookResponseClassification classify(WebhookHttpResponse response) {
        if (response == null) {
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.UNEXPECTED_FAILURE,
                    false,
                    false,
                    null,
                    0,
                    "Null HTTP response received",
                    null
            );
        }

        int code = response.statusCode();
        int latency = response.responseTimeMs();
        String reasonPhrase = resolveReasonPhrase(code);

        // 1. Successful HTTP responses (2xx)
        if (code >= 200 && code < 300) {
            return WebhookResponseClassification.success(code, latency);
        }

        // 2. Redirect responses (3xx) - Non-retryable
        if (code >= 300 && code < 400) {
            String summary = sanitize("Redirect response (" + code + " " + reasonPhrase + "); " +
                    "automatic redirects are disabled to enforce HTTPS and prevent SSRF.");
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.REDIRECT,
                    false,
                    false,
                    code,
                    latency,
                    summary,
                    null
            );
        }

        // 3. Rate limiting (429 Too Many Requests) - Retryable
        if (code == 429) {
            Long retryAfter = parseRetryAfter(response.getFirstHeader("Retry-After"));
            String summary = sanitize("Rate limited (429 Too Many Requests)" +
                    (retryAfter != null ? "; Retry-After: " + retryAfter + "s" : ""));
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.RATE_LIMITED,
                    false,
                    true,
                    429,
                    latency,
                    summary,
                    retryAfter
            );
        }

        // 4. Server-reported request timeout (408 Request Timeout) - Retryable
        if (code == 408) {
            String summary = sanitize("Remote server timeout (408 Request Timeout); retryable failure.");
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.TIMEOUT,
                    false,
                    true,
                    408,
                    latency,
                    summary,
                    null
            );
        }

        // 5. Client errors (4xx) - Non-retryable (fail fast per review doc 6.4)
        if (code >= 400 && code < 500) {
            String summary = sanitize("Client error (" + code + " " + reasonPhrase + "); " +
                    "non-retryable endpoint failure.");
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.CLIENT_ERROR,
                    false,
                    false,
                    code,
                    latency,
                    summary,
                    null
            );
        }

        // 6. Server errors (5xx) - Retryable
        if (code >= 500 && code < 600) {
            Long retryAfter = (code == 503) ? parseRetryAfter(response.getFirstHeader("Retry-After")) : null;
            String summary = sanitize("Server error (" + code + " " + reasonPhrase + "); " +
                    "transient retryable failure." +
                    (retryAfter != null ? " Retry-After: " + retryAfter + "s" : ""));
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.SERVER_ERROR,
                    false,
                    true,
                    code,
                    latency,
                    summary,
                    retryAfter
            );
        }

        // 7. Unexpected / Unrecognized status code
        String summary = sanitize("Unexpected HTTP status code: " + code);
        return new WebhookResponseClassification(
                WebhookOutcomeCategory.UNEXPECTED_FAILURE,
                false,
                false,
                code,
                latency,
                summary,
                null
        );
    }

    /**
     * Classifies a transport or runtime exception encountered during delivery execution.
     */
    public WebhookResponseClassification classifyException(Throwable throwable, int responseTimeMs) {
        if (throwable == null) {
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.UNEXPECTED_FAILURE,
                    false,
                    false,
                    null,
                    responseTimeMs,
                    "Unknown error (null throwable)",
                    null
            );
        }

        Throwable cause = rootCause(throwable);

        // 1. Timeouts (Connect timeout, request timeout, socket read timeout) - Retryable
        if (cause instanceof HttpTimeoutException ||
                cause instanceof HttpConnectTimeoutException ||
                cause instanceof SocketTimeoutException ||
                cause instanceof TimeoutException) {
            String summary = sanitize("Request timeout: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.TIMEOUT,
                    false,
                    true,
                    null,
                    responseTimeMs,
                    summary,
                    null
            );
        }

        // 2. TLS & Security exceptions - Non-retryable
        if (cause instanceof SSLException ||
                cause instanceof InvalidWebhookDestinationException ||
                cause instanceof SecurityException) {
            String summary = sanitize("Security / TLS verification failure: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.SECURITY_FAILURE,
                    false,
                    false,
                    null,
                    responseTimeMs,
                    summary,
                    null
            );
        }

        // 3. DNS Host resolution failures - Retryable
        if (cause instanceof UnknownHostException) {
            String summary = sanitize("DNS resolution failure: UnknownHostException - " + cause.getMessage());
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.NETWORK_FAILURE,
                    false,
                    true,
                    null,
                    responseTimeMs,
                    summary,
                    null
            );
        }

        // 4. Connection failures (connection refused, network unreachable, broken pipe) - Retryable
        if (cause instanceof ConnectException ||
                cause instanceof NoRouteToHostException ||
                cause instanceof SocketException) {
            String summary = sanitize("Network connection failure: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.NETWORK_FAILURE,
                    false,
                    true,
                    null,
                    responseTimeMs,
                    summary,
                    null
            );
        }

        // 5. General I/O transport failure - Retryable
        if (cause instanceof IOException) {
            String summary = sanitize("I/O transport failure: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
            return new WebhookResponseClassification(
                    WebhookOutcomeCategory.NETWORK_FAILURE,
                    false,
                    true,
                    null,
                    responseTimeMs,
                    summary,
                    null
            );
        }

        // 6. Any other unexpected failure - Non-retryable
        String summary = sanitize("Unexpected delivery error: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
        return new WebhookResponseClassification(
                WebhookOutcomeCategory.UNEXPECTED_FAILURE,
                false,
                false,
                null,
                responseTimeMs,
                summary,
                null
        );
    }

    /**
     * Sanitizes error message: masks secrets, strips control chars, and caps length at 500 chars.
     */
    public String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = CONTROL_CHARS.matcher(raw).replaceAll(" ").trim();
        clean = SECRET_PATTERN.matcher(clean).replaceAll("[REDACTED_SECRET]");
        if (clean.length() > MAX_ERROR_SUMMARY_LENGTH) {
            clean = clean.substring(0, MAX_ERROR_SUMMARY_LENGTH - 3) + "...";
        }
        return clean;
    }

    /**
     * Parses the Retry-After header as either integer seconds or an RFC 1123 / 2822 HTTP date.
     */
    public Long parseRetryAfter(String retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isBlank()) {
            return null;
        }
        String trimmed = retryAfterHeader.trim();

        // Check if integer seconds
        if (trimmed.matches("^\\d+$")) {
            try {
                long seconds = Long.parseLong(trimmed);
                return Math.max(0L, seconds);
            } catch (NumberFormatException ignored) {
            }
        }

        // Check if HTTP-date (e.g., Wed, 21 Oct 2026 07:28:00 GMT)
        try {
            Instant targetTime = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed));
            long diff = targetTime.getEpochSecond() - Instant.now().getEpochSecond();
            return Math.max(0L, diff);
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    private String resolveReasonPhrase(int statusCode) {
        try {
            return HttpStatus.valueOf(statusCode).getReasonPhrase();
        } catch (IllegalArgumentException ex) {
            return "Status " + statusCode;
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
