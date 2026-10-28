package com.swiftdrop.communicationsservice.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Kafka consumer infrastructure configuration for Communications Service.
 * Provides independent consumer factories and container factories for notifications
 * (communications.notifications.v1) and webhooks (communications.webhooks.v1).
 * Enforces manual offset commit mode, bounded retry handling, and String deserialization.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "swiftdrop.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class CommunicationsKafkaConsumerConfiguration {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:communications.notifications.v1}")
    private String notificationGroupId;

    @Value("${swiftdrop.kafka.consumer.webhooks.group-id:communications.webhooks.v1}")
    private String webhookGroupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return createConsumerFactory(notificationGroupId);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
        errorHandler.addNotRetryableExceptions(EventValidationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, String> webhookConsumerFactory() {
        return createConsumerFactory(webhookGroupId);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> webhookKafkaListenerContainerFactory(
            ConsumerFactory<String, String> webhookConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(webhookConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
        errorHandler.addNotRetryableExceptions(EventValidationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private ConsumerFactory<String, String> createConsumerFactory(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(props);
    }
}
