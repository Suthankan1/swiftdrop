package com.swiftdrop.accountsservice.staff;

import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffProfileRepository extends Repository<StaffProfile, UUID>, JpaSpecificationExecutor<StaffProfile> {

    Page<StaffProfile> findAll(Pageable pageable);

    Optional<StaffProfile> findById(UUID id);

    Optional<StaffProfile> findByKeycloakSub(String keycloakSub);

    Optional<StaffProfile> findByStaffId(String staffId);

    boolean existsByKeycloakSub(String keycloakSub);

    boolean existsByStaffId(String staffId);

    List<StaffProfile> findByRole(StaffRole role);

    List<StaffProfile> findByRoleAndAssignedZone(StaffRole role, String assignedZone);

    StaffProfile save(StaffProfile staffProfile);
}
