package com.swiftdrop.apigateway.filter.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe, in-memory token bucket rate limiter for Spring Cloud Gateway WebFlux.
 * Enforces per-instance bounded rate quotas without external distributed cache dependencies.
 *
 * Employs bounded cardinality defense (max 10,000 tracked keys) to prevent memory exhaustion
 * from randomized attacker IP or key attacks.
 */
@Component
public class InMemoryRateLimiter {

    private static final int MAX_BUCKETS = 10_000;
    private static final long CLEANUP_INTERVAL_NANOS = 60_000_000_000L; // 60 seconds

    public record ConsumptionResult(boolean allowed, long retryAfterSeconds) {
        public static ConsumptionResult allow() {
            return new ConsumptionResult(true, 0);
        }

        public static ConsumptionResult reject(long retryAfterSeconds) {
            return new ConsumptionResult(false, Math.max(1, retryAfterSeconds));
        }
    }

    private static class TokenBucket {
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessEpochMillis;

        TokenBucket(double initialTokens, long nowNanos) {
            this.tokens = initialTokens;
            this.lastRefillNanos = nowNanos;
            this.lastAccessEpochMillis = System.currentTimeMillis();
        }

        synchronized ConsumptionResult tryConsume(int requested, long capacity, double tokensPerMinute, long nowNanos) {
            this.lastAccessEpochMillis = System.currentTimeMillis();
            double tokensPerNano = (tokensPerMinute / 60.0) / 1_000_000_000.0;
            long elapsedNanos = Math.max(0, nowNanos - lastRefillNanos);
            tokens = Math.min(capacity, tokens + (elapsedNanos * tokensPerNano));
            lastRefillNanos = nowNanos;

            if (tokens >= requested) {
                tokens -= requested;
                return ConsumptionResult.allow();
            }

            double missingTokens = requested - tokens;
            long nanosNeeded = (long) Math.ceil(missingTokens / tokensPerNano);
            long secondsNeeded = (nanosNeeded / 1_000_000_000L) + 1;
            return ConsumptionResult.reject(secondsNeeded);
        }

        long getLastAccessEpochMillis() {
            return lastAccessEpochMillis;
        }
    }

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger opCounter = new AtomicInteger(0);
    private volatile long lastCleanupNanos = System.nanoTime();

    public ConsumptionResult tryConsume(String key, int tokens, long capacity, double tokensPerMinute) {
        if (capacity <= 0 || tokensPerMinute <= 0) {
            return ConsumptionResult.allow();
        }

        maybePruneBuckets();

        // Guard against unbounded bucket cardinality
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(key)) {
            // Prune aggressively
            pruneExpiredBuckets(CLEANUP_INTERVAL_NANOS / 2);
            if (buckets.size() >= MAX_BUCKETS) {
                // If still saturated, reject non-existing keys to protect memory
                return ConsumptionResult.reject(60);
            }
        }

        long nowNanos = System.nanoTime();
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, nowNanos));
        return bucket.tryConsume(tokens, capacity, tokensPerMinute, nowNanos);
    }

    public void clear() {
        buckets.clear();
    }

    public int size() {
        return buckets.size();
    }

    private void maybePruneBuckets() {
        if (opCounter.incrementAndGet() % 1000 == 0) {
            long now = System.nanoTime();
            if (now - lastCleanupNanos > CLEANUP_INTERVAL_NANOS) {
                lastCleanupNanos = now;
                pruneExpiredBuckets(CLEANUP_INTERVAL_NANOS * 5); // 5 minutes inactivity
            }
        }
    }

    private void pruneExpiredBuckets(long maxIdleNanos) {
        long idleThresholdMillis = System.currentTimeMillis() - (maxIdleNanos / 1_000_000L);
        buckets.entrySet().removeIf(entry -> entry.getValue().getLastAccessEpochMillis() < idleThresholdMillis);
    }
}
