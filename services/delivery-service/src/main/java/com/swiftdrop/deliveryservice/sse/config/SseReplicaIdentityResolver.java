package com.swiftdrop.deliveryservice.sse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves stable replica identity and unique Kafka consumer group identifiers
 * for Delivery Service SSE broadcast fan-out (Issue #161).
 *
 * Prevents multiple Delivery replicas from sharing a single consumer group, which
 * would cause Kafka to partition topics between them rather than broadcasting
 * lifecycle events to all SSE-connected clients.
 */
@Component
public class SseReplicaIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(SseReplicaIdentityResolver.class);
    private static final Pattern SAFE_CHARS_PATTERN = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final String DEFAULT_GROUP_PREFIX = "tracking.";
    private static final String DEFAULT_REPLICA_FALLBACK = "default-replica";
    private static final String OLD_SHARED_GROUP_DEFAULT = "delivery-service-sse";

    private static final List<String> REPLICA_ENV_VARS = List.of(
            "SWIFTDROP_SSE_REPLICA_ID",
            "SSE_REPLICA_ID",
            "POD_NAME",
            "INSTANCE_ID",
            "HOSTNAME"
    );

    private final SseProperties properties;
    private final Environment environment;

    public SseReplicaIdentityResolver(SseProperties properties, Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.environment = Objects.requireNonNull(environment, "environment cannot be null");
    }

    /**
     * Resolves a stable and unique replica identifier.
     * Evaluates configured properties, environment variables, system properties, and hostnames in sequence.
     *
     * @return sanitized stable replica identifier
     */
    public String resolveReplicaId() {
        // 1. Explicit configuration property
        String configReplicaId = properties.getKafka().getReplicaId();
        if (configReplicaId != null && !configReplicaId.isBlank()) {
            return sanitize(configReplicaId);
        }

        // 2. Environment variables in priority order
        for (String envVar : REPLICA_ENV_VARS) {
            String val = System.getenv(envVar);
            if (val != null && !val.isBlank()) {
                log.debug("Resolved replica ID from environment variable {}: {}", envVar, val);
                return sanitize(val);
            }
        }

        // 3. Spring environment / system property
        String sysProp = environment.getProperty("swiftdrop.sse.replica-id");
        if (sysProp != null && !sysProp.isBlank()) {
            return sanitize(sysProp);
        }

        // 4. Local hostname fallback
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                log.debug("Resolved replica ID from local hostname: {}", host);
                return sanitize(host);
            }
        } catch (Exception ex) {
            log.debug("Failed to resolve local hostname for replica ID: {}", ex.getMessage());
        }

        // 5. Default fallback
        log.warn("No replica identifier found in configuration or environment. Falling back to '{}'.", DEFAULT_REPLICA_FALLBACK);
        return DEFAULT_REPLICA_FALLBACK;
    }

    /**
     * Resolves the effective Kafka consumer group ID for this replica's SSE broadcast listener.
     *
     * @return effective consumer group ID
     */
    public String resolveConsumerGroupId() {
        // 1. Explicit group-id property configured
        String configGroupId = properties.getKafka().getGroupId();
        if (configGroupId != null && !configGroupId.isBlank()) {
            return configGroupId.trim();
        }

        // 2. Spring Kafka consumer group property if explicitly overridden (not the legacy shared default)
        String springGroupId = environment.getProperty("spring.kafka.consumer.group-id");
        if (springGroupId != null && !springGroupId.isBlank() && !OLD_SHARED_GROUP_DEFAULT.equals(springGroupId.trim())) {
            return springGroupId.trim();
        }

        // 3. Compose tracking.<stable-replica-id>
        String prefix = properties.getKafka().getGroupPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = DEFAULT_GROUP_PREFIX;
        }

        String replicaId = resolveReplicaId();
        return prefix.trim() + replicaId;
    }

    /**
     * Sanitizes replica IDs to conform to Kafka consumer group naming restrictions
     * (alphanumeric, dot, underscore, and dash only; max 100 chars).
     *
     * @param raw raw identifier string
     * @return sanitized identifier string
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_REPLICA_FALLBACK;
        }

        String sanitized = SAFE_CHARS_PATTERN.matcher(raw.trim()).replaceAll("-");

        // Trim leading and trailing dashes or dots
        sanitized = sanitized.replaceAll("^[-.]+", "").replaceAll("[-.]+$", "");

        if (sanitized.isBlank()) {
            return DEFAULT_REPLICA_FALLBACK;
        }

        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }

        return sanitized;
    }

    /**
     * Validates that two replica identities are distinct, rejecting accidental collisions.
     *
     * @param replicaA identifier for replica A
     * @param replicaB identifier for replica B
     * @throws IllegalArgumentException if replica IDs collide
     */
    public static void validateDistinctReplicas(String replicaA, String replicaB) {
        if (replicaA != null && replicaB != null && replicaA.trim().equalsIgnoreCase(replicaB.trim())) {
            throw new IllegalArgumentException(
                    "Simultaneous Delivery replicas must have distinct replica IDs to prevent Kafka consumer group partition starvation. Found collision: " + replicaA
            );
        }
    }
}
