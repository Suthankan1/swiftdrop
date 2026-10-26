package com.swiftdrop.deliveryservice.proof;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of ProofMetadataContract for parcel lifecycle validation.
 */
@Service
public class ProofMetadataService implements ProofMetadataContract {

    private final ProofOfDeliveryRepository proofRepository;

    public ProofMetadataService(ProofOfDeliveryRepository proofRepository) {
        this.proofRepository = proofRepository;
    }

    @Override
    public boolean isProofValid(UUID parcelId, UUID proofId) {
        if (parcelId == null || proofId == null) {
            return false;
        }
        return proofRepository.findByParcelId(parcelId)
                .map(proof -> Objects.equals(proof.getProofId(), proofId))
                .orElse(false);
    }
}
