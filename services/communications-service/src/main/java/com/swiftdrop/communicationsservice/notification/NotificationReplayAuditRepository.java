package com.swiftdrop.communicationsservice.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationReplayAuditRepository extends JpaRepository<NotificationReplayAudit, UUID> {

    List<NotificationReplayAudit> findByNotificationIdOrderByReplayedAtDesc(UUID notificationId);

    List<NotificationReplayAudit> findByOriginalEventIdOrderByReplayedAtDesc(UUID originalEventId);
}
