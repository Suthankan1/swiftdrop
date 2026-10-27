package com.swiftdrop.communicationsservice.webhook.delivery;

import tools.jackson.databind.json.JsonMapper;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookAttemptPersistenceException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookSecretEncryptionException;
import com.swiftdrop.communicationsservice.webhook.security.WebhookSigningSecretService;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryExecutionServiceTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookAttemptRepository attemptRepository;
    private WebhookSubscriptionRepository subscriptionRepository;
    private WebhookSigningSecretService secretService;
    private WebhookSigner signer;
    private WebhookHttpClient httpClient;
    private WebhookResponseClassifier responseClassifier;
    private WebhookDestinationValidator destinationValidator;
    private WebhookDispatchProperties properties;
    private WebhookDeliveryExecutionService executionService;

    private UUID subscriptionId;
    private UUID deliveryId;
    private UUID eventId;
    private UUID parcelId;
    private UUID merchantId;
    private WebhookSubscription subscription;
    private WebhookDelivery delivery;

    private static final String PLAIN_SECRET = "swd_whsec_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ENCRYPTED_SECRET = "enc:v1:test_iv:test_ciphertext";
    private static final String DESTINATION_URL = "https://merchant.example.com/api/v1/callbacks";

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        attemptRepository = mock(WebhookAttemptRepository.class);
        subscriptionRepository = mock(WebhookSubscriptionRepository.class);
        secretService = mock(WebhookSigningSecretService.class);
        signer = new DefaultWebhookSigner();
        httpClient = mock(WebhookHttpClient.class);
        responseClassifier = new WebhookResponseClassifier();
        destinationValidator = mock(WebhookDestinationValidator.class);

        properties = new WebhookDispatchProperties();
        properties.setMaxAttempts(5);
        properties.setBaseBackoffSeconds(5L);
        properties.setMaxBackoffSeconds(300L);
        properties.setRequestTimeoutMs(5000L);

        executionService = new WebhookDeliveryExecutionService(
                deliveryRepository,
                attemptRepository,
                subscriptionRepository,
                secretService,
                signer,
                httpClient,
                responseClassifier,
                destinationValidator,
                properties,
                JsonMapper.builder().build()
        );

        subscriptionId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        parcelId = UUID.randomUUID();
        merchantId = UUID.randomUUID();

        subscription = new WebhookSubscription(
                subscriptionId,
                merchantId,
                DESTINATION_URL,
                "parcel.status.changed.v1",
                ENCRYPTED_SECRET
        );
        subscription.setActive(true);

        delivery = new WebhookDelivery(
                deliveryId,
                subscriptionId,
                eventId,
                parcelId,
                merchantId,
                1,
                "digest-12345"
        );
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(Instant.now());

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(secretService.decryptSecret(ENCRYPTED_SECRET)).thenReturn(PLAIN_SECRET);
        when(destinationValidator.validate(DESTINATION_URL)).thenReturn(DESTINATION_URL);

        // Stub attemptRepository.save to return the passed entity
        when(attemptRepository.save(any(WebhookAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Successful 200 OK delivery persists attempt, marks delivery SUCCESS, and clears nextAttemptAt")
    void executeDelivery_Success_PersistsAttemptAndSetsSuccess() throws Exception {
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(200, Map.of(), 142));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(result.attemptCount()).isEqualTo(1);

        // Verify attempt persistence
        ArgumentCaptor<WebhookAttempt> attemptCaptor = ArgumentCaptor.forClass(WebhookAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        WebhookAttempt savedAttempt = attemptCaptor.getValue();
        assertThat(savedAttempt.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(savedAttempt.getHttpStatusCode()).isEqualTo(200);
        assertThat(savedAttempt.getResponseTimeMs()).isEqualTo(142);
        assertThat(savedAttempt.getErrorSummary()).isNull();

        // Verify delivery status update
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Transient 500 server error persists attempt, keeps delivery PENDING, and schedules backoff")
    void executeDelivery_ServerError_SchedulesRetry() throws Exception {
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(500, Map.of(), 250));

        Instant before = Instant.now();
        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(result.attemptCount()).isEqualTo(1);

        // Verify attempt persistence
        ArgumentCaptor<WebhookAttempt> attemptCaptor = ArgumentCaptor.forClass(WebhookAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        WebhookAttempt savedAttempt = attemptCaptor.getValue();
        assertThat(savedAttempt.getHttpStatusCode()).isEqualTo(500);
        assertThat(savedAttempt.getErrorSummary()).contains("Server error (500");

        // Verify scheduled retry with base backoff (5s)
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(before.plusSeconds(4));
    }

    @Test
    @DisplayName("Rate-limited 429 response respects Retry-After header for scheduled retry time")
    void executeDelivery_RateLimited_WithRetryAfterHeader_SchedulesExactRetry() throws Exception {
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(429, Map.of("Retry-After", List.of("45")), 180));

        Instant before = Instant.now();
        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);

        // Verify nextAttemptAt is scheduled approx 45 seconds ahead
        assertThat(delivery.getNextAttemptAt()).isBetween(before.plusSeconds(44), before.plusSeconds(46));
    }

    @Test
    @DisplayName("Non-retryable 404 client error transitions delivery immediately to FAILED")
    void executeDelivery_ClientError_FailsImmediately() throws Exception {
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(404, Map.of(), 100));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("301 redirect response transitions delivery immediately to FAILED (SSRF / HTTPS safety)")
    void executeDelivery_Redirect_FailsImmediately() throws Exception {
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(301, Map.of(), 50));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(result.attempt().getErrorSummary()).contains("Redirect response (301");
    }

    @Test
    @DisplayName("HttpTimeoutException records attempt with null status code and schedules retry")
    void executeDelivery_HttpTimeout_SchedulesRetry() throws Exception {
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenThrow(new HttpTimeoutException("request timed out after 5000ms"));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(result.attempt().getHttpStatusCode()).isNull();
        assertThat(result.attempt().getErrorSummary()).contains("Request timeout: HttpTimeoutException");
        assertThat(delivery.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("Fifth failed attempt transitions delivery status to EXHAUSTED")
    void executeDelivery_MaxAttemptsExhausted_TransitionsToExhausted() throws Exception {
        delivery.setAttemptCount(4); // 4 attempts already made; this is attempt 5
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(503, Map.of(), 300));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(result.attemptCount()).isEqualTo(5);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(delivery.getNextAttemptAt()).isNull(); // No further retries
    }

    @Test
    @DisplayName("Delivery already in SUCCESS status is skipped and not re-executed")
    void executeDelivery_AlreadySuccess_SkipsExecution() throws Exception {
        delivery.setStatus(WebhookDeliveryStatus.SUCCESS);
        delivery.setAttemptCount(1);

        WebhookAttempt existingAttempt = new WebhookAttempt(UUID.randomUUID(), deliveryId, Instant.now(), 200, 100, null);
        when(attemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(deliveryId))
                .thenReturn(Optional.of(existingAttempt));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));
        verify(attemptRepository, never()).save(any(WebhookAttempt.class));
    }

    @Test
    @DisplayName("Inactive or missing subscription transitions delivery to FAILED and records attempt")
    void executeDelivery_InactiveSubscription_FailsDelivery() throws Exception {
        subscription.setActive(false);

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(result.attempt().getErrorSummary()).contains("Subscription not found or deactivated");
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));
    }

    @Test
    @DisplayName("Destination URL security rejection transitions delivery to FAILED and records attempt")
    void executeDelivery_SecurityViolation_FailsDelivery() throws Exception {
        when(destinationValidator.validate(DESTINATION_URL))
                .thenThrow(new InvalidWebhookDestinationException("Loopback destination rejected"));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(result.attempt().getErrorSummary()).contains("Security / TLS verification failure");
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));
    }

    @Test
    @DisplayName("Secret decryption failure transitions delivery to FAILED and records attempt")
    void executeDelivery_SecretDecryptionFailure_FailsDelivery() throws Exception {
        when(secretService.decryptSecret(ENCRYPTED_SECRET))
                .thenThrow(new WebhookSecretEncryptionException("Corrupted secret"));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(result.attempt().getErrorSummary()).contains("Failed to decrypt webhook signing secret");
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));
    }

    @Test
    @DisplayName("Subsequent retry attempts preserve previous attempt records (audit trail)")
    void executeDelivery_MultipleAttempts_PreservesAuditHistory() throws Exception {
        // Attempt 1: 500 error
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(500, Map.of(), 200));
        executionService.executeDelivery(delivery);

        // Attempt 2: 200 OK
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(200, Map.of(), 150));
        executionService.executeDelivery(delivery);

        // Verify save was called for two separate attempts
        verify(attemptRepository, times(2)).save(any(WebhookAttempt.class));
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
    }

    @Test
    @DisplayName("Database failure during attempt persistence throws WebhookAttemptPersistenceException")
    void executeDelivery_PersistenceFailure_Throws() throws Exception {
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(200, Map.of(), 100));
        when(attemptRepository.save(any(WebhookAttempt.class)))
                .thenThrow(new RuntimeException("PostgreSQL connection lost"));

        assertThatThrownBy(() -> executionService.executeDelivery(delivery))
                .isInstanceOf(WebhookAttemptPersistenceException.class)
                .hasMessageContaining("Database failure persisting attempt");
    }

    @Test
    @DisplayName("Recover stuck PROCESSING jobs resets them to PENDING")
    void recoverStuckProcessingJobs_ResetsToPending() {
        WebhookDelivery stuck = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, eventId, parcelId, merchantId, 1, "digest"
        );
        stuck.setStatus(WebhookDeliveryStatus.PROCESSING);
        stuck.setUpdatedAt(Instant.now().minus(Duration.ofMinutes(15)));

        when(deliveryRepository.findByStatusAndUpdatedAtLessThanEqual(eq(WebhookDeliveryStatus.PROCESSING), any(Instant.class)))
                .thenReturn(List.of(stuck));

        int recovered = executionService.recoverStuckProcessingJobs(Duration.ofMinutes(10));

        assertThat(recovered).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        verify(deliveryRepository).save(stuck);
    }
}
