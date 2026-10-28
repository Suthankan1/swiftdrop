package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Kafka listener for parcel lifecycle domain events (topic: parcel.lifecycle.v1)
 * operating under the dedicated webhook consumer group (communications.webhooks.v1).
 *
 * Enforces crash-safe commit ordering:
 * Kafka partition offsets are acknowledged strictly AFTER the PostgreSQL inbox receipt
 * and durable webhook delivery jobs have committed.
 */
@Component
@ConditionalOnProperty(name = "swiftdrop.kafka.consumer.webhooks.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookLifecycleKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(WebhookLifecycleKafkaConsumer.class);

    private final ParcelLifecycleEventParser eventParser;
    private final WebhookJobMaterializationService materializationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics communicationsMetrics;

    public void setCommunicationsMetrics(com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics communicationsMetrics) {
        this.communicationsMetrics = communicationsMetrics;
    }

    public WebhookLifecycleKafkaConsumer(
            ParcelLifecycleEventParser eventParser,
            WebhookJobMaterializationService materializationService) {
        this.eventParser = Objects.requireNonNull(eventParser, "eventParser cannot be null");
        this.materializationService = Objects.requireNonNull(materializationService, "materializationService cannot be null");
    }

    @KafkaListener(
            topics = "${swiftdrop.kafka.topics.parcel-lifecycle:parcel.lifecycle.v1}",
            groupId = "${swiftdrop.kafka.consumer.webhooks.group-id:communications.webhooks.v1}",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.info("Received Kafka lifecycle event for webhooks [topic={}, partition={}, offset={}, key={}]",
                record.topic(), record.partition(), record.offset(), record.key());

        try {
            // 1. Validate envelope, version, and partition key alignment
            ParcelStatusChangedEvent event = eventParser.parseAndValidate(record.key(), record.value());

            // 2. Materialize durable webhook jobs in PostgreSQL within atomic transaction boundary
            final WebhookMaterializationResult[] holder = new WebhookMaterializationResult[1];
            com.swiftdrop.security.correlation.CorrelationContext.runWith(event.correlationId(), () -> {
                holder[0] = materializationService.processLifecycleEvent(event, record.value());
            });
            WebhookMaterializationResult result = holder[0];

            if (communicationsMetrics != null) {
                communicationsMetrics.recordKafkaConsumerProcessed("communications.webhooks.v1", "success");
            }

            // 3. Acknowledge Kafka record offset strictly AFTER database commit or verified duplicate
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
                log.info("Acknowledged Kafka record offset for webhooks [topic={}, partition={}, offset={}, eventId={}, outcome={}]",
                        record.topic(), record.partition(), record.offset(), event.eventId(), result != null ? result.summary() : "UNKNOWN");
            }
        } catch (Exception ex) {
            if (communicationsMetrics != null) {
                communicationsMetrics.recordKafkaConsumerProcessed("communications.webhooks.v1", "failure");
            }
            throw ex;
        }
    }
}
