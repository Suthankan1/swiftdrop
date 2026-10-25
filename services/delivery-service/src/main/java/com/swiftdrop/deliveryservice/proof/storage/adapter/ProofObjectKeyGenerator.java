package com.swiftdrop.deliveryservice.proof.storage.adapter;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;

import java.util.UUID;

/** Generates server-owned keys without accepting filenames or personal information. */
final class ProofObjectKeyGenerator {

    ProofObjectReference generate() {
        return new ProofObjectReference("proofs/v1/" + UUID.randomUUID());
    }
}
