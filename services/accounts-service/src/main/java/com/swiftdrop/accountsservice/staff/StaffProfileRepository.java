package com.swiftdrop.accountsservice.staff;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffProfileRepository extends Repository<StaffProfile, UUID> {

    Optional<StaffProfile> findById(UUID id);

    Optional<StaffProfile> findByKeycloakSub(String keycloakSub);

    Optional<StaffProfile> findByStaffId(String staffId);

    boolean existsByKeycloakSub(String keycloakSub);

    boolean existsByStaffId(String staffId);

    List<StaffProfile> findByRole(StaffRole role);

    StaffProfile save(StaffProfile staffProfile);
}
