package com.swiftdrop.deliveryservice.assignment;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;

/** PostgreSQL-safe dynamic reads for the courier workspace. */
public interface DeliveryAssignmentRepositoryCustom {

    Page<DeliveryAssignment> findFilteredActiveByCourierSub(
            String courierSub,
            Instant assignedSince,
            String trackingNumber,
            ParcelStatus status,
            Pageable pageable);

    Page<DeliveryAssignment> findCourierHistory(
            String courierSub,
            ParcelStatus status,
            Instant completedSince,
            Instant completedUntil,
            Pageable pageable);
}
