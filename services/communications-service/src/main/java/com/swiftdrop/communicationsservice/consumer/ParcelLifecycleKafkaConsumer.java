package com.swiftdrop.communicationsservice.consumer;

import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Kafka listener for parcel lifecycle domain events (topic: parcel.lifecycle.v1).
 * Strictly acknowledges Kafka partition offsets only after the PostgreSQL inbox receipt
 * and notification job transaction has committed successfully.
 */
@Component
public class ParcelLifecycleKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ParcelLifecycleKafkaConsumer.class);

    private final ParcelLifecycleEventParser eventParser;
    private final NotificationSchedulingService schedulingService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics communicationsMetrics;

    public void setCommunicationsMetrics(com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics communicationsMetrics) {
        this.communicationsMetrics = communicationsMetrics;
    }

    public ParcelLifecycleKafkaConsumer(
            ParcelLifecycleEventParser eventParser,
            NotificationSchedulingService schedulingService) {
        this.eventParser = Objects.requireNonNull(eventParser, "eventParser cannot be null");
        this.schedulingService = Objects.requireNonNull(schedulingService, "schedulingService cannot be null");
    }

    @KafkaListener(
            topics = "${swiftdrop.kafka.topics.parcel-lifecycle:parcel.lifecycle.v1}",
            groupId = "${spring.kafka.consumer.group-id:communications.notifications.v1}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.info("Received Kafka lifecycle event [topic={}, partition={}, offset={}, key={}]",
                record.topic(), record.partition(), record.offset(), record.key());

        try {
            // 1. Validate envelope, version, and partition key alignment
            ParcelStatusChangedEvent event = eventParser.parseAndValidate(record.key(), record.value());

            // 2. Authorize with Delivery, lookup permitted contact in Accounts, and commit atomically in PostgreSQL
            com.swiftdrop.security.correlation.CorrelationContext.runWith(event.correlationId(), () -> {
                schedulingService.processLifecycleEvent(event);
            });

            if (communicationsMetrics != null) {
                communicationsMetrics.recordKafkaConsumerProcessed("communications.notifications.v1", "success");
            }

            // 3. Acknowledge Kafka record offset strictly AFTER database commit or verified duplicate
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
                log.info("Acknowledged Kafka record offset [topic={}, partition={}, offset={}, eventId={}]",
                        record.topic(), record.partition(), record.offset(), event.eventId());
            }
        } catch (Exception ex) {
            if (communicationsMetrics != null) {
                communicationsMetrics.recordKafkaConsumerProcessed("communications.notifications.v1", "failure");
            }
            throw ex;
        }
    }
}
