package com.swiftdrop.communicationsservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationJobRepository extends JpaRepository<NotificationJob, UUID> {

    Optional<NotificationJob> findByEventIdAndRecipientSubAndChannel(UUID eventId, String recipientSub, NotificationChannel channel);

    List<NotificationJob> findByStatusAndNextAttemptAtLessThanEqual(NotificationStatus status, Instant nextAttemptAt);

    List<NotificationJob> findByParcelIdOrderByCreatedAtDesc(UUID parcelId);

    List<NotificationJob> findByRecipientSubOrderByCreatedAtDesc(String recipientSub);
}
