package com.swiftdrop.communicationsservice.notification.api;

import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantine;
import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantineService;
import com.swiftdrop.communicationsservice.notification.NotificationAttempt;
import com.swiftdrop.communicationsservice.notification.NotificationAttemptRepository;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.dto.NotificationAttemptResponse;
import com.swiftdrop.communicationsservice.notification.dto.NotificationJobResponse;
import com.swiftdrop.communicationsservice.notification.dto.PoisonEventQuarantineResponse;
import com.swiftdrop.communicationsservice.notification.dto.ReplayNotificationRequest;
import com.swiftdrop.communicationsservice.notification.dto.ReprocessQuarantineEventRequest;
import com.swiftdrop.communicationsservice.notification.service.NotificationReplayService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal operator endpoints for notification replay inspection, attempt history audit,
 * and quarantined poison-event management (Issue #117).
 *
 * Invariant:
 * Strictly restricted to authenticated operator roles (ADMIN, DISPATCHER).
 */
@RestController
@RequestMapping("/api/v1/internal")
public class NotificationOperatorController {

    private final NotificationReplayService replayService;
    private final NotificationAttemptRepository attemptRepository;
    private final PoisonEventQuarantineService quarantineService;

    @Autowired
    public NotificationOperatorController(
            NotificationReplayService replayService,
            NotificationAttemptRepository attemptRepository,
            PoisonEventQuarantineService quarantineService) {
        this.replayService = Objects.requireNonNull(replayService, "replayService cannot be null");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository cannot be null");
        this.quarantineService = Objects.requireNonNull(quarantineService, "quarantineService cannot be null");
    }

    /**
     * POST /api/v1/internal/notifications/{notificationId}/replay
     * Operator-inspected replay of a terminally failed or exhausted notification job.
     */
    @PostMapping("/notifications/{notificationId}/replay")
    public NotificationJobResponse replayNotification(
            @PathVariable UUID notificationId,
            @Valid @RequestBody ReplayNotificationRequest request,
            Authentication authentication) {
        String actorSub = extractSubject(authentication);
        String actorRole = extractPrimaryRole(authentication);

        NotificationJob replayed = replayService.replayNotification(
                notificationId,
                actorSub,
                actorRole,
                request.reason()
        );

        return toJobResponse(replayed);
    }

    /**
     * GET /api/v1/internal/notifications/{notificationId}/attempts
     * Retrieves durable attempt history and sanitized vendor error outcomes for a notification.
     */
    @GetMapping("/notifications/{notificationId}/attempts")
    public List<NotificationAttemptResponse> getNotificationAttempts(
            @PathVariable UUID notificationId,
            Authentication authentication) {
        extractSubject(authentication); // Verify authenticated
        List<NotificationAttempt> attempts = attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(notificationId);
        return attempts.stream().map(this::toAttemptResponse).toList();
    }

    /**
     * GET /api/v1/internal/quarantine/events
     * Lists quarantined poison events for operator inspection.
     */
    @GetMapping("/quarantine/events")
    public List<PoisonEventQuarantineResponse> listQuarantinedEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        extractSubject(authentication);
        int boundedSize = Math.min(Math.max(1, size), 100);
        int boundedPage = Math.max(0, page);
        List<PoisonEventQuarantine> list = quarantineService.listQuarantinedEvents(PageRequest.of(boundedPage, boundedSize));
        return list.stream().map(this::toQuarantineResponse).toList();
    }

    /**
     * POST /api/v1/internal/quarantine/events/{quarantineId}/replay
     * Reprocesses a quarantined event after defect correction.
     */
    @PostMapping("/quarantine/events/{quarantineId}/replay")
    public PoisonEventQuarantineResponse reprocessQuarantinedEvent(
            @PathVariable UUID quarantineId,
            @Valid @RequestBody ReprocessQuarantineEventRequest request,
            Authentication authentication) {
        String actorSub = extractSubject(authentication);
        String actorRole = extractPrimaryRole(authentication);

        PoisonEventQuarantine reprocessed = quarantineService.reprocessQuarantinedEvent(
                quarantineId,
                actorSub,
                actorRole,
                request.reason(),
                request.correctedPayloadJson()
        );

        return toQuarantineResponse(reprocessed);
    }

    private String extractSubject(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return authentication.getName();
    }

    private String extractPrimaryRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return "";
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        for (GrantedAuthority ga : authorities) {
            String role = ga.getAuthority();
            if (role.startsWith("ROLE_")) {
                role = role.substring(5);
            }
            if ("ADMIN".equalsIgnoreCase(role) || "DISPATCHER".equalsIgnoreCase(role)) {
                return role.toUpperCase();
            }
        }
        // Fallback to first role
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
                .findFirst()
                .orElse("");
    }

    private NotificationJobResponse toJobResponse(NotificationJob job) {
        return new NotificationJobResponse(
                job.getNotificationId(),
                job.getEventId(),
                job.getParcelId(),
                job.getChannel() != null ? job.getChannel().name() : null,
                job.getStatus() != null ? job.getStatus().name() : null,
                job.getAttempts() != null ? job.getAttempts() : 0,
                job.getNextAttemptAt(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getLastFailureCategory()
        );
    }

    private NotificationAttemptResponse toAttemptResponse(NotificationAttempt attempt) {
        return new NotificationAttemptResponse(
                attempt.getAttemptId(),
                attempt.getNotificationId(),
                attempt.getAttemptNumber(),
                attempt.getChannel() != null ? attempt.getChannel().name() : null,
                attempt.getOutcome(),
                attempt.getFailureCategory(),
                attempt.getSafeErrorCode(),
                attempt.getDurationMs(),
                attempt.getAttemptedAt()
        );
    }

    private PoisonEventQuarantineResponse toQuarantineResponse(PoisonEventQuarantine q) {
        return new PoisonEventQuarantineResponse(
                q.getQuarantineId(),
                q.getConsumerGroup(),
                q.getSourceTopic(),
                q.getSourcePartition(),
                q.getSourceOffset(),
                q.getEventIdIfValid(),
                q.getEventTypeIfValid(),
                q.getFailureCategory(),
                q.getSafeErrorSummary(),
                q.getPayloadDigest(),
                q.getStatus(),
                q.getQuarantinedAt(),
                q.getReplayedAt(),
                q.getReplayReason()
        );
    }
}
