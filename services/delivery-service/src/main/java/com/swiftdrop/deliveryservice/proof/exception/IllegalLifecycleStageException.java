package com.swiftdrop.deliveryservice.proof.exception;

import com.swiftdrop.deliveryservice.core.ParcelStatus;

public class IllegalLifecycleStageException extends RuntimeException {

    public IllegalLifecycleStageException(ParcelStatus status) {
        super("Cannot submit or modify delivery proof when parcel is in stage " + status);
    }

    public IllegalLifecycleStageException(String message) {
        super(message);
    }
}
