package com.swiftdrop.communicationsservice.webhook.service;

import com.swiftdrop.communicationsservice.client.AccountsMerchantEligibilityClient;
import com.swiftdrop.communicationsservice.client.ApprovedMerchantTenant;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit;
import com.swiftdrop.communicationsservice.webhook.WebhookReplayAuditRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.dto.CreateWebhookSubscriptionRequest;
import com.swiftdrop.communicationsservice.webhook.dto.UpdateWebhookSubscriptionRequest;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryItem;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryLogListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionCreatedResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionResponse;
import com.swiftdrop.communicationsservice.webhook.exception.CrossTenantAccessException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookReplayException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookDeliveryNotFoundException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookSubscriptionNotFoundException;
import com.swiftdrop.communicationsservice.webhook.security.WebhookSigningSecretService;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookEventFilterValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing webhook subscription lifecycle, destination validation, and HMAC signing secret generation.
 * Enforces strict tenant isolation, merchant approval from Accounts Service, and SSRF prevention.
 */
@Service
public class WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookAttemptRepository attemptRepository;
    private final WebhookDestinationValidator destinationValidator;
    private final WebhookEventFilterValidator eventFilterValidator;
    private final WebhookSigningSecretService secretService;
    private final AccountsMerchantEligibilityClient accountsEligibilityClient;
    private final WebhookReplayAuditRepository replayAuditRepository;

    public WebhookSubscriptionService(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookAttemptRepository attemptRepository,
            WebhookDestinationValidator destinationValidator,
            WebhookEventFilterValidator eventFilterValidator,
            WebhookSigningSecretService secretService,
            AccountsMerchantEligibilityClient accountsEligibilityClient) {
        this(subscriptionRepository, deliveryRepository, attemptRepository, destinationValidator,
                eventFilterValidator, secretService, accountsEligibilityClient, null);
    }

    @Autowired
    public WebhookSubscriptionService(
            WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookAttemptRepository attemptRepository,
            WebhookDestinationValidator destinationValidator,
            WebhookEventFilterValidator eventFilterValidator,
            WebhookSigningSecretService secretService,
            AccountsMerchantEligibilityClient accountsEligibilityClient,
            WebhookReplayAuditRepository replayAuditRepository) {
        this.subscriptionRepository = Objects.requireNonNull(subscriptionRepository, "subscriptionRepository is required");
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository is required");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository is required");
        this.destinationValidator = Objects.requireNonNull(destinationValidator, "destinationValidator is required");
        this.eventFilterValidator = Objects.requireNonNull(eventFilterValidator, "eventFilterValidator is required");
        this.secretService = Objects.requireNonNull(secretService, "secretService is required");
        this.accountsEligibilityClient = Objects.requireNonNull(accountsEligibilityClient, "accountsEligibilityClient is required");
        this.replayAuditRepository = replayAuditRepository;
    }

    /**
     * Registers a new HTTPS webhook destination for the authenticated merchant tenant.
     * Returns the subscription details and the one-time HMAC signing secret.
     */
    @Transactional
    public WebhookSubscriptionCreatedResponse createSubscription(String callerSub, CreateWebhookSubscriptionRequest request) {
        ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);

        String validatedUrl = destinationValidator.validate(request.httpsUrl());
        List<String> validatedEvents = eventFilterValidator.validate(request.allowedEventTypes());

        String plaintextSecret = secretService.generateSecret();
        String secretRef = secretService.encryptSecret(plaintextSecret);

        UUID subscriptionId = UUID.randomUUID();
        String serializedEvents = eventFilterValidator.serialize(validatedEvents);

        WebhookSubscription subscription = new WebhookSubscription(
                subscriptionId,
                tenant.merchantId(),
                validatedUrl,
                serializedEvents,
                secretRef
        );

        WebhookSubscription saved = subscriptionRepository.save(subscription);
        log.info("Registered webhook subscription [subscriptionId={}, merchantId={}, url={}]",
                saved.getSubscriptionId(), tenant.merchantId(), validatedUrl);

        return new WebhookSubscriptionCreatedResponse(
                saved.getSubscriptionId(),
                saved.getMerchantId(),
                saved.getHttpsUrl(),
                validatedEvents,
                plaintextSecret,
                saved.getActive(),
                saved.getCreatedAt()
        );
    }

    /**
     * Lists registered webhook endpoints for the authenticated merchant tenant.
     */
    @Transactional(readOnly = true)
    public WebhookSubscriptionListResponse listSubscriptions(String callerSub) {
        ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);

        List<WebhookSubscription> subscriptions = subscriptionRepository
                .findByMerchantIdOrderByCreatedAtDesc(tenant.merchantId());

        List<WebhookSubscriptionResponse> responseList = subscriptions.stream()
                .map(this::toResponse)
                .toList();

        return new WebhookSubscriptionListResponse(responseList);
    }

    /**
     * Retrieves configuration of a specific webhook subscription, enforcing tenant ownership.
     */
    @Transactional(readOnly = true)
    public WebhookSubscriptionResponse getSubscription(String callerSub, UUID subscriptionId) {
        ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);

        WebhookSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException("Webhook subscription not found."));

        if (!subscription.getMerchantId().equals(tenant.merchantId())) {
            log.warn("Cross-tenant access attempt to subscription [callerMerchantId={}, subscriptionOwner={}, subscriptionId={}]",
                    tenant.merchantId(), subscription.getMerchantId(), subscriptionId);
            throw new CrossTenantAccessException("Caller does not own this subscription.");
        }

        return toResponse(subscription);
    }

    /**
     * Soft-deactivates a webhook subscription (active = false).
     * Preserves subscription identity and historical delivery logs.
     */
    @Transactional
    public void deactivateSubscription(String callerSub, UUID subscriptionId) {
        ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);

        WebhookSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException("Webhook subscription not found."));

        if (!subscription.getMerchantId().equals(tenant.merchantId())) {
            log.warn("Cross-tenant deactivation attempt [callerMerchantId={}, subscriptionOwner={}, subscriptionId={}]",
                    tenant.merchantId(), subscription.getMerchantId(), subscriptionId);
            throw new CrossTenantAccessException("Caller does not own this subscription.");
        }

        if (Boolean.TRUE.equals(subscription.getActive())) {
            subscription.setActive(false);
            subscription.setUpdatedAt(Instant.now());
            subscriptionRepository.save(subscription);
            log.info("Deactivated webhook subscription [subscriptionId={}, merchantId={}]",
                    subscriptionId, tenant.merchantId());
        }
    }

    /**
     * Lists delivery execution history and attempt logs for a subscription.
     */
    @Transactional(readOnly = true)
    public WebhookDeliveryLogListResponse listDeliveries(String callerSub, UUID subscriptionId, int page, int size) {
        ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);

        WebhookSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException("Webhook subscription not found."));

        if (!subscription.getMerchantId().equals(tenant.merchantId())) {
            log.warn("Cross-tenant delivery logs access attempt [callerMerchantId={}, subscriptionOwner={}, subscriptionId={}]",
                    tenant.merchantId(), subscription.getMerchantId(), subscriptionId);
            throw new CrossTenantAccessException("Caller does not own this subscription.");
        }

        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(boundedPage, boundedSize);

        List<WebhookDelivery> deliveries = deliveryRepository
                .findBySubscriptionIdOrderByCreatedAtDesc(subscriptionId, pageable);
        long totalElements = deliveryRepository.countBySubscriptionId(subscriptionId);

        List<WebhookDeliveryItem> items = deliveries.stream()
                .map(this::toDeliveryItem)
                .toList();

        return new WebhookDeliveryLogListResponse(totalElements, items);
    }

    /**
     * Updates an existing webhook subscription (contract extension support, Refs #116).
     */
    @Transactional
    public WebhookSubscriptionResponse updateSubscription(
            String callerSub, UUID subscriptionId, UpdateWebhookSubscriptionRequest request) {
        ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);

        WebhookSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException("Webhook subscription not found."));

        if (!subscription.getMerchantId().equals(tenant.merchantId())) {
            throw new CrossTenantAccessException("Caller does not own this subscription.");
        }

        if (request.httpsUrl() != null && !request.httpsUrl().isBlank()) {
            String validatedUrl = destinationValidator.validate(request.httpsUrl());
            subscription.setHttpsUrl(validatedUrl);
        }

        if (request.allowedEventTypes() != null && !request.allowedEventTypes().isEmpty()) {
            List<String> validatedEvents = eventFilterValidator.validate(request.allowedEventTypes());
            subscription.setAllowedEventTypes(eventFilterValidator.serialize(validatedEvents));
        }

        subscription.setUpdatedAt(Instant.now());
        WebhookSubscription updated = subscriptionRepository.save(subscription);

        log.info("Updated webhook subscription [subscriptionId={}, merchantId={}]",
                subscriptionId, tenant.merchantId());

        return toResponse(updated);
    }

    /**
     * Rotates HMAC signing secret for a subscription (contract extension support, Refs #116).
     */
    @Transactional
    public WebhookSubscriptionCreatedResponse rotateSecret(String callerSub, UUID subscriptionId) {
        ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);

        WebhookSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException("Webhook subscription not found."));

        if (!subscription.getMerchantId().equals(tenant.merchantId())) {
            throw new CrossTenantAccessException("Caller does not own this subscription.");
        }

        String newPlaintextSecret = secretService.generateSecret();
        String newSecretRef = secretService.encryptSecret(newPlaintextSecret);

        subscription.setSecretRef(newSecretRef);
        subscription.setUpdatedAt(Instant.now());
        WebhookSubscription updated = subscriptionRepository.save(subscription);

        log.info("Rotated HMAC signing secret for subscription [subscriptionId={}, merchantId={}]",
                subscriptionId, tenant.merchantId());

        return new WebhookSubscriptionCreatedResponse(
                updated.getSubscriptionId(),
                updated.getMerchantId(),
                updated.getHttpsUrl(),
                eventFilterValidator.deserialize(updated.getAllowedEventTypes()),
                newPlaintextSecret,
                updated.getActive(),
                updated.getCreatedAt()
        );
    }

    /**
     * Authorizes and schedules a replay of a terminally failed or exhausted webhook delivery job.
     * Enforces:
     * 1. Caller authentication and authorization: merchant owner or platform operator (ADMIN / DISPATCHER).
     * 2. Delivery must belong to the specified subscription.
     * 3. Delivery must be in a replayable terminal state (FAILED or EXHAUSTED).
     *    Non-terminal jobs (PENDING, PROCESSING, SUCCESS) are rejected with 409 Conflict.
     * 4. Explicit operator reason is required and recorded in durable webhook_replays audit table.
     * 5. Immutable event identity: original eventId, parcelId, stateVersion, and payload digest are preserved.
     * 6. Preserves existing attempt history in webhook_attempts (lineage preserved).
     * 7. Resets delivery status to PENDING with nextAttemptAt = now() for immediate pickup.
     */
    @Transactional
    public WebhookDeliveryItem replayDelivery(
            String callerSub,
            Collection<String> callerRoles,
            UUID subscriptionId,
            UUID deliveryId,
            String reason) {

        if (reason == null || reason.trim().length() < 3 || reason.trim().length() > 500) {
            throw new IllegalArgumentException("Replay reason must be between 3 and 500 characters.");
        }

        WebhookSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException("Webhook subscription not found."));

        // Authorization check: ADMIN or DISPATCHER can operate on any subscription.
        // Otherwise caller must be an approved merchant tenant who owns the subscription.
        boolean isOperator = callerRoles != null && (callerRoles.contains("ADMIN")
                || callerRoles.contains("ROLE_ADMIN")
                || callerRoles.contains("DISPATCHER")
                || callerRoles.contains("ROLE_DISPATCHER"));

        String actorRole = "MERCHANT";
        if (isOperator) {
            if (callerRoles.contains("ADMIN") || callerRoles.contains("ROLE_ADMIN")) {
                actorRole = "ADMIN";
            } else {
                actorRole = "DISPATCHER";
            }
        } else {
            ApprovedMerchantTenant tenant = accountsEligibilityClient.resolveApprovedMerchant(callerSub);
            if (!subscription.getMerchantId().equals(tenant.merchantId())) {
                log.warn("Cross-tenant replay attempt [callerMerchantId={}, subscriptionOwner={}, subscriptionId={}, deliveryId={}]",
                        tenant.merchantId(), subscription.getMerchantId(), subscriptionId, deliveryId);
                throw new CrossTenantAccessException("Caller does not own this subscription.");
            }
        }

        WebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new WebhookDeliveryNotFoundException("Webhook delivery record not found."));

        if (!delivery.getSubscriptionId().equals(subscriptionId)) {
            log.warn("Replay delivery subscription mismatch [deliverySubId={}, pathSubId={}, deliveryId={}]",
                    delivery.getSubscriptionId(), subscriptionId, deliveryId);
            throw new WebhookDeliveryNotFoundException("Webhook delivery does not belong to the specified subscription.");
        }

        // Terminal state check: only FAILED or EXHAUSTED can be replayed
        WebhookDeliveryStatus currentStatus = delivery.getStatus();
        if (currentStatus != WebhookDeliveryStatus.FAILED && currentStatus != WebhookDeliveryStatus.EXHAUSTED) {
            log.warn("Illegal replay attempted for delivery in non-terminal state [deliveryId={}, status={}]",
                    deliveryId, currentStatus);
            throw new InvalidWebhookReplayException(
                    "Webhook delivery " + deliveryId + " cannot be replayed from status " + currentStatus +
                    ". Only FAILED or EXHAUSTED deliveries can be replayed.");
        }

        Instant now = Instant.now();

        // Durable audit record
        if (replayAuditRepository != null) {
            WebhookReplayAudit audit = new WebhookReplayAudit(
                    UUID.randomUUID(),
                    delivery.getDeliveryId(),
                    subscription.getSubscriptionId(),
                    subscription.getMerchantId(),
                    callerSub,
                    actorRole,
                    reason.trim(),
                    currentStatus.name(),
                    now
            );
            replayAuditRepository.save(audit);
        }

        // Schedule replay: reset status to PENDING, attempt count reset to 0, nextAttemptAt to now
        delivery.setStatus(WebhookDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(now);
        delivery.setUpdatedAt(now);
        WebhookDelivery savedDelivery = deliveryRepository.save(delivery);

        log.info("Authorized webhook delivery replay scheduled [deliveryId={}, subscriptionId={}, actorSub={}, actorRole={}, reason={}]",
                deliveryId, subscriptionId, callerSub, actorRole, reason.trim());

        return toDeliveryItem(savedDelivery);
    }

    private WebhookSubscriptionResponse toResponse(WebhookSubscription sub) {
        return new WebhookSubscriptionResponse(
                sub.getSubscriptionId(),
                sub.getMerchantId(),
                sub.getHttpsUrl(),
                eventFilterValidator.deserialize(sub.getAllowedEventTypes()),
                sub.getActive(),
                sub.getCreatedAt()
        );
    }

    private WebhookDeliveryItem toDeliveryItem(WebhookDelivery delivery) {
        Optional<WebhookAttempt> latestAttempt = attemptRepository
                .findTopByDeliveryIdOrderByAttemptedAtDesc(delivery.getDeliveryId());

        Instant lastAttemptAt = latestAttempt.map(WebhookAttempt::getAttemptedAt)
                .orElse(delivery.getCreatedAt());
        Integer lastHttpStatusCode = latestAttempt.map(WebhookAttempt::getHttpStatusCode).orElse(null);
        Integer lastResponseTimeMs = latestAttempt.map(WebhookAttempt::getResponseTimeMs).orElse(null);
        String errorSummary = latestAttempt.map(WebhookAttempt::getErrorSummary).orElse(null);

        return new WebhookDeliveryItem(
                delivery.getDeliveryId(),
                delivery.getEventId(),
                delivery.getParcelId(),
                delivery.getStatus() != null ? delivery.getStatus().name() : "PENDING",
                delivery.getAttemptCount() != null ? delivery.getAttemptCount() : 0,
                lastAttemptAt,
                lastHttpStatusCode,
                lastResponseTimeMs,
                errorSummary
        );
    }
}
