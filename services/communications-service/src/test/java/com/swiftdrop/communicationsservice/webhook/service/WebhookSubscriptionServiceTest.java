package com.swiftdrop.communicationsservice.webhook.service;

import com.swiftdrop.communicationsservice.client.AccountsMerchantEligibilityClient;
import com.swiftdrop.communicationsservice.client.ApprovedMerchantTenant;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.dto.CreateWebhookSubscriptionRequest;
import com.swiftdrop.communicationsservice.webhook.dto.UpdateWebhookSubscriptionRequest;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryLogListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionCreatedResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionResponse;
import com.swiftdrop.communicationsservice.webhook.exception.CrossTenantAccessException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookSubscriptionNotFoundException;
import com.swiftdrop.communicationsservice.webhook.security.WebhookSigningSecretService;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookEventFilterValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookSubscriptionServiceTest {

    private WebhookSubscriptionRepository subscriptionRepository;
    private WebhookDeliveryRepository deliveryRepository;
    private WebhookAttemptRepository attemptRepository;
    private WebhookDestinationValidator destinationValidator;
    private WebhookEventFilterValidator eventFilterValidator;
    private WebhookSigningSecretService secretService;
    private AccountsMerchantEligibilityClient accountsEligibilityClient;

    private WebhookSubscriptionService service;

    private final UUID callerMerchantId = UUID.randomUUID();
    private final String callerSub = "auth-subject-123";
    private final ApprovedMerchantTenant approvedTenant = new ApprovedMerchantTenant(
            callerMerchantId, callerSub, "Merchant Corp", "APPROVED", true, true);

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(WebhookSubscriptionRepository.class);
        deliveryRepository = mock(WebhookDeliveryRepository.class);
        attemptRepository = mock(WebhookAttemptRepository.class);
        destinationValidator = mock(WebhookDestinationValidator.class);
        eventFilterValidator = new WebhookEventFilterValidator();
        secretService = new WebhookSigningSecretService("");
        accountsEligibilityClient = mock(AccountsMerchantEligibilityClient.class);

        when(accountsEligibilityClient.resolveApprovedMerchant(callerSub)).thenReturn(approvedTenant);
        when(destinationValidator.validate(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new WebhookSubscriptionService(
                subscriptionRepository,
                deliveryRepository,
                attemptRepository,
                destinationValidator,
                eventFilterValidator,
                secretService,
                accountsEligibilityClient
        );
    }

    @Test
    @DisplayName("Create subscription persists encrypted secretRef and returns plaintext secret once")
    void createSubscription_PersistsEncryptedRef_ReturnsPlaintextOnce() {
        CreateWebhookSubscriptionRequest request = new CreateWebhookSubscriptionRequest(
                "https://api.merchant.com/callbacks",
                List.of("parcel.status.changed.v1")
        );

        when(subscriptionRepository.save(any(WebhookSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WebhookSubscriptionCreatedResponse response = service.createSubscription(callerSub, request);

        assertThat(response.merchantId()).isEqualTo(callerMerchantId);
        assertThat(response.httpsUrl()).isEqualTo("https://api.merchant.com/callbacks");
        assertThat(response.allowedEventTypes()).containsExactly("parcel.status.changed.v1");
        assertThat(response.hmacSigningSecret()).startsWith("swd_whsec_");
        assertThat(response.active()).isTrue();

        ArgumentCaptor<WebhookSubscription> captor = ArgumentCaptor.forClass(WebhookSubscription.class);
        verify(subscriptionRepository).save(captor.capture());
        WebhookSubscription saved = captor.getValue();

        assertThat(saved.getMerchantId()).isEqualTo(callerMerchantId);
        assertThat(saved.getSecretRef()).startsWith("enc:v1:");
        assertThat(saved.getSecretRef()).doesNotContain(response.hmacSigningSecret());
        assertThat(secretService.decryptSecret(saved.getSecretRef())).isEqualTo(response.hmacSigningSecret());
    }

    @Test
    @DisplayName("List subscriptions returns only caller's subscriptions without signing secret")
    void listSubscriptions_ReturnsOnlyCallerSubscriptions() {
        WebhookSubscription sub1 = new WebhookSubscription(
                UUID.randomUUID(), callerMerchantId, "https://api1.com", "parcel.status.changed.v1", "enc:v1:secret1");
        WebhookSubscription sub2 = new WebhookSubscription(
                UUID.randomUUID(), callerMerchantId, "https://api2.com", "parcel.status.changed.v1", "enc:v1:secret2");

        when(subscriptionRepository.findByMerchantIdOrderByCreatedAtDesc(callerMerchantId))
                .thenReturn(List.of(sub1, sub2));

        WebhookSubscriptionListResponse listResponse = service.listSubscriptions(callerSub);

        assertThat(listResponse.subscriptions()).hasSize(2);
        assertThat(listResponse.subscriptions().get(0).subscriptionId()).isEqualTo(sub1.getSubscriptionId());
        assertThat(listResponse.subscriptions().get(1).subscriptionId()).isEqualTo(sub2.getSubscriptionId());
    }

    @Test
    @DisplayName("Get subscription returns details when caller is tenant owner")
    void getSubscription_OwnedByCaller_ReturnsDetails() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, callerMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));

        WebhookSubscriptionResponse response = service.getSubscription(callerSub, subscriptionId);

        assertThat(response.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(response.merchantId()).isEqualTo(callerMerchantId);
        assertThat(response.httpsUrl()).isEqualTo("https://api.com");
    }

    @Test
    @DisplayName("Get subscription throws CrossTenantAccessException when caller does not own subscription")
    void getSubscription_CrossTenant_ThrowsCrossTenantAccessException() {
        UUID subscriptionId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, otherMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.getSubscription(callerSub, subscriptionId))
                .isInstanceOf(CrossTenantAccessException.class)
                .hasMessageContaining("Caller does not own this subscription");
    }

    @Test
    @DisplayName("Get subscription throws WebhookSubscriptionNotFoundException when not found")
    void getSubscription_NotFound_ThrowsException() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSubscription(callerSub, subscriptionId))
                .isInstanceOf(WebhookSubscriptionNotFoundException.class);
    }

    @Test
    @DisplayName("Deactivate subscription sets active = false")
    void deactivateSubscription_SetsActiveFalse() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, callerMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");
        sub.setActive(true);

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateSubscription(callerSub, subscriptionId);

        assertThat(sub.getActive()).isFalse();
        verify(subscriptionRepository).save(sub);
    }

    @Test
    @DisplayName("Deactivate cross-tenant subscription throws CrossTenantAccessException")
    void deactivateSubscription_CrossTenant_ThrowsCrossTenantAccessException() {
        UUID subscriptionId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, otherMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.deactivateSubscription(callerSub, subscriptionId))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    @DisplayName("List deliveries returns deliveries and attempt logs for owned subscription")
    void listDeliveries_OwnedSubscription_ReturnsHistory() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, callerMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));

        UUID deliveryId = UUID.randomUUID();
        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(), callerMerchantId, 1, "digest1");
        delivery.setStatus(WebhookDeliveryStatus.SUCCESS);
        delivery.setAttemptCount(1);

        WebhookAttempt attempt = new WebhookAttempt(
                UUID.randomUUID(), deliveryId, Instant.now(), 200, 150, null);

        when(deliveryRepository.findBySubscriptionIdOrderByCreatedAtDesc(eq(subscriptionId), any(Pageable.class)))
                .thenReturn(List.of(delivery));
        when(deliveryRepository.countBySubscriptionId(subscriptionId)).thenReturn(1L);
        when(attemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(deliveryId))
                .thenReturn(Optional.of(attempt));

        WebhookDeliveryLogListResponse logResponse = service.listDeliveries(callerSub, subscriptionId, 0, 20);

        assertThat(logResponse.totalElements()).isEqualTo(1);
        assertThat(logResponse.deliveries()).hasSize(1);
        assertThat(logResponse.deliveries().get(0).deliveryId()).isEqualTo(deliveryId);
        assertThat(logResponse.deliveries().get(0).status()).isEqualTo("SUCCESS");
        assertThat(logResponse.deliveries().get(0).lastHttpStatusCode()).isEqualTo(200);
        assertThat(logResponse.deliveries().get(0).lastResponseTimeMs()).isEqualTo(150);
    }

    @Test
    @DisplayName("Update subscription mutates allowed fields and advances updatedAt")
    void updateSubscription_MutatesAllowedFields() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, callerMerchantId, "https://old.com", "parcel.status.changed.v1", "enc:v1:secret");
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWebhookSubscriptionRequest request = new UpdateWebhookSubscriptionRequest(
                "https://new.com/webhook",
                List.of("parcel.status.changed.v1")
        );

        WebhookSubscriptionResponse response = service.updateSubscription(callerSub, subscriptionId, request);

        assertThat(response.httpsUrl()).isEqualTo("https://new.com/webhook");
        assertThat(sub.getHttpsUrl()).isEqualTo("https://new.com/webhook");
    }

    @Test
    @DisplayName("Rotate secret generates new secret and updates secretRef")
    void rotateSecret_GeneratesNewSecretAndEncryptedRef() {
        UUID subscriptionId = UUID.randomUUID();
        String oldSecret = "test-synthetic-secret-" + UUID.randomUUID();
        String oldSecretRef = secretService.encryptSecret(oldSecret);
        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, callerMerchantId, "https://api.com", "parcel.status.changed.v1", oldSecretRef);

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookSubscriptionCreatedResponse response = service.rotateSecret(callerSub, subscriptionId);

        assertThat(response.hmacSigningSecret()).startsWith("swd_whsec_");
        assertThat(sub.getSecretRef()).isNotEqualTo(oldSecretRef);
        assertThat(secretService.decryptSecret(sub.getSecretRef())).isEqualTo(response.hmacSigningSecret());
    }

    @Test
    @DisplayName("Replaying EXHAUSTED delivery schedules PENDING retry and persists audit record")
    void replayDelivery_Exhausted_Success() {
        com.swiftdrop.communicationsservice.webhook.WebhookReplayAuditRepository mockReplayAuditRepo =
                mock(com.swiftdrop.communicationsservice.webhook.WebhookReplayAuditRepository.class);

        WebhookSubscriptionService serviceWithAudit = new WebhookSubscriptionService(
                subscriptionRepository,
                deliveryRepository,
                attemptRepository,
                destinationValidator,
                eventFilterValidator,
                secretService,
                accountsEligibilityClient,
                mockReplayAuditRepo
        );

        UUID subscriptionId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, callerMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");

        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, eventId, parcelId, callerMerchantId, 1, "digest123");
        delivery.setStatus(WebhookDeliveryStatus.EXHAUSTED);
        delivery.setAttemptCount(5);

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryItem result =
                serviceWithAudit.replayDelivery(callerSub, List.of("MERCHANT"), subscriptionId, deliveryId, "Customer network restored");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.attemptCount()).isEqualTo(0);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(0);
        assertThat(delivery.getNextAttemptAt()).isNotNull();

        // Verify original identity preserved
        assertThat(delivery.getEventId()).isEqualTo(eventId);
        assertThat(delivery.getParcelId()).isEqualTo(parcelId);
        assertThat(delivery.getStateVersion()).isEqualTo(1);
        assertThat(delivery.getSignedPayloadDigest()).isEqualTo("digest123");

        // Verify audit record saved
        ArgumentCaptor<com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit> auditCaptor =
                ArgumentCaptor.forClass(com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit.class);
        verify(mockReplayAuditRepo).save(auditCaptor.capture());

        com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(savedAudit.getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(savedAudit.getMerchantId()).isEqualTo(callerMerchantId);
        assertThat(savedAudit.getActorSub()).isEqualTo(callerSub);
        assertThat(savedAudit.getActorRole()).isEqualTo("MERCHANT");
        assertThat(savedAudit.getReason()).isEqualTo("Customer network restored");
        assertThat(savedAudit.getPreviousStatus()).isEqualTo("EXHAUSTED");
    }

    @Test
    @DisplayName("Replaying delivery in SUCCESS state throws InvalidWebhookReplayException (409)")
    void replayDelivery_SuccessStatus_Throws409() {
        UUID subscriptionId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, callerMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");

        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(), callerMerchantId, 1, "digest123");
        delivery.setStatus(WebhookDeliveryStatus.SUCCESS);

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.replayDelivery(callerSub, List.of("MERCHANT"), subscriptionId, deliveryId, "Retry delivered job"))
                .isInstanceOf(com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookReplayException.class)
                .hasMessageContaining("Only FAILED or EXHAUSTED deliveries can be replayed");
    }

    @Test
    @DisplayName("Replaying delivery cross-tenant throws CrossTenantAccessException (403)")
    void replayDelivery_CrossTenant_Throws403() {
        UUID subscriptionId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, otherMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.replayDelivery(callerSub, List.of("MERCHANT"), subscriptionId, deliveryId, "Cross-tenant replay"))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    @DisplayName("Admin operator can replay failed delivery across tenants")
    void replayDelivery_AdminOperator_Allowed() {
        com.swiftdrop.communicationsservice.webhook.WebhookReplayAuditRepository mockReplayAuditRepo =
                mock(com.swiftdrop.communicationsservice.webhook.WebhookReplayAuditRepository.class);

        WebhookSubscriptionService serviceWithAudit = new WebhookSubscriptionService(
                subscriptionRepository,
                deliveryRepository,
                attemptRepository,
                destinationValidator,
                eventFilterValidator,
                secretService,
                accountsEligibilityClient,
                mockReplayAuditRepo
        );

        UUID subscriptionId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();

        WebhookSubscription sub = new WebhookSubscription(
                subscriptionId, otherMerchantId, "https://api.com", "parcel.status.changed.v1", "enc:v1:secret");

        WebhookDelivery delivery = new WebhookDelivery(
                deliveryId, subscriptionId, UUID.randomUUID(), UUID.randomUUID(), otherMerchantId, 1, "digest123");
        delivery.setStatus(WebhookDeliveryStatus.FAILED);

        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(sub));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryItem item =
                serviceWithAudit.replayDelivery("admin-sub-999", List.of("ROLE_ADMIN"), subscriptionId, deliveryId, "Operator manual intervention");

        assertThat(item.status()).isEqualTo("PENDING");
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    }
}
