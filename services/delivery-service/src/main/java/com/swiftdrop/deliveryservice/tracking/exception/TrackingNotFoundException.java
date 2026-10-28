package com.swiftdrop.deliveryservice.tracking.exception;

/**
 * Thrown when a well-formed tracking number cannot be resolved to an active parcel record.
 */
public class TrackingNotFoundException extends RuntimeException {

    private final String trackingNumber;

    public TrackingNotFoundException(String trackingNumber) {
        super("Tracking reference '" + trackingNumber + "' was not found.");
        this.trackingNumber = trackingNumber;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }
}
