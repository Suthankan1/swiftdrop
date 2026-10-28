package com.swiftdrop.communicationsservice.notification.service;

import com.swiftdrop.communicationsservice.client.AccountsRecipientContactClient;
import com.swiftdrop.communicationsservice.client.DeliveryAuthorizationException;
import com.swiftdrop.communicationsservice.client.DeliveryEventAuthorization;
import com.swiftdrop.communicationsservice.client.DeliveryRecipientAuthorizationClient;
import com.swiftdrop.communicationsservice.client.PermittedRecipientContact;
import com.swiftdrop.communicationsservice.consumer.NoEligibleNotificationTargetException;
import com.swiftdrop.communicationsservice.consumer.NotificationSchedulingService;
import com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationJobRepository;
import com.swiftdrop.communicationsservice.notification.NotificationReplayAudit;
import com.swiftdrop.communicationsservice.notification.NotificationReplayAuditRepository;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.notification.exception.InvalidNotificationReplayException;
import com.swiftdrop.communicationsservice.notification.exception.NotificationNotFoundException;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing authorized, audited replay operations for failed or exhausted notification jobs (Issue #117).
 *
 * Invariants:
 * 1. Only authorized operational roles (ADMIN, DISPATCHER) can initiate replays.
 * 2. Only jobs in terminal states (FAILED, EXHAUSTED) can be replayed.
 * 3. Re-validates current recipient eligibility and Delivery authorization prior to rescheduling.
 * 4. Preserves the original canonical eventId, parcelId, and notification lineage.
 * 5. Records immutable audit history in notification_replays.
 */
@Service
public class NotificationReplayService {

    private static final Logger log = LoggerFactory.getLogger(NotificationReplayService.class);

    private final NotificationJobRepository notificationJobRepository;
    private final NotificationReplayAuditRepository replayAuditRepository;
    private final AccountsRecipientContactClient accountsContactClient;
    private final DeliveryRecipientAuthorizationClient deliveryAuthorizationClient;

    @Autowired(required = false)
    private CommunicationsMetrics communicationsMetrics;

    public void setCommunicationsMetrics(CommunicationsMetrics communicationsMetrics) {
        this.communicationsMetrics = communicationsMetrics;
    }

    public NotificationReplayService(
            NotificationJobRepository notificationJobRepository,
            NotificationReplayAuditRepository replayAuditRepository,
            AccountsRecipientContactClient accountsContactClient,
            DeliveryRecipientAuthorizationClient deliveryAuthorizationClient) {
        this.notificationJobRepository = Objects.requireNonNull(notificationJobRepository, "notificationJobRepository cannot be null");
        this.replayAuditRepository = Objects.requireNonNull(replayAuditRepository, "replayAuditRepository cannot be null");
        this.accountsContactClient = Objects.requireNonNull(accountsContactClient, "accountsContactClient cannot be null");
        this.deliveryAuthorizationClient = Objects.requireNonNull(deliveryAuthorizationClient, "deliveryAuthorizationClient cannot be null");
    }

    /**
     * Authorizes, inspects, and schedules a replay of a terminally failed notification job.
     *
     * @param notificationId ID of the notification to replay
     * @param actorSub authenticated operator subject
     * @param actorRole authenticated operator role
     * @param reason non-blank operator reason
     * @return updated NotificationJob in PENDING state
     */
    @Transactional
    public NotificationJob replayNotification(
            UUID notificationId,
            String actorSub,
            String actorRole,
            String reason) {

        if (actorSub == null || actorSub.isBlank()) {
            throw new SecurityException("Authentication required: actor subject is missing.");
        }
        if (!SwiftDropRoles.ROLE_ADMIN.equals(actorRole) && !SwiftDropRoles.ROLE_DISPATCHER.equals(actorRole)) {
            if (communicationsMetrics != null) {
                communicationsMetrics.recordNotificationReplay("rejected");
            }
            throw new SecurityException("Role " + actorRole + " is not authorized to replay notifications.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Replay reason must not be blank.");
        }

        NotificationJob job = notificationJobRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException("Notification job not found: " + notificationId));

        NotificationStatus currentStatus = job.getStatus();
        if (currentStatus != NotificationStatus.FAILED && currentStatus != NotificationStatus.EXHAUSTED) {
            if (communicationsMetrics != null) {
                communicationsMetrics.recordNotificationReplay("rejected");
            }
            throw new InvalidNotificationReplayException(
                    "Notification " + notificationId + " cannot be replayed from status " + currentStatus +
                    ". Only FAILED or EXHAUSTED jobs can be replayed.");
        }

        Instant now = Instant.now();

        // 1. Re-validate recipient contact policy with Accounts Service
        Optional<PermittedRecipientContact> contactOpt = accountsContactClient.resolveContact(
                job.getRecipientSub(),
                NotificationSchedulingService.NOTIFICATION_PURPOSE,
                job.getChannel()
        );

        if (contactOpt.isEmpty() || !contactOpt.get().isEligible() || !contactOpt.get().hasPermittedEmail()) {
            String rejectReason = contactOpt.map(PermittedRecipientContact::rejectionReason).orElse("Recipient not found");
            recordAudit(job, actorSub, actorRole, reason, currentStatus, "REJECTED", now);
            if (communicationsMetrics != null) {
                communicationsMetrics.recordNotificationReplay("rejected");
            }
            throw new NoEligibleNotificationTargetException("Replay rejected: recipient is ineligible: " + rejectReason);
        }

        // 2. Re-validate event-to-recipient authorization with Delivery Operations
        DeliveryEventAuthorization auth = deliveryAuthorizationClient.authorizeEventRecipient(
                job.getEventId(),
                job.getParcelId(),
                null,
                null,
                false
        );

        if (!auth.isAuthorized()) {
            recordAudit(job, actorSub, actorRole, reason, currentStatus, "REJECTED", now);
            if (communicationsMetrics != null) {
                communicationsMetrics.recordNotificationReplay("rejected");
            }
            throw new DeliveryAuthorizationException("Replay rejected: Delivery authorization failed: " + auth.authorizationReason());
        }

        // 3. Record accepted audit
        recordAudit(job, actorSub, actorRole, reason, currentStatus, "ACCEPTED", now);

        // 4. Reset job to PENDING with attempt count 0 for immediate retry eligibility
        job.setStatus(NotificationStatus.PENDING);
        job.setAttempts(0);
        job.setNextAttemptAt(now);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        job.setUpdatedAt(now);

        NotificationJob savedJob = notificationJobRepository.save(job);
        log.info("Authorized notification replay scheduled [notificationId={}, actorSub={}, actorRole={}, reason={}]",
                notificationId, actorSub, actorRole, reason.trim());

        if (communicationsMetrics != null) {
            communicationsMetrics.recordNotificationReplay("succeeded");
        }

        return savedJob;
    }

    private void recordAudit(NotificationJob job, String actorSub, String actorRole,
                             String reason, NotificationStatus previousStatus, String decision, Instant now) {
        NotificationReplayAudit audit = new NotificationReplayAudit(
                UUID.randomUUID(),
                job.getNotificationId(),
                job.getEventId(),
                actorSub,
                actorRole,
                reason.trim(),
                previousStatus.name(),
                decision,
                now
        );
        replayAuditRepository.save(audit);
    }
}
