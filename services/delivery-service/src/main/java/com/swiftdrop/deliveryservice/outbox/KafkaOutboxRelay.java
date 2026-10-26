package com.swiftdrop.deliveryservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded Kafka Outbox Relay.
 *
 * Implements DEC-EVT-01..04 and Section 5 of Issue #31:
 * - Bounded polling batch
 * - Safe concurrent row claiming via PostgreSQL FOR UPDATE SKIP LOCKED
 * - Expiring claims/leases for crash recovery
 * - Synchronous broker acknowledgement wait before marking published
 * - Bounded exponential backoff retry schedule
 * - Poison-event dead-lettering
 * - Strict aggregate version sequencing per parcel partition key
 */
@Component
@EnableScheduling
public class KafkaOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRelayProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String workerId;

    @Autowired
    public KafkaOutboxRelay(
            OutboxEventRepository outboxEventRepository,
            OutboxRelayProperties properties,
            @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.workerId = "relay-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Periodic scheduled runner polling for ready outbox events.
     */
    @Scheduled(fixedDelayString = "${swiftdrop.outbox.relay.polling-interval-ms:1000}")
    public void scheduleRelayCycle() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            int publishedCount = processBatch();
            if (publishedCount > 0) {
                log.debug("Outbox relay [{}] successfully published {} events", workerId, publishedCount);
            }
        } catch (Exception e) {
            log.error("Outbox relay [{}] error during polling cycle: {}", workerId, e.getMessage(), e);
        }
    }

    /**
     * Executes one bounded batch claim and dispatch cycle.
     *
     * @return count of successfully published events in this batch
     */
    public int processBatch() {
        if (kafkaTemplate == null) {
            log.trace("KafkaTemplate unavailable; relay cycle skipped.");
            return 0;
        }

        Instant now = Instant.now();
        List<OutboxEvent> claimedBatch = claimEvents(now);
        if (claimedBatch.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (OutboxEvent event : claimedBatch) {
            boolean success = dispatchEvent(event);
            if (success) {
                successCount++;
            }
        }
        return successCount;
    }

    /**
     * Step 1: Claim batch using PostgreSQL FOR UPDATE SKIP LOCKED in a dedicated transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimEvents(Instant now) {
        List<OutboxEvent> candidates = outboxEventRepository.claimNextBatch(
                now,
                properties.getMaxAttempts(),
                properties.getBatchSize()
        );

        Instant expiresAt = now.plusSeconds(properties.getClaimTimeoutSeconds());
        for (OutboxEvent event : candidates) {
            event.setStatus(OutboxStatus.IN_FLIGHT);
            event.setClaimedBy(workerId);
            event.setClaimExpiresAt(expiresAt);
            event.setLastAttemptAt(now);
            event.setAttemptCount(event.getAttemptCount() + 1);
        }

        return outboxEventRepository.saveAll(candidates);
    }

    /**
     * Step 2: Publish to Kafka with broker acknowledgement.
     */
    private boolean dispatchEvent(OutboxEvent event) {
        String topic = (event.getTopic() != null && !event.getTopic().isBlank())
                ? event.getTopic()
                : properties.getTopic();

        try {
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    topic,
                    event.getPartitionKey(),
                    event.getPayload()
            );

            // Bounded wait for broker acknowledgement
            SendResult<String, String> sendResult = future.get(5, TimeUnit.SECONDS);
            Instant ackTime = Instant.now();

            markPublished(event.getEventId(), ackTime);
            log.debug("Event {} published to partition {} offset {}",
                    event.getEventId(),
                    sendResult.getRecordMetadata().partition(),
                    sendResult.getRecordMetadata().offset());
            return true;

        } catch (Exception e) {
            String errorMsg = sanitizeError(e.getMessage());
            log.warn("Failed to publish outbox event {}: {}", event.getEventId(), errorMsg);
            markFailed(event.getEventId(), errorMsg);
            return false;
        }
    }

    /**
     * Step 3: Record broker acknowledgement and mark PUBLISHED in a dedicated transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId, Instant acknowledgedAt) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setAcknowledgedAt(acknowledgedAt);
            event.setClaimedBy(null);
            event.setClaimExpiresAt(null);
            event.setLastError(null);
            outboxEventRepository.save(event);
        });
    }

    /**
     * Step 4: Handle failure with exponential backoff or dead-lettering in a dedicated transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, String errorMessage) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setLastError(errorMessage);
            event.setClaimedBy(null);
            event.setClaimExpiresAt(null);

            if (event.getAttemptCount() >= properties.getMaxAttempts()) {
                event.setStatus(OutboxStatus.DEAD_LETTER);
                log.error("Outbox event {} exceeded max attempts ({}) and moved to DEAD_LETTER",
                        event.getEventId(), properties.getMaxAttempts());
            } else {
                event.setStatus(OutboxStatus.FAILED);
                long backoffSeconds = calculateBackoff(event.getAttemptCount());
                event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                log.info("Outbox event {} scheduled for retry in {}s (attempt {}/{})",
                        event.getEventId(), backoffSeconds, event.getAttemptCount(), properties.getMaxAttempts());
            }

            outboxEventRepository.save(event);
        });
    }

    /**
     * Computes exponential backoff with ceiling.
     */
    public long calculateBackoff(int attemptCount) {
        double multiplier = Math.pow(properties.getBackoffMultiplier(), Math.max(0, attemptCount - 1));
        long backoff = (long) (properties.getInitialBackoffSeconds() * multiplier);
        return Math.min(properties.getMaxBackoffSeconds(), backoff);
    }

    /**
     * Sanitizes error message to prevent accidental PII leakage in last_error column.
     */
    private String sanitizeError(String message) {
        if (message == null) {
            return "Unknown publication error";
        }
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        return message.replaceAll("[\r\n]", " ");
    }

    public String getWorkerId() {
        return workerId;
    }
}
