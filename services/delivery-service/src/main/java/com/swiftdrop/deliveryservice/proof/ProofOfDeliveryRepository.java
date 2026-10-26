package com.swiftdrop.deliveryservice.proof;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProofOfDeliveryRepository extends JpaRepository<ProofOfDelivery, UUID> {

    Optional<ProofOfDelivery> findByParcelId(UUID parcelId);
}
