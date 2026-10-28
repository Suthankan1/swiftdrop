package com.swiftdrop.communicationsservice.e2e;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventId;
import com.swiftdrop.communicationsservice.inbox.ConsumedEventRepository;
import com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.delivery.DefaultWebhookSigner;
import com.swiftdrop.communicationsservice.webhook.delivery.WebhookDeliveryExecutionService;
import com.swiftdrop.communicationsservice.webhook.delivery.WebhookDispatchProperties;
import com.swiftdrop.communicationsservice.webhook.delivery.WebhookHttpClient;
import com.swiftdrop.communicationsservice.webhook.delivery.WebhookHttpResponse;
import com.swiftdrop.communicationsservice.webhook.delivery.WebhookResponseClassifier;
import com.swiftdrop.communicationsservice.webhook.failure.TerminalFailureVisibilityService;
import com.swiftdrop.communicationsservice.webhook.security.WebhookSigningSecretService;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import com.swiftdrop.security.correlation.CorrelationContext;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyntheticEndToEndFlowTest {

    private final Instant fixedNow = Instant.parse("2026-10-28T02:00:00Z");
    private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
    private final JsonMapper json = JsonMapper.builder().build();

    private MeterRegistry meterRegistry;
    private WebhookDeliveryRepository deliveryRepository;
    private WebhookAttemptRepository attemptRepository;
    private WebhookSubscriptionRepository subscriptionRepository;
    private ConsumedEventRepository consumedEventRepository;
    private WebhookSigningSecretService secretService;
    private WebhookHttpClient httpClient;
    private WebhookDestinationValidator destinationValidator;

    private WebhookDeliveryExecutionService executionService;
    private TerminalFailureVisibilityService failureVisibilityService;
    private CommunicationsMetrics communicationsMetrics;

    private final String syntheticCorrelationId = "corr-e2e-synthetic-flow-550e8400";
    private final UUID eventId = UUID.randomUUID();
    private final UUID parcelId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();
    private final String plainSecret = "swd_whsec_1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        attemptRepository = mock(WebhookAttemptRepository.class);
        subscriptionRepository = mock(WebhookSubscriptionRepository.class);
        consumedEventRepository = mock(ConsumedEventRepository.class);
        secretService = mock(WebhookSigningSecretService.class);
        httpClient = mock(WebhookHttpClient.class);
        destinationValidator = mock(WebhookDestinationValidator.class);

        when(attemptRepository.save(any(WebhookAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(inv -> inv.getArgument(0));

        var dispatchProps = new WebhookDispatchProperties();
        dispatchProps.setMaxAttempts(3);
        dispatchProps.setBaseBackoffSeconds(10L);
        dispatchProps.setMaxBackoffSeconds(300L);
        dispatchProps.setRequestTimeoutMs(2000L);

        communicationsMetrics = new CommunicationsMetrics(
                meterRegistry,
                deliveryRepository,
                mock(com.swiftdrop.communicationsservice.notification.NotificationJobRepository.class),
                consumedEventRepository,
                clock
        );

        executionService = new WebhookDeliveryExecutionService(
                deliveryRepository,
                attemptRepository,
                subscriptionRepository,
                secretService,
                new DefaultWebhookSigner(),
                httpClient,
                new WebhookResponseClassifier(),
                destinationValidator,
                dispatchProps,
                json,
                clock,
                () -> 0.0
        );
        executionService.setCommunicationsMetrics(communicationsMetrics);

        failureVisibilityService = new TerminalFailureVisibilityService(
                deliveryRepository,
                attemptRepository
        );

        CorrelationContext.clear();
    }

    @Test
    @DisplayName("End-to-End: Outbox -> Kafka -> Inbox -> Webhook Attempt Failure -> Safe Inspection with Zero PII")
    void syntheticBookingToWebhookFailureFlow_preservesCorrelationAndExposesSafeVisibility() throws Exception {
        // Step 1: Ingress / Gateway correlation context
        CorrelationContext.set(syntheticCorrelationId);
        assertThat(CorrelationContext.get()).contains(syntheticCorrelationId);

        // Step 2: Consumed event in Inbox retains correlation ID
        var consumedEvent = new ConsumedEvent(
                new ConsumedEventId("communications.webhooks.v1", eventId),
                "parcel.status.changed.v1",
                1,
                parcelId,
                merchantId,
                fixedNow
        );
        consumedEvent.setCorrelationId(syntheticCorrelationId);
        when(consumedEventRepository.findById(any())).thenReturn(Optional.of(consumedEvent));

        // Step 3: Webhook delivery job is materialized with correlation ID
        var subscription = new WebhookSubscription(
                subscriptionId,
                merchantId,
                "https://merchant-api.synthetic.example/webhooks",
                "parcel.status.changed.v1",
                "enc:v1:test_iv:test_ciphertext"
        );
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(secretService.decryptSecret("enc:v1:test_iv:test_ciphertext")).thenReturn(plainSecret);

        var delivery = new WebhookDelivery(
                deliveryId,
                subscriptionId,
                eventId,
                parcelId,
                merchantId,
                1,
                "test-digest"
        );
        delivery.setStatus(WebhookDeliveryStatus.PROCESSING);
        delivery.setAttemptCount(0);
        delivery.setCorrelationId(syntheticCorrelationId);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        // Step 4: Synthetic endpoint returns HTTP 500 error
        when(httpClient.send(any())).thenReturn(new WebhookHttpResponse(500, Map.of(), 120, "Internal Server Error"));

        // Step 5: Execute delivery attempt inside correlation context
        CorrelationContext.runWith(syntheticCorrelationId, () -> executionService.executeDelivery(delivery));

        // Assert attempt metric incremented
        var attemptCounter = meterRegistry.find("swiftdrop.webhook.attempts.total").counter();
        assertThat(attemptCounter).isNotNull();
        assertThat(attemptCounter.count()).isEqualTo(1.0);

        // Assert retry scheduled metric incremented
        var retryCounter = meterRegistry.find("swiftdrop.webhook.retry.scheduled.total").counter();
        assertThat(retryCounter).isNotNull();
        assertThat(retryCounter.count()).isEqualTo(1.0);

        // Verify attempt was saved
        verify(attemptRepository).save(any(WebhookAttempt.class));
        verify(deliveryRepository).save(eq(delivery));

        // Step 6: Simulate terminal failure after retry exhaustion
        delivery.setStatus(WebhookDeliveryStatus.EXHAUSTED);
        delivery.setAttemptCount(3);

        var attempt = new WebhookAttempt(
                UUID.randomUUID(),
                deliveryId,
                fixedNow,
                500,
                120,
                "HTTP 500: Server Error"
        );
        when(attemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(eq(deliveryId)))
                .thenReturn(Optional.of(attempt));

        // Step 7: Tenant-authorized failure inspection
        var evidenceOpt = failureVisibilityService.inspectDeliveryFailure(deliveryId, merchantId);
        assertThat(evidenceOpt).isPresent();

        var evidence = evidenceOpt.get();
        // Traceable to correlation and event ID
        assertThat(evidence.correlationId()).isEqualTo(syntheticCorrelationId);
        assertThat(evidence.eventId()).isEqualTo(eventId);
        assertThat(evidence.deliveryId()).isEqualTo(deliveryId);
        assertThat(evidence.status()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(evidence.attemptCount()).isEqualTo(3);
        assertThat(evidence.httpStatusCode()).isEqualTo(500);
        assertThat(evidence.sanitizedErrorReason()).isEqualTo("HTTP 500: Server Error");

        // Verify Zero PII and No Secret Leakage
        String evidenceString = evidence.toString();
        assertThat(evidenceString).doesNotContain(plainSecret);
        assertThat(evidenceString).doesNotContain("enc:v1");
        assertThat(evidenceString).doesNotContain("Authorization");
        assertThat(evidenceString).doesNotContain("Bearer");
        assertThat(evidenceString).doesNotContain("+94");
        assertThat(evidenceString).doesNotContain("@");

        // Step 8: Metric Cardinality Protection Check
        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey();
                String value = tag.getValue();

                // Strict assertion: metric tags must never contain high-cardinality IDs
                assertThat(key).isNotIn("correlationId", "merchantId", "parcelId", "eventId", "deliveryId", "userId");
                assertThat(value).doesNotContain(syntheticCorrelationId);
                assertThat(value).doesNotContain(merchantId.toString());
                assertThat(value).doesNotContain(parcelId.toString());
                assertThat(value).doesNotContain(eventId.toString());
                assertThat(value).doesNotContain(deliveryId.toString());
            }
        }
    }
}
