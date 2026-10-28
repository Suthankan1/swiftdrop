package com.swiftdrop.communicationsservice.webhook.delivery;

import tools.jackson.databind.json.JsonMapper;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedPayload;
import com.swiftdrop.communicationsservice.consumer.WebhookJobMaterializationService;
import com.swiftdrop.communicationsservice.consumer.WebhookMaterializationResult;
import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.transaction.DurableJobPersistenceService;
import com.swiftdrop.communicationsservice.transaction.InboxAndWebhookDeliveriesResult;
import com.swiftdrop.communicationsservice.client.ApprovedMerchantTenant;
import com.swiftdrop.communicationsservice.client.AccountsMerchantEligibilityClient;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit;
import com.swiftdrop.communicationsservice.webhook.WebhookReplayAuditRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.exception.CrossTenantAccessException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookReplayException;
import com.swiftdrop.communicationsservice.webhook.security.WebhookSigningSecretService;
import com.swiftdrop.communicationsservice.webhook.service.WebhookSubscriptionService;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookEventFilterValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test validating the complete SwiftDrop webhook delivery and retry pipeline (Refs #33).
 *
 * Coordinates decomposed Communications slices:
 * - Issue #116: Tenant-bound webhook subscription configuration and secret management.
 * - Issue #131: Durable webhook jobs materialized in independent idempotent consumer group.
 * - Issue #142: Signed HTTPS delivery attempts and response classification.
 * - Issue #151: Durable polling scheduler, atomic leasing, exponential retry, and exhaustion.
 * - Issue #155: Egress hardening, DNS pinning, secret rotation, and authorized replay.
 */
class WebhookDeliveryPipelineIntegrationTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookAttemptRepository attemptRepository;
    private WebhookSubscriptionRepository subscriptionRepository;
    private ConsumedEventRepository consumedEventRepository;
    private WebhookReplayAuditRepository replayAuditRepository;
    private DurableJobPersistenceService durableJobPersistenceService;
    private AccountsMerchantEligibilityClient accountsClient;

    private WebhookSigningSecretService secretService;
    private WebhookSigner signer;
    private WebhookHttpClient httpClient;
    private WebhookResponseClassifier responseClassifier;
    private WebhookDestinationValidator destinationValidator;
    private WebhookEventFilterValidator eventFilterValidator;

    private WebhookDispatchProperties dispatchProperties;
    private WebhookSchedulerProperties schedulerProperties;
    private JsonMapper jsonMapper;

    private WebhookJobMaterializationService materializationService;
    private WebhookDeliveryExecutionService executionService;
    private WebhookDeliveryScheduler scheduler;
    private WebhookSubscriptionService subscriptionService;

    private MutableTestClock testClock;
    private UUID merchantId;
    private UUID subscriptionId;
    private WebhookSubscription subscription;

    private static final String PLAIN_SECRET = "swd_whsec_pipeline_test_secret_0123456789abcdef0123456789abcdef";
    private static final String ENCRYPTED_SECRET = "enc:v1:pipeline_iv:pipeline_ciphertext";
    private static final String DESTINATION_URL = "https://merchant.example.com/api/v1/callbacks";
    private static final String CALLER_SUB = "auth0|merchant_owner_user_123";

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        attemptRepository = mock(WebhookAttemptRepository.class);
        subscriptionRepository = mock(WebhookSubscriptionRepository.class);
        consumedEventRepository = mock(ConsumedEventRepository.class);
        replayAuditRepository = mock(WebhookReplayAuditRepository.class);
        durableJobPersistenceService = mock(DurableJobPersistenceService.class);
        accountsClient = mock(AccountsMerchantEligibilityClient.class);

        secretService = mock(WebhookSigningSecretService.class);
        signer = new DefaultWebhookSigner();
        httpClient = mock(WebhookHttpClient.class);
        responseClassifier = new WebhookResponseClassifier();
        destinationValidator = mock(WebhookDestinationValidator.class);
        eventFilterValidator = new WebhookEventFilterValidator();
        jsonMapper = JsonMapper.builder().build();

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

        testClock = new MutableTestClock(Instant.parse("2026-10-27T10:00:00Z"));

        materializationService = new WebhookJobMaterializationService(
                durableJobPersistenceService,
                subscriptionRepository,
                deliveryRepository,
                eventFilterValidator,
                "communications.webhooks.v1"
        );

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
                jsonMapper,
                testClock,
                () -> 0.5 // deterministic jitter midpoint
        );

        scheduler = new WebhookDeliveryScheduler(
                deliveryRepository,
                executionService,
                schedulerProperties,
                testClock
        );

        subscriptionService = new WebhookSubscriptionService(
                subscriptionRepository,
                deliveryRepository,
                attemptRepository,
                destinationValidator,
                eventFilterValidator,
                secretService,
                accountsClient,
                replayAuditRepository
        );

        merchantId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();

        subscription = new WebhookSubscription(
                subscriptionId,
                merchantId,
                DESTINATION_URL,
                "parcel.status.changed.v1",
                ENCRYPTED_SECRET
        );
        subscription.setActive(true);

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.findByMerchantIdAndActiveTrue(merchantId)).thenReturn(List.of(subscription));
        when(secretService.decryptSecret(ENCRYPTED_SECRET)).thenReturn(PLAIN_SECRET);
        when(destinationValidator.validate(DESTINATION_URL)).thenReturn(DESTINATION_URL);
        when(accountsClient.resolveApprovedMerchant(CALLER_SUB))
                .thenReturn(new ApprovedMerchantTenant(merchantId, CALLER_SUB, "Test Merchant Ltd", "APPROVED", true, true));

        when(attemptRepository.save(any(WebhookAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(inv -> inv.getArgument(0));
        when(replayAuditRepository.save(any(WebhookReplayAudit.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Stage 1 & 2: Complete Happy-Path Pipeline — Event Ingestion -> Claim -> Signed HTTPS -> Success")
    void testCompleteHappyPathPipeline() throws Exception {
        UUID parcelId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        ParcelStatusChangedEvent event = new ParcelStatusChangedEvent(
                eventId,
                "parcel.status.changed.v1",
                "1.0.0",
                parcelId,
                "PARCEL",
                1L,
                testClock.instant(),
                "corr-test-1",
                "delivery-operations",
                new ParcelStatusChangedPayload(
                        parcelId,
                        "SWD-20261027-A1B2",
                        null,
                        "CREATED",
                        null,
                        merchantId,
                        null,
                        "Colombo"
                )
        );

        when(durableJobPersistenceService.isEventConsumed("communications.webhooks.v1", eventId)).thenReturn(false);
        when(durableJobPersistenceService.persistInboxAndWebhookDeliveries(any(), any())).thenAnswer(inv -> {
            ConsumedEvent ce = inv.getArgument(0);
            List<WebhookDelivery> dels = inv.getArgument(1);
            return new InboxAndWebhookDeliveriesResult(ce, dels, true);
        });

        // 1. Ingest event and materialize job
        WebhookMaterializationResult matResult = materializationService.processLifecycleEvent(event);
        assertThat(matResult.newlyPersisted()).isTrue();
        assertThat(matResult.jobsCreatedCount()).isEqualTo(1);

        WebhookDelivery createdJob = matResult.jobs().get(0);
        assertThat(createdJob.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(createdJob.getAttemptCount()).isEqualTo(0);
        assertThat(createdJob.getNextAttemptAt()).isNotNull();

        // 2. Scheduler discovers due job and acquires atomic database lease
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of(createdJob));
        when(deliveryRepository.claimDeliveryWithLease(eq(createdJob.getDeliveryId()), any(), any(), any(), any()))
                .thenReturn(1); // Worker 1 wins lease

        // 3. Mock receiver verifies signature contract and returns 200 OK
        AtomicBoolean receiverVerifiedSignature = new AtomicBoolean(false);
        when(httpClient.send(any(WebhookHttpRequest.class))).thenAnswer(inv -> {
            WebhookHttpRequest req = inv.getArgument(0);
            assertThat(req.url()).isEqualTo(DESTINATION_URL);
            assertThat(req.signatureHeader()).startsWith("t=");
            assertThat(req.signatureHeader()).contains(",v1=");

            // Verify receiver signature verification contract
            boolean validSignature = signer.verifySignature(req.payloadJson(), req.signatureHeader(), PLAIN_SECRET);
            assertThat(validSignature).isTrue();
            receiverVerifiedSignature.set(true);

            return new WebhookHttpResponse(200, Map.of(), 125);
        });

        // 4. Scheduler execution loop runs
        int executed = scheduler.schedulePendingDeliveries();
        assertThat(executed).isEqualTo(1);
        assertThat(receiverVerifiedSignature.get()).isTrue();

        // 5. Verify delivery reached terminal SUCCESS state
        assertThat(createdJob.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(createdJob.getAttemptCount()).isEqualTo(1);
        assertThat(createdJob.getNextAttemptAt()).isNull();

        // 6. Verify attempt audit history recorded accurately
        ArgumentCaptor<WebhookAttempt> attemptCaptor = ArgumentCaptor.forClass(WebhookAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        WebhookAttempt attempt = attemptCaptor.getValue();
        assertThat(attempt.getDeliveryId()).isEqualTo(createdJob.getDeliveryId());
        assertThat(attempt.getHttpStatusCode()).isEqualTo(200);
        assertThat(attempt.getResponseTimeMs()).isEqualTo(125);
        assertThat(attempt.getErrorSummary()).isNull();
    }

    @Test
    @DisplayName("Stage 3: Multi-Worker Concurrency Defense — Second worker safely skips leased job")
    void testMultiWorkerConcurrencyDefense() {
        WebhookDelivery job = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest"
        );
        job.setStatus(WebhookDeliveryStatus.PENDING);
        job.setNextAttemptAt(testClock.instant());

        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of(job));

        // Worker 2 attempts to claim lease, but Worker 1 already updated it (claim returns 0)
        when(deliveryRepository.claimDeliveryWithLease(eq(job.getDeliveryId()), any(), any(), any(), any()))
                .thenReturn(0);

        int executed = scheduler.schedulePendingDeliveries();

        assertThat(executed).isEqualTo(0);
        verifyNoInteractions(httpClient);
        verify(attemptRepository, never()).save(any(WebhookAttempt.class));
    }

    @Test
    @DisplayName("Stage 4 & 5: Exponential Retry, Rate-Limiting, and Terminal Exhaustion Lifecycle")
    void testRetryLifecycleWithExponentialBackoffAndExhaustion() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest"
        );
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(testClock.instant());

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.claimDeliveryWithLease(eq(deliveryId), any(), any(), any(), any())).thenReturn(1);

        // Attempt 1: Transport timeout (e.g. DNS / connect timeout)
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenThrow(new HttpTimeoutException("Connection timed out after 5000ms"));

        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of(delivery));

        scheduler.schedulePendingDeliveries();

        // Classified as retryable TIMEOUT, scheduled in 5s (baseBackoff)
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        Instant nextAttempt1 = delivery.getNextAttemptAt();
        assertThat(nextAttempt1).isEqualTo(testClock.instant().plusSeconds(5));

        // Polling before nextAttemptAt does not dispatch
        testClock.advance(Duration.ofSeconds(2));
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of()); // Not due yet
        int pollEarly = scheduler.schedulePendingDeliveries();
        assertThat(pollEarly).isEqualTo(0);

        // Advance to nextAttempt1: Attempt 2 returns 429 with Retry-After: 45
        testClock.set(nextAttempt1);
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of(delivery));
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(429, Map.of("Retry-After", List.of("45")), 100));

        scheduler.schedulePendingDeliveries();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        Instant nextAttempt2 = delivery.getNextAttemptAt();
        assertThat(nextAttempt2).isEqualTo(testClock.instant().plusSeconds(45));

        // Advance to nextAttempt2: Attempt 3 returns 500 Server Error
        testClock.set(nextAttempt2);
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of(delivery));
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(500, Map.of(), 200));

        scheduler.schedulePendingDeliveries();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        Instant nextAttempt3 = delivery.getNextAttemptAt();
        assertThat(nextAttempt3).isEqualTo(testClock.instant().plusSeconds(20)); // 5 * 2^2 = 20s

        // Advance to nextAttempt3: Attempt 4 returns 503 Service Unavailable
        testClock.set(nextAttempt3);
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of(delivery));
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(503, Map.of(), 250));

        scheduler.schedulePendingDeliveries();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(4);
        Instant nextAttempt4 = delivery.getNextAttemptAt();
        assertThat(nextAttempt4).isEqualTo(testClock.instant().plusSeconds(40)); // 5 * 2^3 = 40s

        // Advance to nextAttempt4: Attempt 5 returns 502 Bad Gateway -> hits maxAttempts (5)
        testClock.set(nextAttempt4);
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), eq(testClock.instant()), any(Pageable.class)))
                .thenReturn(List.of(delivery));
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(502, Map.of(), 300));

        scheduler.schedulePendingDeliveries();

        // Terminal EXHAUSTED state reached!
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        assertThat(delivery.getNextAttemptAt()).isNull();

        // Invariant: Total of 5 attempts persisted in history
        verify(attemptRepository, times(5)).save(any(WebhookAttempt.class));

        // Invariant: Even if clock advances 1 day, exhausted job is never rescheduled
        testClock.advance(Duration.ofDays(1));
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), any(), any()))
                .thenReturn(List.of());
        int executedAfterDays = scheduler.schedulePendingDeliveries();
        assertThat(executedAfterDays).isEqualTo(0);
    }

    @Test
    @DisplayName("Stage 6: Authorized Replay — Revives EXHAUSTED delivery to PENDING and succeeds on redelivery")
    void testAuthorizedReplayAndRedelivery() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest"
        );
        delivery.setStatus(WebhookDeliveryStatus.EXHAUSTED);
        delivery.setAttemptCount(5);
        delivery.setNextAttemptAt(null);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        // 1. Cross-tenant caller rejected
        when(accountsClient.resolveApprovedMerchant("intruder_sub"))
                .thenReturn(new ApprovedMerchantTenant(UUID.randomUUID(), "intruder_sub", "Intruder Corp", "APPROVED", true, true));
        assertThatThrownBy(() -> subscriptionService.replayDelivery("intruder_sub", List.of("MERCHANT"), subscriptionId, deliveryId, "Fix"))
                .isInstanceOf(CrossTenantAccessException.class);

        // 2. Approved owner triggers replay with valid audit reason
        subscriptionService.replayDelivery(CALLER_SUB, List.of("MERCHANT"), subscriptionId, deliveryId, "Partner webhook endpoint restored");

        // Verify audit entry saved
        ArgumentCaptor<WebhookReplayAudit> auditCaptor = ArgumentCaptor.forClass(WebhookReplayAudit.class);
        verify(replayAuditRepository).save(auditCaptor.capture());
        WebhookReplayAudit audit = auditCaptor.getValue();
        assertThat(audit.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(audit.getPreviousStatus()).isEqualTo("EXHAUSTED");
        assertThat(audit.getReason()).contains("Partner webhook endpoint restored");

        // Delivery is reset to PENDING with attemptCount 0 and nextAttemptAt now
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(0);
        assertThat(delivery.getNextAttemptAt()).isNotNull();

        // 3. Scheduler discovers replayed job and executes attempt #1 of replay
        when(deliveryRepository.findDueDeliveries(eq(WebhookDeliveryStatus.PENDING), any(), any()))
                .thenReturn(List.of(delivery));
        when(deliveryRepository.claimDeliveryWithLease(eq(deliveryId), any(), any(), any(), any())).thenReturn(1);
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(200, Map.of(), 110));

        scheduler.schedulePendingDeliveries();

        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();

        // Invariant: Cannot replay delivery in SUCCESS state
        assertThatThrownBy(() -> subscriptionService.replayDelivery(CALLER_SUB, List.of("MERCHANT"), subscriptionId, deliveryId, "Retry again"))
                .isInstanceOf(InvalidWebhookReplayException.class);
    }

    @Test
    @DisplayName("Stage 7: Non-Retryable Failure — SSRF rejection, 400 Bad Request, and 302 Redirect transition immediately to FAILED")
    void testNonRetryableFailuresImmediatelyTransitionToFailed() throws Exception {
        // Case A: Destination security policy violation (SSRF)
        WebhookDelivery jobSsrf = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest-ssrf"
        );
        when(destinationValidator.validate(DESTINATION_URL))
                .thenThrow(new InvalidWebhookDestinationException("Private RFC 1918 destination blocked"));

        executionService.executeDelivery(jobSsrf);

        assertThat(jobSsrf.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(jobSsrf.getNextAttemptAt()).isNull();
        verify(httpClient, never()).send(any(WebhookHttpRequest.class));

        // Case B: 400 Bad Request client error
        doReturn(DESTINATION_URL).when(destinationValidator).validate(DESTINATION_URL);
        WebhookDelivery job400 = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest-400"
        );
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(400, Map.of(), 90));

        executionService.executeDelivery(job400);

        assertThat(job400.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(job400.getNextAttemptAt()).isNull();

        // Case C: 302 Found redirect (prevent SSRF redirect bypass)
        WebhookDelivery job302 = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest-302"
        );
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(302, Map.of(), 60));

        executionService.executeDelivery(job302);

        assertThat(job302.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(job302.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("Stage 8: Crash Recovery — Stalled PROCESSING jobs past lease timeout are safely returned to PENDING")
    void testCrashRecoveryForStalledProcessingJobs() {
        WebhookDelivery crashedJob = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest"
        );
        crashedJob.setStatus(WebhookDeliveryStatus.PROCESSING);
        crashedJob.setUpdatedAt(testClock.instant().minusSeconds(400)); // Stalled beyond 300s lease

        when(deliveryRepository.findByStatusAndUpdatedAtLessThanEqual(eq(WebhookDeliveryStatus.PROCESSING), any(Instant.class)))
                .thenReturn(List.of(crashedJob));

        int recovered = scheduler.recoverStalledDeliveries();

        assertThat(recovered).isEqualTo(1);
        assertThat(crashedJob.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(crashedJob.getNextAttemptAt()).isEqualTo(testClock.instant());
        verify(deliveryRepository).save(crashedJob);
    }

    @Test
    @DisplayName("Stage 9: Security Invariants — Zero secrets or raw PII leaked into attempts or logs")
    void testSecurityInvariantsZeroSecretsInAttempts() throws Exception {
        WebhookDelivery delivery = new WebhookDelivery(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), UUID.randomUUID(), merchantId, 1, "digest"
        );
        when(httpClient.send(any(WebhookHttpRequest.class)))
                .thenReturn(new WebhookHttpResponse(500, Map.of("Authorization", List.of("Bearer super_secret")), 150));

        executionService.executeDelivery(delivery);

        ArgumentCaptor<WebhookAttempt> attemptCaptor = ArgumentCaptor.forClass(WebhookAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        WebhookAttempt attempt = attemptCaptor.getValue();

        assertThat(attempt.getErrorSummary()).doesNotContain(PLAIN_SECRET);
        assertThat(attempt.getErrorSummary()).doesNotContain("super_secret");
        assertThat(attempt.getErrorSummary()).doesNotContain("Bearer");
    }

    /**
     * Mutable test clock enabling deterministic step-by-step advancement in tests.
     */
    private static class MutableTestClock extends Clock {
        private Instant currentInstant;
        private final ZoneOffset zone = ZoneOffset.UTC;

        public MutableTestClock(Instant initial) {
            this.currentInstant = initial;
        }

        public void advance(Duration duration) {
            this.currentInstant = this.currentInstant.plus(duration);
        }

        public void set(Instant newInstant) {
            this.currentInstant = newInstant;
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
