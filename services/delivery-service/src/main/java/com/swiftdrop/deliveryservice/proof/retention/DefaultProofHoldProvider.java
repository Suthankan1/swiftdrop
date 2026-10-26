package com.swiftdrop.deliveryservice.proof.retention;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory hold provider supporting operational and legal hold registries.
 *
 * <p>When initialized without an authoritative hold registry or when disabled,
 * it returns {@code Optional.empty()} to fail closed by default.</p>
 */
@Component
public class DefaultProofHoldProvider implements ProofHoldProvider {

    private final Set<UUID> heldParcelIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> heldProofIds = ConcurrentHashMap.newKeySet();
    private volatile boolean authoritative = true;

    public DefaultProofHoldProvider() {
    }

    public void addParcelHold(UUID parcelId) {
        if (parcelId != null) {
            heldParcelIds.add(parcelId);
        }
    }

    public void removeParcelHold(UUID parcelId) {
        if (parcelId != null) {
            heldParcelIds.remove(parcelId);
        }
    }

    public void addProofHold(UUID proofId) {
        if (proofId != null) {
            heldProofIds.add(proofId);
        }
    }

    public void removeProofHold(UUID proofId) {
        if (proofId != null) {
            heldProofIds.remove(proofId);
        }
    }

    public void setAuthoritative(boolean authoritative) {
        this.authoritative = authoritative;
    }

    @Override
    public Optional<Boolean> isUnderHold(UUID parcelId, UUID proofId) {
        if (!authoritative) {
            // Cannot authoritatively determine hold status -> fail closed
            return Optional.empty();
        }
        if (parcelId == null && proofId == null) {
            return Optional.empty();
        }
        boolean held = (parcelId != null && heldParcelIds.contains(parcelId))
                || (proofId != null && heldProofIds.contains(proofId));
        return Optional.of(held);
    }
}
