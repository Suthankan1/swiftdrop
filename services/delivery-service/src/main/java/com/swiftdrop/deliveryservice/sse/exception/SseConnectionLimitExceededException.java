package com.swiftdrop.deliveryservice.sse.exception;

/**
 * Thrown when an SSE connection cannot be established because global, per-user,
 * or per-parcel concurrency limits have been exceeded.
 */
public class SseConnectionLimitExceededException extends RuntimeException {

    private final String limitType;
    private final int maxAllowed;

    public SseConnectionLimitExceededException(String limitType, int maxAllowed, String message) {
        super(message);
        this.limitType = limitType;
        this.maxAllowed = maxAllowed;
    }

    public String getLimitType() {
        return limitType;
    }

    public int getMaxAllowed() {
        return maxAllowed;
    }
}
