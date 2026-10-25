package com.swiftdrop.deliveryservice.core;

import java.util.UUID;

/**
 * Thrown when a requested parcel cannot be found by its UUID or tracking number.
 */
public class ParcelNotFoundException extends RuntimeException {

    private final String identifier;

    public ParcelNotFoundException(UUID parcelId) {
        super("Parcel not found with id: " + parcelId);
        this.identifier = parcelId != null ? parcelId.toString() : "null";
    }

    public ParcelNotFoundException(String trackingNumber) {
        super("Parcel not found with tracking number: " + trackingNumber);
        this.identifier = trackingNumber;
    }

    public String getIdentifier() {
        return identifier;
    }
}
