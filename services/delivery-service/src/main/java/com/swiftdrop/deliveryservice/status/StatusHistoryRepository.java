package com.swiftdrop.deliveryservice.status;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for immutable status history audit records.
 */
@Repository
public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {

    List<StatusHistory> findByParcelIdOrderByResultingVersionAsc(UUID parcelId);

    List<StatusHistory> findByParcelIdOrderByOccurredAtDesc(UUID parcelId);
}
