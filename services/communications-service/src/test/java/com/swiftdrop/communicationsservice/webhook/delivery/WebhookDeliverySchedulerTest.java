package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.security.WebhookSigningSecretService;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliverySchedulerTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookAttemptRepository attemptRepository;
    private WebhookSubscriptionRepository subscriptionRepository;
    private WebhookSigningSecretService secretService;
    private WebhookSigner signer;
    private WebhookHttpClient httpClient;
    private WebhookResponseClassifier responseClassifier;
    private WebhookDestinationValidator destinationValidator;
    private WebhookDispatchProperties dispatchProperties;
    private WebhookSchedulerProperties schedulerProperties;
    private WebhookDeliveryExecutionService executionService;
    private WebhookDeliveryScheduler scheduler;

    private Instant testNow;
    private Clock fixedClock;

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

        dispatchProperties = new WebhookDispatchProperties();
        dispatchProperties.setMaxAttempts(5);
        dispatchProperties.setBaseBackoffSeconds(5L);
        dispatchProperties.setMaxBackoffSeconds(300L);
        dispatchProperties.setRequestTimeoutMs(5000L);

        schedulerProperties = new WebhookSchedulerProperties();
        schedulerProperties.setEnabled(true);
        schedulerProperties.setPollIntervalMs(5000L);
        schedulerProperties.setRecoveryIntervalMs(60000L);
        schedulerProperties.setLeaseTimeoutSeconds(300L);
        schedulerProperties.setBatchSize(50);

        testNow = Instant.parse("2026-10-27T12:00:00Z");
        fixedClock = Clock.fixed(testNow, ZoneOffset.UTC);

        executionService = new WebhookDeliveryExecutionService(
                deliveryRepository,
                attemptRepository,
                subscriptionRepository,
                secretService,
                signer,
                httpClient,
                responseClassifier,
                destinationValidator,
                dispatchProperties,
                tools.jackson.databind.json.JsonMapper.builder().build(),
                fixedClock,
                () -> 0.5 // deterministic jitter midpoint
        );

        when(attemptRepository.save(any(WebhookAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler = new WebhookDeliveryScheduler(
                deliveryRepository,
                executionService,
                schedulerProperties,
                fixedClock
        );
    }

    @Test
    @DisplayName("1. Polls due deliveries and successfully executes when lease is claimed")
    void schedulePendingDeliveries_ExecutesDueJobs_WhenClaimed() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(testNow.minusSeconds(10));

        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of(delivery));
        when(deliveryRepository.claimDeliveryWithLease(eq(deliveryId), any(), any(), eq(testNow), any()))
                .thenReturn(1); // Lease acquired!

        WebhookSubscription subscription = new WebhookSubscription(
                subscriptionId, delivery.getMerchantId(), "https://merchant.example.com/webhook",
                "parcel.status.changed.v1", "enc:v1:iv:secret"
        );
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(secretService.decryptSecret("enc:v1:iv:secret")).thenReturn("swd_whsec_secret12345678901234567890");

        WebhookHttpResponse mockResponse = new WebhookHttpResponse(200, Map.of(), 45, "{\"ok\":true}");
        when(httpClient.send(any(WebhookHttpRequest.class))).thenReturn(mockResponse);

        int executed = scheduler.schedulePendingDeliveries();

        assertThat(executed).isEqualTo(1);
        verify(deliveryRepository).claimDeliveryWithLease(eq(deliveryId), any(), any(), eq(testNow), any());
        verify(httpClient).send(any(WebhookHttpRequest.class));
        verify(attemptRepository).save(any(WebhookAttempt.class));
    }

    @Test
    @DisplayName("2. Two concurrent workers: Worker 2 skips execution when Worker 1 already claimed lease")
    void schedulePendingDeliveries_SkipsJob_WhenAlreadyClaimedByAnotherWorker() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(testNow.minusSeconds(5));

        // Both workers discover the same due delivery
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of(delivery));

        // Worker 2 attempts atomic claim, but Worker 1 already updated row -> returns 0
        when(deliveryRepository.claimDeliveryWithLease(eq(deliveryId), any(), any(), eq(testNow), any()))
                .thenReturn(0);

        int executed = scheduler.schedulePendingDeliveries();

        // Worker 2 does NOT execute or send HTTP request
        assertThat(executed).isEqualTo(0);
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));
        verify(attemptRepository, never()).save(any(WebhookAttempt.class));
    }

    @Test
    @DisplayName("3. Disabled scheduler returns zero and performs no database polling")
    void schedulePendingDeliveries_DisabledScheduler_ReturnsZero() {
        schedulerProperties.setEnabled(false);

        int executed = scheduler.schedulePendingDeliveries();

        assertThat(executed).isEqualTo(0);
        verify(deliveryRepository, never()).findDueDeliveries(any(), any(), any());
    }

    @Test
    @DisplayName("4. Only due deliveries are selected; jobs with future next_attempt_at are excluded")
    void schedulePendingDeliveries_OnlyDueJobsSelected_FutureJobsExcluded() throws Exception {
        // Repository returns empty list for due jobs because future jobs are not returned
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of());

        int executed = scheduler.schedulePendingDeliveries();

        assertThat(executed).isEqualTo(0);
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));
    }

    @Test
    @DisplayName("5. Worker exceptions during execution do not abort scheduler batch loop")
    void schedulePendingDeliveries_HandlesWorkerExceptionGracefully() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        WebhookDelivery d1 = new WebhookDelivery(id1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "d1");
        WebhookDelivery d2 = new WebhookDelivery(id2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "d2");

        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of(d1, d2));

        // d1 claim succeeds, but subscription lookup throws unexpected RuntimeException
        when(deliveryRepository.claimDeliveryWithLease(eq(id1), any(), any(), eq(testNow), any())).thenReturn(1);
        when(subscriptionRepository.findById(d1.getSubscriptionId())).thenThrow(new RuntimeException("Transient DB issue"));

        // d2 claim succeeds and completes
        when(deliveryRepository.claimDeliveryWithLease(eq(id2), any(), any(), eq(testNow), any())).thenReturn(1);
        WebhookSubscription sub2 = new WebhookSubscription(d2.getSubscriptionId(), d2.getMerchantId(), "https://merchant.example.com/ok", "parcel.status.changed.v1", "enc:v1:iv:sec");
        when(subscriptionRepository.findById(d2.getSubscriptionId())).thenReturn(Optional.of(sub2));
        when(secretService.decryptSecret("enc:v1:iv:sec")).thenReturn("swd_whsec_sec2");
        when(httpClient.send(any())).thenReturn(new WebhookHttpResponse(200, Map.of(), 10, "{}"));

        int executed = scheduler.schedulePendingDeliveries();

        // Second job still executes despite first job encountering an error
        assertThat(executed).isEqualTo(1);
    }

    @Test
    @DisplayName("6. Recovers jobs stuck in PROCESSING status beyond lease timeout")
    void recoverStalledDeliveries_RecoversStuckProcessingJobs() {
        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery stuckDelivery = new WebhookDelivery(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        stuckDelivery.setStatus(WebhookDeliveryStatus.PROCESSING);
        stuckDelivery.setUpdatedAt(testNow.minus(Duration.ofMinutes(10)));

        when(deliveryRepository.findByStatusAndUpdatedAtLessThanEqual(
                eq(WebhookDeliveryStatus.PROCESSING), any(Instant.class)))
                .thenReturn(List.of(stuckDelivery));

        int recovered = scheduler.recoverStalledDeliveries();

        assertThat(recovered).isEqualTo(1);
        assertThat(stuckDelivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(stuckDelivery.getNextAttemptAt()).isEqualTo(testNow);
        verify(deliveryRepository).save(stuckDelivery);
    }

    @Test
    @DisplayName("7. Disabled scheduler does not recover stalled jobs")
    void recoverStalledDeliveries_DisabledScheduler_ReturnsZero() {
        schedulerProperties.setEnabled(false);

        int recovered = scheduler.recoverStalledDeliveries();

        assertThat(recovered).isEqualTo(0);
        verify(deliveryRepository, never()).findByStatusAndUpdatedAtLessThanEqual(any(), any());
    }

    @Test
    @DisplayName("8. Exponential backoff delays progress deterministically (5s, 10s, 20s, 40s, 80s) capped at 300s")
    void exponentialBackoff_SequenceAndLimits_AreDeterministic() {
        // Base = 5s, Max = 300s, Jitter = false
        dispatchProperties.setJitterEnabled(false);

        assertThat(executionService.calculateBackoff(1, null)).isEqualTo(5L);   // 5 * 2^0 = 5
        assertThat(executionService.calculateBackoff(2, null)).isEqualTo(10L);  // 5 * 2^1 = 10
        assertThat(executionService.calculateBackoff(3, null)).isEqualTo(20L);  // 5 * 2^2 = 20
        assertThat(executionService.calculateBackoff(4, null)).isEqualTo(40L);  // 5 * 2^3 = 40
        assertThat(executionService.calculateBackoff(5, null)).isEqualTo(80L);  // 5 * 2^4 = 80
        assertThat(executionService.calculateBackoff(6, null)).isEqualTo(160L); // 5 * 2^5 = 160
        assertThat(executionService.calculateBackoff(7, null)).isEqualTo(300L); // 5 * 2^6 = 320 -> capped at 300
        assertThat(executionService.calculateBackoff(10, null)).isEqualTo(300L); // capped at max
    }

    @Test
    @DisplayName("9. Exponential backoff with jitter stays strictly within bounds and ceiling")
    void exponentialBackoff_WithJitter_StaysWithinConfiguredBounds() {
        dispatchProperties.setJitterEnabled(true);
        dispatchProperties.setJitterFactor(0.2); // +/- 20%

        // Attempt 2 base delay = 10s. +/- 20% range is [8, 12]
        // With randomSupplier = 0.5 (midpoint), delay should be exactly 10s
        long delayMidpoint = executionService.calculateBackoff(2, null);
        assertThat(delayMidpoint).isBetween(8L, 12L);

        // Attempt 7 delay without cap = 320s. With max ceiling = 300s, delay must never exceed 300s
        long delayCapped = executionService.calculateBackoff(7, null);
        assertThat(delayCapped).isLessThanOrEqualTo(300L);
    }

    @Test
    @DisplayName("10. Disabled subscription transitions immediately to FAILED with no further retries")
    void disabledSubscription_TransitionsToFailedWithoutInfiniteRetry() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(1);

        // Subscription is inactive (soft-deleted)
        WebhookSubscription inactiveSub = new WebhookSubscription(
                subscriptionId, delivery.getMerchantId(), "https://merchant.example.com/webhook",
                "parcel.status.changed.v1", "enc:v1:iv:secret"
        );
        inactiveSub.setActive(false);

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(inactiveSub));

        WebhookAttemptResult result = executionService.executeDelivery(delivery);

        // Status is terminal FAILED, nextAttemptAt is null
        assertThat(result.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getNextAttemptAt()).isNull();
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));
    }

    @Test
    @DisplayName("11. Terminal jobs (EXHAUSTED, SUCCESS, FAILED) are guarded against re-execution")
    void terminalStatusIntegrity_ExhaustedAndCompletedJobsNeverRescheduled() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery successJob = new WebhookDelivery(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        successJob.setStatus(WebhookDeliveryStatus.SUCCESS);
        WebhookAttemptResult successResult = executionService.executeDelivery(successJob);
        assertThat(successResult.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);

        WebhookDelivery exhaustedJob = new WebhookDelivery(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        exhaustedJob.setStatus(WebhookDeliveryStatus.EXHAUSTED);
        WebhookAttemptResult exhaustedResult = executionService.executeDelivery(exhaustedJob);
        assertThat(exhaustedResult.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);

        WebhookDelivery failedJob = new WebhookDelivery(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        failedJob.setStatus(WebhookDeliveryStatus.FAILED);
        WebhookAttemptResult failedResult = executionService.executeDelivery(failedJob);
        assertThat(failedResult.deliveryStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);

        // Verify zero HTTP requests dispatched for terminal jobs
        verify(httpClient, never()).send(any());
    }

    @Test
    @DisplayName("12. Retry budget exhaustion: 5th failed attempt transitions delivery to EXHAUSTED")
    void retryBudgetExhaustion_FifthAttemptTransitionsToExhausted() {
        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(4); // 4 attempts already made; this is attempt 5

        WebhookResponseClassification serverError = new WebhookResponseClassification(
                WebhookOutcomeCategory.SERVER_ERROR, false, true, 503, 100, "Service Unavailable", null
        );

        executionService.persistAttemptAndUpdateDelivery(delivery, serverError);

        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(delivery.getNextAttemptAt()).isNull();
        verify(deliveryRepository).save(delivery);
    }

    @Test
    @DisplayName("13. Simulated restart: Pending delivery with due time is picked up by new scheduler instance")
    void simulatedRestart_PendingJobsResumedAtScheduledTime() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        WebhookDelivery pendingDelivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "digest"
        );
        pendingDelivery.setStatus(WebhookDeliveryStatus.PENDING);
        // Next attempt was scheduled 30 seconds ago before process crash
        pendingDelivery.setNextAttemptAt(testNow.minusSeconds(30));

        // Create a new scheduler instance simulating post-restart application context
        WebhookDeliveryScheduler restartedScheduler = new WebhookDeliveryScheduler(
                deliveryRepository,
                executionService,
                schedulerProperties,
                fixedClock
        );

        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testNow), any(Pageable.class)))
                .thenReturn(List.of(pendingDelivery));
        when(deliveryRepository.claimDeliveryWithLease(eq(deliveryId), any(), any(), eq(testNow), any()))
                .thenReturn(1);

        WebhookSubscription subscription = new WebhookSubscription(
                subscriptionId, pendingDelivery.getMerchantId(), "https://merchant.example.com/webhook",
                "parcel.status.changed.v1", "enc:v1:iv:secret"
        );
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(secretService.decryptSecret("enc:v1:iv:secret")).thenReturn("swd_whsec_secret");
        when(httpClient.send(any())).thenReturn(new WebhookHttpResponse(200, Map.of(), 20, "{}"));

        int executed = restartedScheduler.schedulePendingDeliveries();

        assertThat(executed).isEqualTo(1);
        verify(httpClient).send(any());
    }

    @Test
    @DisplayName("14. Rate limit Retry-After header overrides standard exponential delay")
    void rateLimitRetryAfter_OverridesExponentialBackoff() {
        // Attempt 1 standard delay would be 5s.
        // Server specifies Retry-After: 60s
        long delay = executionService.calculateBackoff(1, 60L);
        assertThat(delay).isEqualTo(60L);

        // Server specifies Retry-After exceeding maxBackoffSeconds (e.g. 500s) -> capped at 300s
        long delayCapped = executionService.calculateBackoff(1, 500L);
        assertThat(delayCapped).isEqualTo(300L);
    }
}
