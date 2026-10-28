package com.swiftdrop.pricing.persistence;

import com.swiftdrop.pricing.domain.RateVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RateVersionRepository extends JpaRepository<RateVersionEntity, UUID> {

    boolean existsByVersionCode(String versionCode);

    Optional<RateVersionEntity> findByVersionCode(String versionCode);

    Optional<RateVersionEntity> findFirstByCurrencyAndStatus(String currency, RateVersionStatus status);

    List<RateVersionEntity> findAllByOrderByCreatedAtDesc();
}
