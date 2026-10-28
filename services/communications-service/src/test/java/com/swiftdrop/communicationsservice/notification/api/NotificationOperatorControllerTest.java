package com.swiftdrop.communicationsservice.notification.api;

import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantine;
import com.swiftdrop.communicationsservice.consumer.quarantine.PoisonEventQuarantineService;
import com.swiftdrop.communicationsservice.notification.NotificationAttempt;
import com.swiftdrop.communicationsservice.notification.NotificationAttemptRepository;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.notification.NotificationStatus;
import com.swiftdrop.communicationsservice.notification.service.NotificationReplayService;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationOperatorControllerTest {

    private MockMvc mockMvc;
    private NotificationReplayService replayService;
    private NotificationAttemptRepository attemptRepository;
    private PoisonEventQuarantineService quarantineService;

    private TestingAuthenticationToken adminAuth;
    private TestingAuthenticationToken dispatcherAuth;

    @BeforeEach
    void setUp() {
        replayService = mock(NotificationReplayService.class);
        attemptRepository = mock(NotificationAttemptRepository.class);
        quarantineService = mock(PoisonEventQuarantineService.class);

        NotificationOperatorController controller = new NotificationOperatorController(
                replayService,
                attemptRepository,
                quarantineService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        adminAuth = new TestingAuthenticationToken("admin-sub", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        adminAuth.setAuthenticated(true);

        dispatcherAuth = new TestingAuthenticationToken("dispatcher-sub", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER")));
        dispatcherAuth.setAuthenticated(true);
    }

    @Test
    @DisplayName("POST /api/v1/internal/notifications/{id}/replay returns 200 with replayed job")
    void replayNotification_whenAdmin_returns200() throws Exception {
        UUID notificationId = UUID.randomUUID();
        NotificationJob job = new NotificationJob(
                notificationId,
                UUID.randomUUID(),
                "user-123",
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sub:user-123",
                "PARCEL_DELIVERED"
        );
        job.setStatus(NotificationStatus.PENDING);
        job.setAttempts(0);
        job.setCreatedAt(Instant.now());

        when(replayService.replayNotification(
                eq(notificationId),
                eq("admin-sub"),
                eq(SwiftDropRoles.ROLE_ADMIN),
                eq("Manual replay by operator")
        )).thenReturn(job);

        String requestBody = """
                {
                    "reason": "Manual replay by operator"
                }
                """;

        mockMvc.perform(post("/api/v1/internal/notifications/{id}/replay", notificationId)
                        .principal(adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(notificationId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/internal/notifications/{id}/attempts returns attempt list")
    void getNotificationAttempts_returnsList200() throws Exception {
        UUID notificationId = UUID.randomUUID();
        NotificationAttempt attempt1 = new NotificationAttempt(
                UUID.randomUUID(),
                notificationId,
                1,
                NotificationChannel.EMAIL,
                "TRANSIENT_FAILURE",
                "SMTP_CONNECTION_TIMEOUT",
                "CONNECTION_REFUSED",
                120,
                Instant.now(),
                Instant.now()
        );

        when(attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(notificationId))
                .thenReturn(List.of(attempt1));

        mockMvc.perform(get("/api/v1/internal/notifications/{id}/attempts", notificationId)
                        .principal(dispatcherAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].outcome").value("TRANSIENT_FAILURE"))
                .andExpect(jsonPath("$[0].failureCategory").value("SMTP_CONNECTION_TIMEOUT"));
    }

    @Test
    @DisplayName("GET /api/v1/internal/quarantine/events returns quarantined list")
    void listQuarantinedEvents_returnsList200() throws Exception {
        PoisonEventQuarantine q = new PoisonEventQuarantine(
                UUID.randomUUID(),
                "communications.notifications.v1",
                "parcel.lifecycle.v1",
                2,
                1050L,
                UUID.randomUUID(),
                "PARCEL_STATUS_CHANGED",
                "1.0",
                "MALFORMED_SCHEMA",
                "Invalid field",
                "sha256digest",
                "corr-1",
                "QUARANTINED",
                Instant.now()
        );

        when(quarantineService.listQuarantinedEvents(any(PageRequest.class)))
                .thenReturn(List.of(q));

        mockMvc.perform(get("/api/v1/internal/quarantine/events")
                        .principal(adminAuth)
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].consumerGroup").value("communications.notifications.v1"))
                .andExpect(jsonPath("$[0].failureCategory").value("MALFORMED_SCHEMA"))
                .andExpect(jsonPath("$[0].status").value("QUARANTINED"));
    }

    @Test
    @DisplayName("POST /api/v1/internal/quarantine/events/{id}/replay returns reprocessed quarantine record")
    void reprocessQuarantinedEvent_whenDispatcher_returns200() throws Exception {
        UUID quarantineId = UUID.randomUUID();
        PoisonEventQuarantine q = new PoisonEventQuarantine(
                quarantineId,
                "communications.notifications.v1",
                "parcel.lifecycle.v1",
                2,
                1050L,
                UUID.randomUUID(),
                "PARCEL_STATUS_CHANGED",
                "1.0",
                "MALFORMED_SCHEMA",
                "Invalid field",
                "sha256digest",
                "corr-1",
                "REPLAYED",
                Instant.now()
        );
        q.setReplayedAt(Instant.now());
        q.setReplayReason("Reprocessed by dispatcher");

        when(quarantineService.reprocessQuarantinedEvent(
                eq(quarantineId),
                eq("dispatcher-sub"),
                eq(SwiftDropRoles.ROLE_DISPATCHER),
                eq("Reprocessed by dispatcher"),
                eq("{\"eventId\":\"valid\"}")
        )).thenReturn(q);

        String requestBody = """
                {
                    "reason": "Reprocessed by dispatcher",
                    "correctedPayloadJson": "{\\"eventId\\":\\"valid\\"}"
                }
                """;

        mockMvc.perform(post("/api/v1/internal/quarantine/events/{id}/replay", quarantineId)
                        .principal(dispatcherAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantineId").value(quarantineId.toString()))
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.replayReason").value("Reprocessed by dispatcher"));
    }
}
