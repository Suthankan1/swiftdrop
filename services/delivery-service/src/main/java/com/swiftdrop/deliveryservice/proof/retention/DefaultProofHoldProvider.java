package com.swiftdrop.deliveryservice.proof.retention;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default hold provider implementation.
 *
 * <p>CRITICAL PRIVACY & RETENTION SAFETY INVARIANT:
 * In production, without an approved, durable, authoritative legal hold integration,
 * this provider MUST fail closed. Therefore, it defaults to {@code authoritative = false},
 * which always returns {@link Optional#empty()} (unknown hold status).</p>
 *
 * <p>When hold status is unknown, {@link ProofRetentionEvaluator} strictly rejects deletion.
 * In-memory hold registrations are strictly non-authoritative in production and reserved for
 * testing fixtures or explicitly verified runtime contexts.</p>
 */
@Component
public class DefaultProofHoldProvider implements ProofHoldProvider {

    private final Set<UUID> heldParcelIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> heldProofIds = ConcurrentHashMap.newKeySet();

    /**
     * Defaults to false so fresh application startup or restarts fail closed
     * rather than erroneously assuming absence of holds.
     */
    private volatile boolean authoritative = false;

    public DefaultProofHoldProvider() {
        this(false);
    }

    public DefaultProofHoldProvider(boolean authoritative) {
        this.authoritative = authoritative;
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

    public boolean isAuthoritative() {
        return authoritative;
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
