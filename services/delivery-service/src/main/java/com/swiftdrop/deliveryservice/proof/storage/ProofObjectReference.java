package com.swiftdrop.deliveryservice.proof.storage;

import com.swiftdrop.deliveryservice.proof.storage.exception.InvalidProofObjectException;

import java.util.regex.Pattern;

/**
 * Opaque, application-owned reference to one private proof object.
 *
 * <p>The value is deliberately not a URL and conveys no authorization. Callers must complete
 * parcel, tenant, role, and assignment authorization before invoking the storage port.</p>
 */
public record ProofObjectReference(String value) {

    private static final Pattern FORMAT = Pattern.compile(
            "proofs/v1/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    public ProofObjectReference {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidProofObjectException("Invalid proof object reference");
        }
    }
}
