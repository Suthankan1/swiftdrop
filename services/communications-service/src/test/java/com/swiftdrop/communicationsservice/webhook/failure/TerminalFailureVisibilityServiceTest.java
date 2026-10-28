package com.swiftdrop.communicationsservice.webhook.failure;

import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class TerminalFailureVisibilityServiceTest {

    private WebhookDeliveryRepository deliveryRepository;
    private WebhookAttemptRepository attemptRepository;
    private TerminalFailureVisibilityService service;

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String CORRELATION_ID = "corr-test-123";

    @BeforeEach
    void setUp() {
        deliveryRepository = Mockito.mock(WebhookDeliveryRepository.class);
        attemptRepository = Mockito.mock(WebhookAttemptRepository.class);
        service = new TerminalFailureVisibilityService(deliveryRepository, attemptRepository);
    }

    @Test
    @DisplayName("Terminal failure inspection returns sanitized evidence linking correlationId and eventId")
    void terminalFailure_ReturnsSanitizedEvidence() {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId(DELIVERY_ID);
        delivery.setEventId(EVENT_ID);
        delivery.setCorrelationId(CORRELATION_ID);
        delivery.setMerchantId(MERCHANT_ID);
        delivery.setParcelId(UUID.randomUUID());
        delivery.setStatus(WebhookDeliveryStatus.EXHAUSTED);
        delivery.setAttemptCount(5);

        WebhookAttempt attempt = new WebhookAttempt(
                UUID.randomUUID(),
                DELIVERY_ID,
                Instant.now(),
                504,
                3000,
                "Gateway Timeout from provider endpoint"
        );

        when(deliveryRepository.findById(eq(DELIVERY_ID))).thenReturn(Optional.of(delivery));
        when(attemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(eq(DELIVERY_ID))).thenReturn(Optional.of(attempt));

        Optional<TerminalFailureVisibilityService.WebhookFailureEvidence> evidenceOpt =
                service.inspectDeliveryFailure(DELIVERY_ID, MERCHANT_ID);

        assertThat(evidenceOpt).isPresent();
        var evidence = evidenceOpt.get();
        assertThat(evidence.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(evidence.eventId()).isEqualTo(EVENT_ID);
        assertThat(evidence.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(evidence.status()).isEqualTo(WebhookDeliveryStatus.EXHAUSTED);
        assertThat(evidence.attemptCount()).isEqualTo(5);
        assertThat(evidence.httpStatusCode()).isEqualTo(504);
        assertThat(evidence.sanitizedErrorReason()).isEqualTo("Gateway Timeout from provider endpoint");
    }

    @Test
    @DisplayName("Tenant isolation blocks another merchant from inspecting delivery failure")
    void tenantIsolation_BlocksCrossMerchantAccess() {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId(DELIVERY_ID);
        delivery.setMerchantId(MERCHANT_ID);
        delivery.setStatus(WebhookDeliveryStatus.FAILED);

        when(deliveryRepository.findById(eq(DELIVERY_ID))).thenReturn(Optional.of(delivery));

        Optional<TerminalFailureVisibilityService.WebhookFailureEvidence> evidenceOpt =
                service.inspectDeliveryFailure(DELIVERY_ID, OTHER_MERCHANT_ID);

        assertThat(evidenceOpt).isEmpty();
    }

    @Test
    @DisplayName("Non-terminal delivery (PENDING or SUCCESS) is not returned for failure inspection")
    void nonTerminalDelivery_ReturnsEmpty() {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId(DELIVERY_ID);
        delivery.setMerchantId(MERCHANT_ID);
        delivery.setStatus(WebhookDeliveryStatus.SUCCESS);

        when(deliveryRepository.findById(eq(DELIVERY_ID))).thenReturn(Optional.of(delivery));

        Optional<TerminalFailureVisibilityService.WebhookFailureEvidence> evidenceOpt =
                service.inspectDeliveryFailure(DELIVERY_ID, MERCHANT_ID);

        assertThat(evidenceOpt).isEmpty();
    }
}
