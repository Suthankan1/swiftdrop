package com.swiftdrop.deliveryservice.proof.exception;

import java.util.UUID;

public class ProofNotFoundException extends RuntimeException {

    public ProofNotFoundException(UUID parcelId) {
        super("Proof not found for parcel " + parcelId);
    }

    public ProofNotFoundException(String message) {
        super(message);
    }
}
