package com.swiftdrop.deliveryservice.assignment;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
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

    Page<DeliveryAssignment> findByCourierSubAndActiveTrueAndAssignedAtGreaterThanEqualOrderByAssignedAtDesc(
            String courierSub, java.time.Instant since, Pageable pageable);

    Page<DeliveryAssignment> findByCourierSubAndAssignedAtGreaterThanEqualOrderByAssignedAtDesc(
            String courierSub, java.time.Instant since, Pageable pageable);

    Page<DeliveryAssignment> findByActiveTrue(Pageable pageable);

    List<DeliveryAssignment> findByParcelIdInAndActiveTrue(java.util.Collection<UUID> parcelIds);

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
          AND da.active = true
          AND (:assignedSince IS NULL OR da.assignedAt >= :assignedSince)
          AND (
            :trackingNumber IS NULL OR EXISTS (
              SELECT p.parcelId FROM Parcel p
              WHERE p.parcelId = da.parcelId
                AND LOWER(p.trackingNumber) LIKE LOWER(CONCAT('%', :trackingNumber, '%'))
            )
          )
          AND (
            :status IS NULL OR EXISTS (
              SELECT p2.parcelId FROM Parcel p2
              WHERE p2.parcelId = da.parcelId
                AND p2.status = :status
            )
          )
        ORDER BY da.assignedAt DESC, da.assignmentId ASC
    """)
    Page<DeliveryAssignment> findFilteredActiveByCourierSub(
            @org.springframework.data.repository.query.Param("courierSub") String courierSub,
            @org.springframework.data.repository.query.Param("assignedSince") java.time.Instant assignedSince,
            @org.springframework.data.repository.query.Param("trackingNumber") String trackingNumber,
            @org.springframework.data.repository.query.Param("status") ParcelStatus status,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        SELECT da FROM DeliveryAssignment da
        WHERE da.courierSub = :courierSub
          AND EXISTS (
            SELECT sh.historyId FROM com.swiftdrop.deliveryservice.status.StatusHistory sh
            WHERE sh.parcelId = da.parcelId
              AND sh.actorSub = da.courierSub
              AND sh.newStatus IN (
                com.swiftdrop.deliveryservice.core.ParcelStatus.DELIVERED,
                com.swiftdrop.deliveryservice.core.ParcelStatus.FAILED
              )
              AND sh.occurredAt >= da.assignedAt
              AND (da.endedAt IS NULL OR sh.occurredAt <= da.endedAt)
              AND (:status IS NULL OR sh.newStatus = :status)
              AND (:completedSince IS NULL OR sh.occurredAt >= :completedSince)
              AND (:completedUntil IS NULL OR sh.occurredAt < :completedUntil)
          )
        ORDER BY da.endedAt DESC, da.assignedAt DESC, da.assignmentId ASC
    """)
    Page<DeliveryAssignment> findCourierHistory(
            @org.springframework.data.repository.query.Param("courierSub") String courierSub,
            @org.springframework.data.repository.query.Param("status") ParcelStatus status,
            @org.springframework.data.repository.query.Param("completedSince") java.time.Instant completedSince,
            @org.springframework.data.repository.query.Param("completedUntil") java.time.Instant completedUntil,
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
