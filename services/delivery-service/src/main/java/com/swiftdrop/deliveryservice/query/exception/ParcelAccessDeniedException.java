package com.swiftdrop.deliveryservice.query.exception;

import java.util.UUID;

/**
 * Thrown when an authenticated caller lacks object-level ownership or role authorization
 * to access the specified parcel.
 */
public class ParcelAccessDeniedException extends RuntimeException {

    private final UUID parcelId;

    public ParcelAccessDeniedException(UUID parcelId) {
        super("Access denied to parcel: " + parcelId);
        this.parcelId = parcelId;
    }

    public ParcelAccessDeniedException(UUID parcelId, String message) {
        super(message);
        this.parcelId = parcelId;
    }

    public UUID getParcelId() {
        return parcelId;
    }
}
