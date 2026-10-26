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

    @org.springframework.data.jpa.repository.Query("""
        SELECT da.courierSub, COUNT(da)
        FROM DeliveryAssignment da
        WHERE da.courierSub IN :courierSubs AND da.active = true
        GROUP BY da.courierSub
    """)
    List<Object[]> countActiveAssignmentsByCourierSubs(
            @org.springframework.data.repository.query.Param("courierSubs") java.util.Collection<String> courierSubs);

    List<DeliveryAssignment> findByCourierSubAndActiveTrueAndParcelIdIn(String courierSub, java.util.Collection<UUID> parcelIds);

    List<DeliveryAssignment> findByCourierSubAndParcelIdIn(String courierSub, java.util.Collection<UUID> parcelIds);

    @org.springframework.data.jpa.repository.Query("""
        SELECT da FROM DeliveryAssignment da
        WHERE da.courierSub = :courierSub AND da.active = true
        ORDER BY da.assignedAt DESC, da.assignmentId ASC
    """)
    Page<DeliveryAssignment> findActiveByCourierSub(
            @org.springframework.data.repository.query.Param("courierSub") String courierSub,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT da FROM DeliveryAssignment da
        WHERE da.courierSub = :courierSub
        ORDER BY da.assignedAt DESC, da.assignmentId ASC
    """)
    Page<DeliveryAssignment> findAllByCourierSub(
            @org.springframework.data.repository.query.Param("courierSub") String courierSub,
            Pageable pageable);
}
