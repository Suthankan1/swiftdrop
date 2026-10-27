package com.swiftdrop.deliveryservice.sse.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SseReplicaIdentityResolverTest {

    @Mock
    private Environment environment;

    private SseProperties properties;
    private SseReplicaIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new SseProperties();
        resolver = new SseReplicaIdentityResolver(properties, environment);
    }

    @Test
    @DisplayName("Explicit replica-id property takes highest precedence")
    void testResolveReplicaId_ExplicitProperty() {
        properties.getKafka().setReplicaId("delivery-pod-alpha");

        String replicaId = resolver.resolveReplicaId();

        assertThat(replicaId).isEqualTo("delivery-pod-alpha");
    }

    @Test
    @DisplayName("Replica ID sanitization converts invalid characters and trims boundaries")
    void testResolveReplicaId_Sanitization() {
        properties.getKafka().setReplicaId("  ..delivery/pod#01:node_a..  ");

        String replicaId = resolver.resolveReplicaId();

        assertThat(replicaId).isEqualTo("delivery-pod-01-node_a");
    }

    @Test
    @DisplayName("Default consumer group ID composes prefix 'tracking.' with replica identity")
    void testResolveConsumerGroupId_DefaultTrackingPrefix() {
        properties.getKafka().setReplicaId("replica-1");

        String groupId = resolver.resolveConsumerGroupId();

        assertThat(groupId).isEqualTo("tracking.replica-1");
    }

    @Test
    @DisplayName("Explicit group-id property overrides automatic prefix composition")
    void testResolveConsumerGroupId_ExplicitOverride() {
        properties.getKafka().setReplicaId("replica-1");
        properties.getKafka().setGroupId("custom-explicit-sse-group");

        String groupId = resolver.resolveConsumerGroupId();

        assertThat(groupId).isEqualTo("custom-explicit-sse-group");
    }

    @Test
    @DisplayName("Custom group prefix is honored when composing group ID")
    void testResolveConsumerGroupId_CustomPrefix() {
        properties.getKafka().setReplicaId("pod-b");
        properties.getKafka().setGroupPrefix("broadcast.delivery.");

        String groupId = resolver.resolveConsumerGroupId();

        assertThat(groupId).isEqualTo("broadcast.delivery.pod-b");
    }

    @Test
    @DisplayName("Old shared default 'delivery-service-sse' is ignored to prevent partition starvation")
    void testResolveConsumerGroupId_OldDefaultIgnored() {
        properties.getKafka().setReplicaId("replica-2");
        when(environment.getProperty("spring.kafka.consumer.group-id")).thenReturn("delivery-service-sse");

        String groupId = resolver.resolveConsumerGroupId();

        assertThat(groupId).isEqualTo("tracking.replica-2");
    }

    @Test
    @DisplayName("Custom spring.kafka.consumer.group-id is honored for test overrides")
    void testResolveConsumerGroupId_CustomTestOverrideHonored() {
        when(environment.getProperty("spring.kafka.consumer.group-id")).thenReturn("custom-test-bridge-group");

        String groupId = resolver.resolveConsumerGroupId();

        assertThat(groupId).isEqualTo("custom-test-bridge-group");
    }

    @Test
    @DisplayName("Collision validator throws IllegalArgumentException when replica IDs match")
    void testValidateDistinctReplicas_CollisionThrows() {
        assertThatThrownBy(() -> SseReplicaIdentityResolver.validateDistinctReplicas("replica-a", "REPLICA-A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition starvation")
                .hasMessageContaining("replica-a");
    }

    @Test
    @DisplayName("Collision validator passes when replica IDs are distinct")
    void testValidateDistinctReplicas_DistinctSucceeds() {
        SseReplicaIdentityResolver.validateDistinctReplicas("replica-a", "replica-b");
    }

    @Test
    @DisplayName("Sanitize edge cases: null, blank, and all-symbol strings fall back safely")
    void testSanitize_EdgeCases() {
        assertThat(SseReplicaIdentityResolver.sanitize(null)).isEqualTo("default-replica");
        assertThat(SseReplicaIdentityResolver.sanitize("")).isEqualTo("default-replica");
        assertThat(SseReplicaIdentityResolver.sanitize("    ")).isEqualTo("default-replica");
        assertThat(SseReplicaIdentityResolver.sanitize("---...---")).isEqualTo("default-replica");
    }

    @Test
    @DisplayName("Sanitize truncates strings exceeding 100 characters")
    void testSanitize_LengthCap() {
        String veryLong = "a".repeat(150);
        String sanitized = SseReplicaIdentityResolver.sanitize(veryLong);

        assertThat(sanitized).hasSize(100);
        assertThat(sanitized).isEqualTo("a".repeat(100));
    }
}
