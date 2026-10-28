package com.swiftdrop.deliveryservice.assignment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for Delivery Assignment Audit events.
 */
@Repository
public interface DeliveryAssignmentAuditRepository extends JpaRepository<DeliveryAssignmentAudit, UUID> {

    List<DeliveryAssignmentAudit> findByParcelIdOrderByOccurredAtDesc(UUID parcelId);

    List<DeliveryAssignmentAudit> findByParcelIdOrderByOccurredAtAsc(UUID parcelId);

    List<DeliveryAssignmentAudit> findByAssignmentIdOrderByOccurredAtDesc(UUID assignmentId);

    List<DeliveryAssignmentAudit> findByCourierSubOrderByOccurredAtDesc(String courierSub);
}
