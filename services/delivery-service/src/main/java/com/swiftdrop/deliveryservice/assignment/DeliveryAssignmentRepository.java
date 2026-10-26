package com.swiftdrop.deliveryservice.assignment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for Delivery Courier Assignments.
 */
@Repository
public interface DeliveryAssignmentRepository extends JpaRepository<DeliveryAssignment, UUID> {

    Optional<DeliveryAssignment> findByParcelIdAndActiveTrue(UUID parcelId);

    boolean existsByParcelIdAndActiveTrue(UUID parcelId);

    boolean existsByParcelIdAndCourierSubAndActiveTrue(UUID parcelId, String courierSub);

    int countByCourierSubAndActiveTrue(String courierSub);

    List<DeliveryAssignment> findByParcelIdOrderByAssignedAtDesc(UUID parcelId);

    Page<DeliveryAssignment> findByCourierSubAndActiveTrueOrderByAssignedAtDesc(String courierSub, Pageable pageable);

    Page<DeliveryAssignment> findByCourierSubOrderByAssignedAtDesc(String courierSub, Pageable pageable);
}
