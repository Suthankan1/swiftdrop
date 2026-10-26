package com.swiftdrop.pricing.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ZoneRepository extends JpaRepository<ZoneEntity, UUID> {

    Optional<ZoneEntity> findByZoneCode(String zoneCode);

    List<ZoneEntity> findByActiveTrueOrderByZoneCodeAsc();

    boolean existsByZoneCode(String zoneCode);
}
