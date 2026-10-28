package com.swiftdrop.communicationsservice.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationJobRepository extends JpaRepository<NotificationJob, UUID> {

    Optional<NotificationJob> findByEventIdAndRecipientSubAndChannel(UUID eventId, String recipientSub, NotificationChannel channel);

    List<NotificationJob> findByStatusAndNextAttemptAtLessThanEqual(NotificationStatus status, Instant nextAttemptAt);

    List<NotificationJob> findByParcelIdOrderByCreatedAtDesc(UUID parcelId);

    List<NotificationJob> findByRecipientSubOrderByCreatedAtDesc(String recipientSub);

    long countByStatus(NotificationStatus status);

    @Query("SELECT MIN(n.createdAt) FROM NotificationJob n WHERE n.status = :status")
    Optional<Instant> findOldestCreatedAtByStatus(@Param("status") NotificationStatus status);

    @Query("""
        SELECT n FROM NotificationJob n
        WHERE n.status = :status
          AND (n.nextAttemptAt IS NULL OR n.nextAttemptAt <= :now)
        ORDER BY n.nextAttemptAt ASC, n.createdAt ASC
    """)
    List<NotificationJob> findDueNotifications(
            @Param("status") NotificationStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE NotificationJob n
        SET n.status = :processingStatus,
            n.claimToken = :claimToken,
            n.claimedAt = :now,
            n.leaseExpiresAt = :leaseExpiresAt,
            n.updatedAt = :now
        WHERE n.notificationId = :notificationId
          AND (n.status = :pendingStatus OR (n.status = :processingStatus AND (n.leaseExpiresAt IS NULL OR n.leaseExpiresAt <= :now)))
    """)
    int claimJobWithLease(
            @Param("notificationId") UUID notificationId,
            @Param("pendingStatus") NotificationStatus pendingStatus,
            @Param("processingStatus") NotificationStatus processingStatus,
            @Param("claimToken") String claimToken,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt
    );

    @Query("""
        SELECT n FROM NotificationJob n
        WHERE n.status = :status
          AND (n.leaseExpiresAt <= :cutoff OR (n.claimedAt <= :cutoff AND n.leaseExpiresAt IS NULL))
    """)
    List<NotificationJob> findStuckProcessingJobs(
            @Param("status") NotificationStatus status,
            @Param("cutoff") Instant cutoff
    );

    @Transactional
    @Modifying
    @Query("""
        DELETE FROM NotificationJob n
        WHERE n.status IN (:terminalStatuses)
          AND n.updatedAt < :cutoff
    """)
    int deleteTerminalNotificationsOlderThan(
            @Param("terminalStatuses") Collection<NotificationStatus> terminalStatuses,
            @Param("cutoff") Instant cutoff
    );
}
