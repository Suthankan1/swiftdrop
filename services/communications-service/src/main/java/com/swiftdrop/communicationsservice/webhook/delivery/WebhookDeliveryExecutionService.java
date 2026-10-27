package com.swiftdrop.communicationsservice.webhook.delivery;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookAttemptRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryRepository;
import com.swiftdrop.communicationsservice.webhook.WebhookDeliveryStatus;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscriptionRepository;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookAttemptPersistenceException;
import com.swiftdrop.communicationsservice.webhook.security.WebhookSigningSecretService;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Service responsible for executing signed HTTPS webhook deliveries, persisting attempt history,
 * and transitioning delivery job lifecycles according to response classifications.
 *
 * Invariants:
 * 1. Outbound HTTP calls occur strictly OUTSIDE database transactions to prevent connection pool exhaustion.
 * 2. Attempt persistence and job status updates occur in an atomic transactional boundary.
 * 3. Destinations are validated via WebhookDestinationValidator (SSRF defense, HTTPS enforcement).
 * 4. Signing secret is decrypted via WebhookSigningSecretService and HMAC-SHA256 signature is attached.
 * 5. Responses are classified into SUCCESS, REDIRECT, CLIENT_ERROR, RATE_LIMITED, SERVER_ERROR, TIMEOUT, etc.
 * 6. Non-retryable errors (4xx, redirects, security failures) transition job immediately to FAILED.
 * 7. Transient errors (5xx, rate limits, timeouts) schedule retries with exponential backoff up to maxAttempts.
 * 8. Zero customer PII or raw third-party response bodies are stored in webhook_attempts.
 */
