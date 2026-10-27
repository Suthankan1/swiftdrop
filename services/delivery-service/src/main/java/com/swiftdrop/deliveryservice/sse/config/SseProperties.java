package com.swiftdrop.deliveryservice.sse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for authenticated Server-Sent Events (SSE) lifecycle streaming.
 */
@Component
@ConfigurationProperties(prefix = "swiftdrop.sse")
public class SseProperties {

    /**
     * Heartbeat ping interval in seconds (default: 15s per SERVICE_INTERFACE_MATRIX IF-SSE-01).
     */
    private int heartbeatIntervalSeconds = 15;

    /**
     * Maximum global concurrent SSE connections on this Delivery replica.
     */
    private int maxConnections = 100;

    /**
     * Maximum concurrent SSE connections permitted per authenticated user subject.
     */
    private int maxConnectionsPerUser = 10;

    /**
     * Maximum concurrent SSE connections permitted per parcel aggregate.
     */
    private int maxConnectionsPerParcel = 5;

    /**
     * Maximum duration in seconds for an active SSE stream before graceful termination.
     */
    private long connectionTimeoutSeconds = 1800; // 30 minutes

    /**
     * Bounded in-memory event buffer per active subscriber session.
     */
    private int bufferSize = 50;

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerUser() {
        return maxConnectionsPerUser;
    }

    public void setMaxConnectionsPerUser(int maxConnectionsPerUser) {
        this.maxConnectionsPerUser = maxConnectionsPerUser;
    }

    public int getMaxConnectionsPerParcel() {
        return maxConnectionsPerParcel;
    }

    public void setMaxConnectionsPerParcel(int maxConnectionsPerParcel) {
        this.maxConnectionsPerParcel = maxConnectionsPerParcel;
    }

    public long getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }

    public void setConnectionTimeoutSeconds(long connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    private KafkaConsumerProperties kafka = new KafkaConsumerProperties();

    public KafkaConsumerProperties getKafka() {
        return kafka;
    }

    public void setKafka(KafkaConsumerProperties kafka) {
        this.kafka = kafka;
    }

    public static class KafkaConsumerProperties {
        private boolean enabled = true;
        private String groupPrefix = "tracking.";
        private String groupId = "";
        private String replicaId = "";
        private String autoOffsetReset = "latest";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getGroupPrefix() {
            return groupPrefix;
        }

        public void setGroupPrefix(String groupPrefix) {
            this.groupPrefix = groupPrefix;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getReplicaId() {
            return replicaId;
        }

        public void setReplicaId(String replicaId) {
            this.replicaId = replicaId;
        }

        public String getAutoOffsetReset() {
            return autoOffsetReset;
        }

        public void setAutoOffsetReset(String autoOffsetReset) {
            this.autoOffsetReset = autoOffsetReset;
        }
    }
}
