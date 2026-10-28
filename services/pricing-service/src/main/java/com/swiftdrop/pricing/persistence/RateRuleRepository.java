package com.swiftdrop.pricing.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RateRuleRepository extends JpaRepository<RateRuleEntity, UUID> {

    org.springframework.data.domain.Page<RateRuleEntity> findByRateVersionId(
            UUID rateVersionId, org.springframework.data.domain.Pageable pageable);

    List<RateRuleEntity> findByRateVersionId(UUID rateVersionId);

    List<RateRuleEntity> findByRateVersionIdAndPickupZoneCodeAndDropoffZoneCode(
            UUID rateVersionId, String pickupZoneCode, String dropoffZoneCode);
}