@Service
public class WebhookDeliveryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryExecutionService.class);

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookAttemptRepository webhookAttemptRepository;
    private final WebhookSubscriptionRepository webhookSubscriptionRepository;
    private final WebhookSigningSecretService webhookSigningSecretService;
    private final WebhookSigner webhookSigner;
    private final WebhookHttpClient webhookHttpClient;
    private final WebhookResponseClassifier responseClassifier;
    private final WebhookDestinationValidator destinationValidator;
    private final WebhookDispatchProperties dispatchProperties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final DoubleSupplier randomSupplier;

    public WebhookDeliveryExecutionService(
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookAttemptRepository webhookAttemptRepository,
            WebhookSubscriptionRepository webhookSubscriptionRepository,
            WebhookSigningSecretService webhookSigningSecretService,
            WebhookSigner webhookSigner,
            WebhookHttpClient webhookHttpClient,
            WebhookResponseClassifier responseClassifier,
            WebhookDestinationValidator destinationValidator,
            WebhookDispatchProperties dispatchProperties) {
        this(webhookDeliveryRepository, webhookAttemptRepository, webhookSubscriptionRepository,
                webhookSigningSecretService, webhookSigner, webhookHttpClient, responseClassifier,
                destinationValidator, dispatchProperties, JsonMapper.builder().build(), Clock.systemUTC(),
                ThreadLocalRandom.current()::nextDouble);
    }

    @Autowired
    public WebhookDeliveryExecutionService(
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookAttemptRepository webhookAttemptRepository,
            WebhookSubscriptionRepository webhookSubscriptionRepository,
            WebhookSigningSecretService webhookSigningSecretService,
            WebhookSigner webhookSigner,
            WebhookHttpClient webhookHttpClient,
            WebhookResponseClassifier responseClassifier,
            WebhookDestinationValidator destinationValidator,
            WebhookDispatchProperties dispatchProperties,
            Clock clock) {
        this(webhookDeliveryRepository, webhookAttemptRepository, webhookSubscriptionRepository,
                webhookSigningSecretService, webhookSigner, webhookHttpClient, responseClassifier,
                destinationValidator, dispatchProperties, JsonMapper.builder().build(), clock,
                ThreadLocalRandom.current()::nextDouble);
    }

    public WebhookDeliveryExecutionService(
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookAttemptRepository webhookAttemptRepository,
            WebhookSubscriptionRepository webhookSubscriptionRepository,
            WebhookSigningSecretService webhookSigningSecretService,
            WebhookSigner webhookSigner,
            WebhookHttpClient webhookHttpClient,
            WebhookResponseClassifier responseClassifier,
            WebhookDestinationValidator destinationValidator,
            WebhookDispatchProperties dispatchProperties,
            JsonMapper jsonMapper) {
        this(webhookDeliveryRepository, webhookAttemptRepository, webhookSubscriptionRepository,
                webhookSigningSecretService, webhookSigner, webhookHttpClient, responseClassifier,
                destinationValidator, dispatchProperties, jsonMapper, Clock.systemUTC(),
                ThreadLocalRandom.current()::nextDouble);
    }

    public WebhookDeliveryExecutionService(
            WebhookDeliveryRepository webhookDeliveryRepository,
            WebhookAttemptRepository webhookAttemptRepository,
            WebhookSubscriptionRepository webhookSubscriptionRepository,
            WebhookSigningSecretService webhookSigningSecretService,
            WebhookSigner webhookSigner,
            WebhookHttpClient webhookHttpClient,
            WebhookResponseClassifier responseClassifier,
            WebhookDestinationValidator destinationValidator,
            WebhookDispatchProperties dispatchProperties,
            JsonMapper jsonMapper,
            Clock clock,
            DoubleSupplier randomSupplier) {
        this.webhookDeliveryRepository = Objects.requireNonNull(webhookDeliveryRepository, "webhookDeliveryRepository cannot be null");
        this.webhookAttemptRepository = Objects.requireNonNull(webhookAttemptRepository, "webhookAttemptRepository cannot be null");
        this.webhookSubscriptionRepository = Objects.requireNonNull(webhookSubscriptionRepository, "webhookSubscriptionRepository cannot be null");
        this.webhookSigningSecretService = Objects.requireNonNull(webhookSigningSecretService, "webhookSigningSecretService cannot be null");
        this.webhookSigner = Objects.requireNonNull(webhookSigner, "webhookSigner cannot be null");
        this.webhookHttpClient = Objects.requireNonNull(webhookHttpClient, "webhookHttpClient cannot be null");
        this.responseClassifier = Objects.requireNonNull(responseClassifier, "responseClassifier cannot be null");
        this.destinationValidator = Objects.requireNonNull(destinationValidator, "destinationValidator cannot be null");
        this.dispatchProperties = Objects.requireNonNull(dispatchProperties, "dispatchProperties cannot be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.randomSupplier = randomSupplier != null ? randomSupplier : ThreadLocalRandom.current()::nextDouble;
    }

    /**
     * Executes a delivery attempt for the given delivery job ID with explicit payload JSON.
     */
    public WebhookAttemptResult executeDelivery(UUID deliveryId, String payloadJson) {
        WebhookDelivery delivery = webhookDeliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("WebhookDelivery not found: " + deliveryId));
        return executeDelivery(delivery, payloadJson);
    }

    /**
     * Executes a delivery attempt for the given delivery job using canonical payload derived from job metadata.
     */
    public WebhookAttemptResult executeDelivery(WebhookDelivery delivery) {
        return executeDelivery(delivery, (String) null);
    }

    /**
     * Executes a delivery attempt for the given delivery job using a domain event envelope.
     */
    public WebhookAttemptResult executeDelivery(WebhookDelivery delivery, ParcelStatusChangedEvent event) {
        String payloadJson = null;
        if (event != null) {
            try {
                payloadJson = jsonMapper.writeValueAsString(event);
            } catch (Exception ex) {
                log.warn("Failed to serialize ParcelStatusChangedEvent envelope [eventId={}]. Falling back to canonical payload.",
                        delivery.getEventId(), ex);
            }
        }
        return executeDelivery(delivery, payloadJson);
    }

    /**
     * Main execution workflow for an outbound webhook delivery attempt.
     */
    public WebhookAttemptResult executeDelivery(WebhookDelivery delivery, String payloadJson) {
        Objects.requireNonNull(delivery, "delivery cannot be null");
        UUID deliveryId = delivery.getDeliveryId();

        // 1. Guard against re-executing already terminal jobs
        if (delivery.getStatus() == WebhookDeliveryStatus.SUCCESS) {
            log.info("Delivery job already completed successfully [deliveryId={}]. Skipping execution.", deliveryId);
            Optional<WebhookAttempt> latest = webhookAttemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(deliveryId);
            return new WebhookAttemptResult(
                    deliveryId,
                    latest.map(WebhookAttempt::getAttemptId).orElse(null),
                    delivery.getStatus(),
                    delivery.getAttemptCount() != null ? delivery.getAttemptCount() : 0,
                    WebhookResponseClassification.success(200, 0),
                    latest.orElse(null)
            );
        }

        if (delivery.getStatus() == WebhookDeliveryStatus.EXHAUSTED) {
            log.info("Delivery job already reached retry exhaustion [deliveryId={}]. Skipping execution.", deliveryId);
            Optional<WebhookAttempt> latest = webhookAttemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(deliveryId);
            return new WebhookAttemptResult(
                    deliveryId,
                    latest.map(WebhookAttempt::getAttemptId).orElse(null),
                    delivery.getStatus(),
                    delivery.getAttemptCount() != null ? delivery.getAttemptCount() : 0,
                    new WebhookResponseClassification(WebhookOutcomeCategory.SERVER_ERROR, false, false, 500, 0, "Exhausted retries", null),
                    latest.orElse(null)
            );
        }

        if (delivery.getStatus() == WebhookDeliveryStatus.FAILED) {
            log.info("Delivery job already in terminal FAILED status [deliveryId={}]. Skipping execution.", deliveryId);
            Optional<WebhookAttempt> latest = webhookAttemptRepository.findTopByDeliveryIdOrderByAttemptedAtDesc(deliveryId);
            return new WebhookAttemptResult(
                    deliveryId,
                    latest.map(WebhookAttempt::getAttemptId).orElse(null),
                    delivery.getStatus(),
                    delivery.getAttemptCount() != null ? delivery.getAttemptCount() : 0,
                    new WebhookResponseClassification(WebhookOutcomeCategory.CLIENT_ERROR, false, false, null, 0, "Terminal failure", null),
                    latest.orElse(null)
            );
        }

        // 2. Resolve subscription
        WebhookSubscription subscription = webhookSubscriptionRepository.findById(delivery.getSubscriptionId())
                .orElse(null);

        if (subscription == null || !Boolean.TRUE.equals(subscription.getActive())) {
            String errorMsg = "Subscription not found or deactivated [subscriptionId=" + delivery.getSubscriptionId() + "]";
            log.warn("Cannot execute webhook delivery: {} [deliveryId={}]", errorMsg, deliveryId);
            WebhookResponseClassification classification = new WebhookResponseClassification(
                    WebhookOutcomeCategory.CLIENT_ERROR, false, false, null, 0,
                    responseClassifier.sanitize(errorMsg), null
            );
            WebhookAttempt attempt = persistAttemptAndUpdateDelivery(delivery, classification);
            return new WebhookAttemptResult(deliveryId, attempt.getAttemptId(), delivery.getStatus(),
                    delivery.getAttemptCount(), classification, attempt);
        }

        // 3. Pre-validate destination URL
        try {
            destinationValidator.validate(subscription.getHttpsUrl());
        } catch (InvalidWebhookDestinationException ex) {
            log.warn("Destination URL failed security validation [deliveryId={}, url={}, error={}]",
                    deliveryId, subscription.getHttpsUrl(), ex.getMessage());
            WebhookResponseClassification classification = responseClassifier.classifyException(ex, 0);
            WebhookAttempt attempt = persistAttemptAndUpdateDelivery(delivery, classification);
            return new WebhookAttemptResult(deliveryId, attempt.getAttemptId(), delivery.getStatus(),
                    delivery.getAttemptCount(), classification, attempt);
        }

        // 4. Decrypt secret key
        String plaintextSecret;
        try {
            plaintextSecret = webhookSigningSecretService.decryptSecret(subscription.getSecretRef());
        } catch (Exception ex) {
            String errorMsg = "Failed to decrypt webhook signing secret: " + ex.getMessage();
            log.error("Failed to decrypt signing secret for subscription [subscriptionId={}, deliveryId={}]",
                    subscription.getSubscriptionId(), deliveryId, ex);
            WebhookResponseClassification classification = new WebhookResponseClassification(
                    WebhookOutcomeCategory.SECURITY_FAILURE, false, false, null, 0,
                    responseClassifier.sanitize(errorMsg), null
            );
            WebhookAttempt attempt = persistAttemptAndUpdateDelivery(delivery, classification);
            return new WebhookAttemptResult(deliveryId, attempt.getAttemptId(), delivery.getStatus(),
                    delivery.getAttemptCount(), classification, attempt);
        }

        // 5. Canonical payload construction
        String resolvedPayload = (payloadJson != null && !payloadJson.isBlank())
                ? payloadJson.trim()
                : buildCanonicalPayload(delivery);

        // 6. Generate cryptographic signature header
        WebhookSignature signature = webhookSigner.generateSignature(resolvedPayload, plaintextSecret);

        // 7. Transition status to PROCESSING (Optimistic claim in separate transaction)
        markProcessing(deliveryId);

        // 8. Execute HTTP dispatch OUTSIDE database transaction
        int currentAttemptNumber = (delivery.getAttemptCount() != null ? delivery.getAttemptCount() : 0) + 1;
        log.info("Dispatching webhook attempt #{} [deliveryId={}, subscriptionId={}, eventId={}]",
                currentAttemptNumber, deliveryId, delivery.getSubscriptionId(), delivery.getEventId());

        long startTime = clock.millis();
        WebhookResponseClassification classification;
        try {
            WebhookHttpRequest request = new WebhookHttpRequest(
                    subscription.getHttpsUrl(),
                    signature.headerValue(),
                    delivery.getDeliveryId().toString(),
                    resolvedPayload,
                    Duration.ofMillis(dispatchProperties.getRequestTimeoutMs())
            );

            WebhookHttpResponse response = webhookHttpClient.send(request);
            classification = responseClassifier.classify(response);

        } catch (Exception ex) {
            int elapsed = (int) (clock.millis() - startTime);
            classification = responseClassifier.classifyException(ex, elapsed);
        }

        log.info("Classified webhook attempt outcome [deliveryId={}, category={}, statusCode={}, latency={}ms, retryable={}]",
                deliveryId, classification.outcomeCategory(), classification.httpStatusCode(),
                classification.responseTimeMs(), classification.isRetryable());

        // 9. Persist attempt record and update job state atomically IN database transaction
        WebhookAttempt savedAttempt = persistAttemptAndUpdateDelivery(delivery, classification);

        return new WebhookAttemptResult(
                deliveryId,
                savedAttempt.getAttemptId(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                classification,
                savedAttempt
        );
    }

    /**
     * Executes pending deliveries up to the specified limit.
     */
    public List<WebhookAttemptResult> processPendingDeliveries(int limit) {
        List<WebhookDelivery> pendingJobs = webhookDeliveryRepository
                .findByStatusAndNextAttemptAtLessThanEqual(WebhookDeliveryStatus.PENDING, clock.instant());

        List<WebhookAttemptResult> results = new ArrayList<>();
        int count = 0;
        for (WebhookDelivery job : pendingJobs) {
            if (count++ >= limit) {
                break;
            }
            try {
                results.add(executeDelivery(job));
            } catch (Exception ex) {
                log.error("Unhandled error processing pending webhook job [deliveryId={}]", job.getDeliveryId(), ex);
            }
        }
        return results;
    }

    /**
     * Recovers jobs stuck in PROCESSING status beyond the lease timeout.
     */
    @Transactional
    public int recoverStuckProcessingJobs(Duration leaseTimeout) {
        Instant cutoff = clock.instant().minus(leaseTimeout);
        List<WebhookDelivery> stuck = webhookDeliveryRepository
                .findByStatusAndUpdatedAtLessThanEqual(WebhookDeliveryStatus.PROCESSING, cutoff);

        int count = 0;
        Instant now = clock.instant();
        for (WebhookDelivery delivery : stuck) {
            log.warn("Recovering stuck PROCESSING delivery job [deliveryId={}, lastUpdated={}]",
                    delivery.getDeliveryId(), delivery.getUpdatedAt());
            delivery.setStatus(WebhookDeliveryStatus.PENDING);
            delivery.setNextAttemptAt(now);
            delivery.setUpdatedAt(now);
            webhookDeliveryRepository.save(delivery);
            count++;
        }
        return count;
    }

    /**
     * Atomically claims a delivery job for processing if it is in PENDING status or has an expired PROCESSING lease.
     * Returns true if the claim was successfully acquired, false otherwise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimDelivery(UUID deliveryId, Instant leaseCutoff) {
        Instant now = clock.instant();
        int updated = webhookDeliveryRepository.claimDeliveryWithLease(
                deliveryId,
                WebhookDeliveryStatus.PENDING,
                WebhookDeliveryStatus.PROCESSING,
                now,
                leaseCutoff
        );
        return updated > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID deliveryId) {
        webhookDeliveryRepository.findById(deliveryId).ifPresent(d -> {
            if (d.getStatus() == WebhookDeliveryStatus.PENDING) {
                d.setStatus(WebhookDeliveryStatus.PROCESSING);
                d.setUpdatedAt(clock.instant());
                webhookDeliveryRepository.save(d);
            }
        });
    }

    /**
     * Atomically persists the attempt record and advances the delivery job status in PostgreSQL.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookAttempt persistAttemptAndUpdateDelivery(
            WebhookDelivery delivery,
            WebhookResponseClassification classification) {
        try {
            WebhookAttempt attempt = new WebhookAttempt(
                    UUID.randomUUID(),
                    delivery.getDeliveryId(),
                    clock.instant(),
                    classification.httpStatusCode(),
                    classification.responseTimeMs(),
                    classification.errorSummary()
            );

            WebhookAttempt savedAttempt = webhookAttemptRepository.save(attempt);

            int newAttemptCount = (delivery.getAttemptCount() != null ? delivery.getAttemptCount() : 0) + 1;
            delivery.setAttemptCount(newAttemptCount);
            delivery.setUpdatedAt(clock.instant());

            if (classification.isSuccess()) {
                delivery.setStatus(WebhookDeliveryStatus.SUCCESS);
                delivery.setNextAttemptAt(null);
            } else if (classification.isRetryable()) {
                if (newAttemptCount >= dispatchProperties.getMaxAttempts()) {
                    log.warn("Delivery job reached maximum attempts ({}) [deliveryId={}]. Transitioning to EXHAUSTED.",
                            dispatchProperties.getMaxAttempts(), delivery.getDeliveryId());
                    delivery.setStatus(WebhookDeliveryStatus.EXHAUSTED);
                    delivery.setNextAttemptAt(null);
                } else {
                    delivery.setStatus(WebhookDeliveryStatus.PENDING);
                    long backoffSeconds = calculateBackoff(newAttemptCount, classification.retryAfterSeconds());
                    delivery.setNextAttemptAt(clock.instant().plusSeconds(backoffSeconds));
                    log.info("Scheduled retry attempt #{} in {}s [deliveryId={}]",
                            newAttemptCount + 1, backoffSeconds, delivery.getDeliveryId());
                }
            } else {
                // Non-retryable error (4xx client error, redirect, or security policy violation)
                log.warn("Delivery failed with non-retryable outcome ({}) [deliveryId={}]. Transitioning to FAILED.",
                        classification.outcomeCategory(), delivery.getDeliveryId());
                delivery.setStatus(WebhookDeliveryStatus.FAILED);
                delivery.setNextAttemptAt(null);
            }

            webhookDeliveryRepository.save(delivery);
            return savedAttempt;

        } catch (Exception ex) {
            log.error("Failed to persist webhook attempt and update job state [deliveryId={}]",
                    delivery.getDeliveryId(), ex);
            throw new WebhookAttemptPersistenceException(
                    "Database failure persisting attempt for delivery " + delivery.getDeliveryId(), ex);
        }
    }

    /**
     * Calculates exponential backoff with jitter and respect for Retry-After header.
     */
    public long calculateBackoff(int attemptNumber, Long retryAfterSeconds) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return Math.min(retryAfterSeconds, dispatchProperties.getMaxBackoffSeconds());
        }

        long base = dispatchProperties.getBaseBackoffSeconds();
        long max = dispatchProperties.getMaxBackoffSeconds();
        long exponent = Math.max(0, attemptNumber - 1);
        if (exponent >= 30) {
            return max;
        }
        long delay = base * (1L << exponent);
        long cappedDelay = Math.min(delay, max);

        if (!dispatchProperties.isJitterEnabled() || cappedDelay <= 1) {
            return cappedDelay;
        }

        return applyJitter(cappedDelay);
    }

    protected long applyJitter(long delaySeconds) {
        double factor = Math.max(0.0, Math.min(1.0, dispatchProperties.getJitterFactor()));
        double delta = delaySeconds * factor;
        double min = Math.max(1.0, delaySeconds - delta);
        double max = Math.min(dispatchProperties.getMaxBackoffSeconds(), delaySeconds + delta);
        double r = randomSupplier != null ? randomSupplier.getAsDouble() : 0.5;
        long jittered = Math.round(min + r * (max - min));
        return Math.max(1, Math.min(dispatchProperties.getMaxBackoffSeconds(), jittered));
    }

    /**
     * Constructs canonical JSON payload matching contracts/events/parcel.status.changed.v1.schema.json.
     */
    public String buildCanonicalPayload(WebhookDelivery delivery) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("eventId", delivery.getEventId().toString());
        root.put("eventType", "parcel.status.changed.v1");
        root.put("schemaVersion", "1.0.0");
        root.put("aggregateId", delivery.getParcelId().toString());
        root.put("aggregateType", "PARCEL");
        root.put("aggregateVersion", delivery.getStateVersion());
        root.put("occurredAt", (delivery.getCreatedAt() != null ? delivery.getCreatedAt() : clock.instant()).toString());
        root.put("correlationId", delivery.getDeliveryId().toString());
        root.put("producer", "delivery-operations");

        ObjectNode payloadNode = root.putObject("payload");
        payloadNode.put("parcelId", delivery.getParcelId().toString());
        payloadNode.put("trackingNumber", "SWD-PARCEL-" + delivery.getParcelId().toString().substring(0, 8).toUpperCase());
        payloadNode.putNull("oldStatus");
        payloadNode.put("newStatus", "IN_TRANSIT");
        payloadNode.put("merchantId", delivery.getMerchantId().toString());

        return root.toString();
    }
}
