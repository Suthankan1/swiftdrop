package com.swiftdrop.deliveryservice.sse.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Kafka consumer configuration for authenticated SSE lifecycle broadcast streaming (Issue #161).
 *
 * Implements a bounded broadcast mechanism where each Delivery replica registers
 * an independent, stable Kafka consumer group (e.g. 'tracking.<replica-instance-id>')
 * rather than sharing a single consumer group. This ensures every replica receives all
 * topic partitions and broadcasts lifecycle updates to its local SSE clients without partition starvation.
 *
 * Uses 'latest' auto-offset-reset to avoid unbounded historical replay, relying on canonical
 * REST reads (GET /api/v1/parcels/{parcelId}) for reconnect synchronization and gap recovery.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "swiftdrop.sse.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class SseKafkaConsumerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SseKafkaConsumerConfiguration.class);

    private final SseReplicaIdentityResolver replicaIdentityResolver;
    private final SseProperties sseProperties;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${swiftdrop.sse.kafka.consumer.auto-offset-reset:${spring.kafka.consumer.auto-offset-reset:latest}}")
    private String autoOffsetReset;

    public SseKafkaConsumerConfiguration(SseReplicaIdentityResolver replicaIdentityResolver, SseProperties sseProperties) {
        this.replicaIdentityResolver = Objects.requireNonNull(replicaIdentityResolver, "replicaIdentityResolver cannot be null");
        this.sseProperties = Objects.requireNonNull(sseProperties, "sseProperties cannot be null");
    }

    /**
     * Resolves the effective Kafka consumer group ID for this replica.
     * Exported as a Spring Bean so that @KafkaListener(groupId = "#{sseConsumerGroupId}")
     * can dynamically bind the replica-specific broadcast group.
     *
     * @return unique and stable consumer group ID for this replica
     */
    @Bean(name = "sseConsumerGroupId")
    public String sseConsumerGroupId() {
        String replicaId = replicaIdentityResolver.resolveReplicaId();
        String groupId = replicaIdentityResolver.resolveConsumerGroupId();
        log.info("Initialized Delivery SSE Kafka broadcast consumer [replicaId='{}', consumerGroupId='{}', offsetReset='{}']",
                replicaId, groupId, autoOffsetReset);
        return groupId;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public void setAutoOffsetReset(String autoOffsetReset) {
        this.autoOffsetReset = autoOffsetReset;
    }

    @Bean
    public ConsumerFactory<String, String> sseKafkaConsumerFactory(String sseConsumerGroupId) {
        String servers = (bootstrapServers != null && !bootstrapServers.isBlank())
                ? bootstrapServers
                : "localhost:9092";
        String offsetReset = (autoOffsetReset != null && !autoOffsetReset.isBlank())
                ? autoOffsetReset
                : (sseProperties.getKafka().getAutoOffsetReset() != null ? sseProperties.getKafka().getAutoOffsetReset() : "latest");

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, sseConsumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> sseKafkaListenerContainerFactory(
            ConsumerFactory<String, String> sseKafkaConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(sseKafkaConsumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setShutdownTimeout(Duration.ofSeconds(10).toMillis());
        return factory;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public String getAutoOffsetReset() {
        return autoOffsetReset;
    }
}
