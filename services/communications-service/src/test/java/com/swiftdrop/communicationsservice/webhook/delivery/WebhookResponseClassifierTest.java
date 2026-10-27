package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookResponseClassifierTest {

    private WebhookResponseClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new WebhookResponseClassifier();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    @DisplayName("2xx status codes are classified as SUCCESS, non-retryable with zero error summary")
    void classify_2xx_Success(int statusCode) {
        WebhookHttpResponse response = new WebhookHttpResponse(statusCode, Map.of(), 125);
        WebhookResponseClassification result = classifier.classify(response);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.httpStatusCode()).isEqualTo(statusCode);
        assertThat(result.responseTimeMs()).isEqualTo(125);
        assertThat(result.errorSummary()).isNull();
        assertThat(result.retryAfterSeconds()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308})
    @DisplayName("3xx redirect responses are classified as REDIRECT, non-retryable to prevent SSRF and HTTPS downgrade")
    void classify_3xx_Redirect(int statusCode) {
        WebhookHttpResponse response = new WebhookHttpResponse(statusCode, Map.of(), 80);
        WebhookResponseClassification result = classifier.classify(response);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.REDIRECT);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.httpStatusCode()).isEqualTo(statusCode);
        assertThat(result.errorSummary()).contains("Redirect response (" + statusCode);
        assertThat(result.errorSummary()).contains("disabled to enforce HTTPS and prevent SSRF");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 410, 422})
    @DisplayName("4xx client errors (excluding 408, 429) are classified as CLIENT_ERROR, non-retryable fail-fast")
    void classify_4xx_ClientError(int statusCode) {
        WebhookHttpResponse response = new WebhookHttpResponse(statusCode, Map.of(), 150);
        WebhookResponseClassification result = classifier.classify(response);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.CLIENT_ERROR);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.httpStatusCode()).isEqualTo(statusCode);
        assertThat(result.errorSummary()).contains("Client error (" + statusCode);
        assertThat(result.errorSummary()).contains("non-retryable endpoint failure");
    }

    @Test
    @DisplayName("429 Too Many Requests is classified as RATE_LIMITED, retryable, parsing Retry-After in seconds")
    void classify_429_RateLimited_WithSeconds() {
        WebhookHttpResponse response = new WebhookHttpResponse(
                429,
                Map.of("Retry-After", List.of("60")),
                200
        );
        WebhookResponseClassification result = classifier.classify(response);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.RATE_LIMITED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.httpStatusCode()).isEqualTo(429);
        assertThat(result.retryAfterSeconds()).isEqualTo(60L);
        assertThat(result.errorSummary()).contains("Rate limited (429 Too Many Requests)");
        assertThat(result.errorSummary()).contains("Retry-After: 60s");
    }

    @Test
    @DisplayName("429 Too Many Requests parses RFC 1123 HTTP-date header into seconds")
    void classify_429_RateLimited_WithHttpDate() {
        String futureDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.now().plusSeconds(120).atZone(java.time.ZoneOffset.UTC)
        );
        WebhookHttpResponse response = new WebhookHttpResponse(
                429,
                Map.of("Retry-After", List.of(futureDate)),
                180
        );
        WebhookResponseClassification result = classifier.classify(response);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.RATE_LIMITED);
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.retryAfterSeconds()).isBetween(115L, 125L);
    }

    @Test
    @DisplayName("408 Request Timeout is classified as TIMEOUT, retryable")
    void classify_408_ServerTimeout() {
        WebhookHttpResponse response = new WebhookHttpResponse(408, Map.of(), 5000);
        WebhookResponseClassification result = classifier.classify(response);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.TIMEOUT);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.httpStatusCode()).isEqualTo(408);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("5xx server errors are classified as SERVER_ERROR, retryable")
    void classify_5xx_ServerError(int statusCode) {
        WebhookHttpResponse response = new WebhookHttpResponse(statusCode, Map.of(), 300);
        WebhookResponseClassification result = classifier.classify(response);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.SERVER_ERROR);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.httpStatusCode()).isEqualTo(statusCode);
        assertThat(result.errorSummary()).contains("Server error (" + statusCode);
    }

    @Test
    @DisplayName("HttpTimeoutException is classified as TIMEOUT with null status code and is retryable")
    void classifyException_HttpTimeout() {
        HttpTimeoutException ex = new HttpTimeoutException("request timed out after 5000ms");
        WebhookResponseClassification result = classifier.classifyException(ex, 5002);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.TIMEOUT);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.responseTimeMs()).isEqualTo(5002);
        assertThat(result.errorSummary()).contains("Request timeout: HttpTimeoutException");
    }

    @Test
    @DisplayName("HttpConnectTimeoutException and SocketTimeoutException are classified as TIMEOUT")
    void classifyException_SocketTimeout() {
        SocketTimeoutException ex = new SocketTimeoutException("Read timed out");
        WebhookResponseClassification result = classifier.classifyException(ex, 3001);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.TIMEOUT);
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.httpStatusCode()).isNull();
    }

    @Test
    @DisplayName("ConnectException and NoRouteToHostException are classified as NETWORK_FAILURE, retryable")
    void classifyException_ConnectException() {
        ConnectException ex = new ConnectException("Connection refused");
        WebhookResponseClassification result = classifier.classifyException(ex, 45);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.NETWORK_FAILURE);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.errorSummary()).contains("Network connection failure: ConnectException");
    }

    @Test
    @DisplayName("SSLHandshakeException is classified as SECURITY_FAILURE, non-retryable")
    void classifyException_SSLHandshakeException() {
        SSLHandshakeException ex = new SSLHandshakeException("PKIX path building failed: unable to find valid certification path");
        WebhookResponseClassification result = classifier.classifyException(ex, 250);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.SECURITY_FAILURE);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.errorSummary()).contains("Security / TLS verification failure");
    }

    @Test
    @DisplayName("InvalidWebhookDestinationException is classified as SECURITY_FAILURE, non-retryable")
    void classifyException_InvalidWebhookDestinationException() {
        InvalidWebhookDestinationException ex = new InvalidWebhookDestinationException("Loopback 127.0.0.1 is prohibited");
        WebhookResponseClassification result = classifier.classifyException(ex, 2);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.SECURITY_FAILURE);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.errorSummary()).contains("Security / TLS verification failure");
    }

    @Test
    @DisplayName("UnknownHostException is classified as NETWORK_FAILURE, retryable")
    void classifyException_UnknownHostException() {
        UnknownHostException ex = new UnknownHostException("webhook.invalid.domain");
        WebhookResponseClassification result = classifier.classifyException(ex, 50);

        assertThat(result.outcomeCategory()).isEqualTo(WebhookOutcomeCategory.NETWORK_FAILURE);
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.httpStatusCode()).isNull();
    }

    @Test
    @DisplayName("Error summary masks potential signing secrets or bearer tokens")
    void sanitize_MasksSecrets() {
        String secret = "swd_whsec_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String raw = "Failed request with secret " + secret + " and Bearer eyJhbGciOiJIUzI1NiJ9.test";
        String sanitized = classifier.sanitize(raw);

        assertThat(sanitized).doesNotContain(secret);
        assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1NiJ9.test");
        assertThat(sanitized).contains("[REDACTED_SECRET]");
    }

    @Test
    @DisplayName("Error summary truncates strings longer than 500 characters")
    void sanitize_TruncatesLongString() {
        String veryLong = "A".repeat(600);
        String sanitized = classifier.sanitize(veryLong);

        assertThat(sanitized).hasSize(500);
        assertThat(sanitized).endsWith("...");
    }
}
