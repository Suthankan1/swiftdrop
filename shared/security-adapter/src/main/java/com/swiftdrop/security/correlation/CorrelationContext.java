package com.swiftdrop.security.correlation;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Thread-isolated correlation context providing distributed request tracing across
 * HTTP REST, gRPC boundaries, and asynchronous execution threads.
 *
 * Enforces the bounded safe-character policy (alphanumeric, dash, underscore, 1-64 characters)
 * matching the established SwiftDrop gRPC baseline and event contract DEC-EVT-03.
 *
 * Strictly prevents log/CRLF injection, header spoofing, and static state leaks between threads.
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID_HEADER = "X-SwiftDrop-Correlation-ID";
    public static final String MDC_KEY_CORRELATION_ID = "correlationId";
    public static final int MAX_CORRELATION_ID_LENGTH = 64;

    private static final Pattern SAFE_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final ThreadLocal<String> CURRENT_CORRELATION_ID = new ThreadLocal<>();

    private CorrelationContext() {
    }

    /**
     * Retrieves the current thread's active correlation identifier, if present.
     */
    public static Optional<String> get() {
        return Optional.ofNullable(CURRENT_CORRELATION_ID.get());
    }

    /**
     * Binds a correlation identifier to the current thread and SLF4J MDC.
     * Automatically sanitizes unsafe identifiers and falls back to a fresh UUID.
     *
     * @param rawCorrelationId potentially untrusted incoming correlation ID
     * @return the safe, normalized correlation ID that was bound
     */
    public static String set(String rawCorrelationId) {
        String safeId = sanitizeOrGenerate(rawCorrelationId);
        CURRENT_CORRELATION_ID.set(safeId);
        MDC.put(MDC_KEY_CORRELATION_ID, safeId);
        return safeId;
    }

    /**
     * Clears the current thread's correlation context and SLF4J MDC entry.
     * MUST be called in a finally block to prevent thread pool leaks.
     */
    public static void clear() {
        CURRENT_CORRELATION_ID.remove();
        MDC.remove(MDC_KEY_CORRELATION_ID);
    }

    /**
     * Sanitizes an incoming correlation identifier.
     * Returns the trimmed value if it matches the safe-character policy and length constraint;
     * otherwise generates a secure random UUID string.
     *
     * @param raw incoming correlation identifier
     * @return safe, normalized correlation identifier
     */
    public static String sanitizeOrGenerate(String raw) {
        if (raw == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_CORRELATION_ID_LENGTH) {
            return UUID.randomUUID().toString();
        }
        if (SAFE_CORRELATION_ID_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Executes a supplier function within the scope of a specific correlation ID,
     * restoring any previous correlation ID upon completion.
     */
    public static <T> T executeWith(String correlationId, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier cannot be null");
        String previous = CURRENT_CORRELATION_ID.get();
        try {
            set(correlationId);
            return supplier.get();
        } finally {
            if (previous != null) {
                set(previous);
            } else {
                clear();
            }
        }
    }

    /**
     * Executes a runnable action within the scope of a specific correlation ID,
     * restoring any previous correlation ID upon completion.
     */
    public static void runWith(String correlationId, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable cannot be null");
        String previous = CURRENT_CORRELATION_ID.get();
        try {
            set(correlationId);
            runnable.run();
        } finally {
            if (previous != null) {
                set(previous);
            } else {
                clear();
            }
        }
    }
}
