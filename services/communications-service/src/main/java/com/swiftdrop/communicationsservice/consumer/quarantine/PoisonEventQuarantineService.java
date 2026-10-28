package com.swiftdrop.communicationsservice.consumer.quarantine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.swiftdrop.communicationsservice.consumer.EventValidationException;
import com.swiftdrop.communicationsservice.consumer.NotificationSchedulingService;
import com.swiftdrop.communicationsservice.consumer.ParcelLifecycleEventParser;
import com.swiftdrop.communicationsservice.consumer.SchedulingResult;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing durable poison-event quarantine, safe payload hashing,
 * and authorized operator inspection and replay (Issue #117).
 *
 * Invariants:
 * 1. Zero customer PII or sensitive payload strings are logged or exposed in quarantine APIs.
 * 2. Cryptographic SHA-256 payload digests are used for verification.
 * 3. Quarantine persistence is strictly idempotent across Kafka redeliveries.
 * 4. Reprocessing requires operator inspection, schema correction, and authorized roles.
 */
@Service
public class PoisonEventQuarantineService {

    private static final Logger log = LoggerFactory.getLogger(PoisonEventQuarantineService.class);

    private final PoisonEventQuarantineRepository quarantineRepository;
    private final ParcelLifecycleEventParser eventParser;
    private final NotificationSchedulingService schedulingService;
    private final JsonMapper jsonMapper;

    @Autowired(required = false)
    private CommunicationsMetrics communicationsMetrics;

    public void setCommunicationsMetrics(CommunicationsMetrics communicationsMetrics) {
        this.communicationsMetrics = communicationsMetrics;
    }

    public PoisonEventQuarantineService(
            PoisonEventQuarantineRepository quarantineRepository,
            ParcelLifecycleEventParser eventParser,
            NotificationSchedulingService schedulingService) {
        this(quarantineRepository, eventParser, schedulingService, JsonMapper.builder().build());
    }

    @Autowired
    public PoisonEventQuarantineService(
            PoisonEventQuarantineRepository quarantineRepository,
            ParcelLifecycleEventParser eventParser,
            NotificationSchedulingService schedulingService,
            JsonMapper jsonMapper) {
        this.quarantineRepository = Objects.requireNonNull(quarantineRepository, "quarantineRepository cannot be null");
        this.eventParser = Objects.requireNonNull(eventParser, "eventParser cannot be null");
        this.schedulingService = Objects.requireNonNull(schedulingService, "schedulingService cannot be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper cannot be null");
    }

    /**
     * Safely isolates a poison Kafka event into the durable quarantine table.
     *
     * @param consumerGroup consumer group id
     * @param topic source topic
     * @param partition source partition
     * @param offset source offset
     * @param key record key
     * @param payloadJson raw message payload
     * @param ex validation exception
     * @return persisted quarantine record
     */
    @Transactional
    public PoisonEventQuarantine quarantine(
            String consumerGroup,
            String topic,
            int partition,
            long offset,
            String key,
            String payloadJson,
            EventValidationException ex) {

        // 1. Idempotency check for repeated redeliveries
        Optional<PoisonEventQuarantine> existing = quarantineRepository
                .findByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(consumerGroup, topic, partition, offset);
        if (existing.isPresent()) {
            log.info("Poison event already quarantined [consumerGroup={}, topic={}, partition={}, offset={}].",
                    consumerGroup, topic, partition, offset);
            return existing.get();
        }

        // 2. Compute SHA-256 payload digest
        String payloadDigest = computeSha256(payloadJson != null ? payloadJson : "");

        // 3. Classify poison category
        String failureCategory = classifyPoisonCategory(ex != null ? ex.getMessage() : "Unknown");
        String safeErrorSummary = sanitizeError(ex != null ? ex.getMessage() : "Validation failed");

        // 4. Safely extract envelope metadata if partially valid JSON
        UUID eventId = null;
        String eventType = null;
        String schemaVersion = null;
        String correlationReference = null;

        if (payloadJson != null && !payloadJson.isBlank()) {
            try {
                JsonNode root = jsonMapper.readTree(payloadJson);
                if (root != null && root.isObject()) {
                    if (root.hasNonNull("eventId")) {
                        try {
                            eventId = UUID.fromString(root.get("eventId").asText().trim());
                        } catch (Exception ignored) {}
                    }
                    if (root.hasNonNull("eventType")) {
                        eventType = sanitizeField(root.get("eventType").asText(), 100);
                    }
                    if (root.hasNonNull("schemaVersion")) {
                        schemaVersion = sanitizeField(root.get("schemaVersion").asText(), 50);
                    }
                    if (root.hasNonNull("correlationId")) {
                        correlationReference = sanitizeField(root.get("correlationId").asText(), 64);
                    }
                }
            } catch (Exception ignored) {
                // Not valid JSON
            }
        }

        PoisonEventQuarantine quarantine = new PoisonEventQuarantine(
                UUID.randomUUID(),
                consumerGroup,
                topic,
                partition,
                offset,
                eventId,
                eventType,
                schemaVersion,
                failureCategory,
                safeErrorSummary,
                payloadDigest,
                correlationReference,
                "QUARANTINED",
                Instant.now()
        );

        try {
            PoisonEventQuarantine saved = quarantineRepository.saveAndFlush(quarantine);
            log.warn("Quarantined poison Kafka event [topic={}, partition={}, offset={}, category={}, digest={}]",
                    topic, partition, offset, failureCategory, payloadDigest);

            if (communicationsMetrics != null) {
                communicationsMetrics.recordPoisonEventQuarantined(failureCategory);
            }
            return saved;
        } catch (DataIntegrityViolationException duplicateEx) {
            return quarantineRepository
                    .findByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(consumerGroup, topic, partition, offset)
                    .orElse(quarantine);
        }
    }

    /**
     * Re-processes a quarantined poison event after defect resolution and operator inspection.
     *
     * @param quarantineId quarantine record ID
     * @param operatorSub authenticated operator subject
     * @param operatorRole authenticated operator role
     * @param replayReason reason for replay
     * @param correctedPayloadJson the corrected event payload JSON
     * @return updated quarantine record
     */
    @Transactional
    public PoisonEventQuarantine reprocessQuarantinedEvent(
            UUID quarantineId,
            String operatorSub,
            String operatorRole,
            String replayReason,
            String correctedPayloadJson) {

        if (!SwiftDropRoles.ROLE_ADMIN.equals(operatorRole) && !SwiftDropRoles.ROLE_DISPATCHER.equals(operatorRole)) {
            throw new SecurityException("Caller role " + operatorRole + " is not authorized to replay quarantined events.");
        }
        if (replayReason == null || replayReason.isBlank()) {
            throw new IllegalArgumentException("Replay reason must not be blank.");
        }
        if (correctedPayloadJson == null || correctedPayloadJson.isBlank()) {
            throw new IllegalArgumentException("Corrected payload JSON is required for reprocessing.");
        }

        PoisonEventQuarantine record = quarantineRepository.findById(quarantineId)
                .orElseThrow(() -> new IllegalArgumentException("Quarantined event not found: " + quarantineId));

        if (!"QUARANTINED".equals(record.getStatus())) {
            throw new IllegalStateException("Quarantined event " + quarantineId + " cannot be reprocessed from status " + record.getStatus());
        }

        // 1. Validate the corrected payload with the canonical schema parser
        JsonNode root;
        try {
            root = jsonMapper.readTree(correctedPayloadJson);
        } catch (Exception ex) {
            throw new EventValidationException("Corrected payload is not valid JSON: " + ex.getMessage(), ex);
        }

        UUID parcelId = null;
        if (root.hasNonNull("payload") && root.get("payload").hasNonNull("parcelId")) {
            try {
                parcelId = UUID.fromString(root.get("payload").get("parcelId").asText().trim());
            } catch (Exception ignored) {}
        }
        String recordKey = parcelId != null ? parcelId.toString() : "";

        ParcelStatusChangedEvent validatedEvent = eventParser.parseAndValidate(recordKey, correctedPayloadJson);

        // 2. Execute domain scheduling and persistence
        SchedulingResult result = schedulingService.processLifecycleEvent(validatedEvent);
        log.info("Reprocessed quarantined event successfully [quarantineId={}, eventId={}, duplicate={}]",
                quarantineId, validatedEvent.eventId(), result.isDuplicate());

        // 3. Update quarantine record
        Instant now = Instant.now();
        record.setStatus("REPLAYED");
        record.setReplayedAt(now);
        record.setReplayedByActorSub(operatorSub);
        record.setReplayReason(replayReason.trim());
        PoisonEventQuarantine saved = quarantineRepository.save(record);

        if (communicationsMetrics != null) {
            communicationsMetrics.recordNotificationReplay("succeeded");
        }

        return saved;
    }

    public List<PoisonEventQuarantine> listQuarantinedEvents(Pageable pageable) {
        return quarantineRepository.findByStatusOrderByQuarantinedAtDesc("QUARANTINED", pageable);
    }

    public Optional<PoisonEventQuarantine> getQuarantineDetails(UUID quarantineId) {
        return quarantineRepository.findById(quarantineId);
    }

    private String classifyPoisonCategory(String message) {
        if (message == null) {
            return "SCHEMA_VIOLATION";
        }
        String lower = message.toLowerCase();
        if (lower.contains("json") || lower.contains("parse")) {
            return "INVALID_JSON";
        }
        if (lower.contains("schemaversion") || lower.contains("major version")) {
            return "UNSUPPORTED_SCHEMA_VERSION";
        }
        if (lower.contains("eventtype")) {
            return "UNSUPPORTED_EVENT_TYPE";
        }
        if (lower.contains("partition key") || lower.contains("record key")) {
            return "PARTITION_KEY_MISMATCH";
        }
        if (lower.contains("aggregateversion")) {
            return "INVALID_AGGREGATE_VERSION";
        }
        if (lower.contains("occurredat") || lower.contains("timestamp")) {
            return "INVALID_TIMESTAMP";
        }
        if (lower.contains("status") || lower.contains("oldstatus") || lower.contains("newstatus")) {
            return "INVALID_LIFECYCLE_STATUS";
        }
        if (lower.contains("uuid") || lower.contains("parcelid") || lower.contains("eventid")) {
            return "INVALID_IDENTIFIER";
        }
        if (lower.contains("payload")) {
            return "MALFORMED_PAYLOAD";
        }
        return "SCHEMA_VIOLATION";
    }

    private String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "Validation failed";
        }
        String clean = message.replaceAll("[\r\n\t]", " ").trim();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private String sanitizeField(String val, int maxLen) {
        if (val == null) return null;
        String clean = val.trim();
        return clean.length() > maxLen ? clean.substring(0, maxLen) : clean;
    }

    private String computeSha256(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }
}
