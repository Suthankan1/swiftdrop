package com.swiftdrop.communicationsservice.consumer.quarantine;

import com.swiftdrop.communicationsservice.consumer.EventValidationException;
import com.swiftdrop.communicationsservice.consumer.NotificationSchedulingService;
import com.swiftdrop.communicationsservice.consumer.ParcelLifecycleEventParser;
import com.swiftdrop.communicationsservice.consumer.SchedulingResult;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedEvent;
import com.swiftdrop.communicationsservice.consumer.event.ParcelStatusChangedPayload;
import com.swiftdrop.communicationsservice.metrics.CommunicationsMetrics;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PoisonEventQuarantineServiceTest {

    private PoisonEventQuarantineRepository quarantineRepository;
    private ParcelLifecycleEventParser eventParser;
    private NotificationSchedulingService schedulingService;
    private CommunicationsMetrics communicationsMetrics;
    private PoisonEventQuarantineService quarantineService;

    @BeforeEach
    void setUp() {
        quarantineRepository = mock(PoisonEventQuarantineRepository.class);
        eventParser = mock(ParcelLifecycleEventParser.class);
        schedulingService = mock(NotificationSchedulingService.class);
        communicationsMetrics = mock(CommunicationsMetrics.class);

        quarantineService = new PoisonEventQuarantineService(
                quarantineRepository,
                eventParser,
                schedulingService,
                JsonMapper.builder().build()
        );
        quarantineService.setCommunicationsMetrics(communicationsMetrics);
    }

    @Test
    @DisplayName("Quarantine new poison event: hashes payload and persists quarantine record")
    void quarantine_whenNewPoisonEvent_hashesPayloadAndPersistsRecord() {
        String consumerGroup = "communications.notifications.v1";
        String topic = "parcel.lifecycle.v1";
        int partition = 2;
        long offset = 1050L;
        String payload = "{\"bad\": \"payload\"}";
        EventValidationException ex = new EventValidationException("Missing required envelope field 'eventId'");

        when(quarantineRepository.findByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(
                consumerGroup, topic, partition, offset
        )).thenReturn(Optional.empty());

        when(quarantineRepository.saveAndFlush(any(PoisonEventQuarantine.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PoisonEventQuarantine result = quarantineService.quarantine(
                consumerGroup, topic, partition, offset, "key-1", payload, ex
        );

        assertThat(result).isNotNull();
        assertThat(result.getConsumerGroup()).isEqualTo(consumerGroup);
        assertThat(result.getSourceTopic()).isEqualTo(topic);
        assertThat(result.getSourcePartition()).isEqualTo(partition);
        assertThat(result.getSourceOffset()).isEqualTo(offset);
        assertThat(result.getStatus()).isEqualTo("QUARANTINED");
        assertThat(result.getFailureCategory()).isEqualTo("INVALID_IDENTIFIER");
        assertThat(result.getPayloadDigest()).isNotEmpty();
        assertThat(result.getSafeErrorSummary()).contains("Missing required envelope field");

        verify(quarantineRepository).saveAndFlush(any(PoisonEventQuarantine.class));
        verify(communicationsMetrics).recordPoisonEventQuarantined("INVALID_IDENTIFIER");
    }

    @Test
    @DisplayName("Quarantine when record already exists: returns existing record without saving duplicate")
    void quarantine_whenExistingRecord_returnsExistingWithoutDuplicateSave() {
        String consumerGroup = "communications.notifications.v1";
        String topic = "parcel.lifecycle.v1";
        int partition = 2;
        long offset = 1050L;
        PoisonEventQuarantine existing = new PoisonEventQuarantine();
        existing.setStatus("QUARANTINED");

        when(quarantineRepository.findByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(
                consumerGroup, topic, partition, offset
        )).thenReturn(Optional.of(existing));

        PoisonEventQuarantine result = quarantineService.quarantine(
                consumerGroup, topic, partition, offset, "key-1", "bad", new EventValidationException("Error")
        );

        assertThat(result).isSameAs(existing);
        verify(quarantineRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Quarantine concurrent race condition: handles DataIntegrityViolationException safely")
    void quarantine_whenDataIntegrityViolation_handlesGracefullyAndReturnsExisting() {
        String consumerGroup = "communications.notifications.v1";
        String topic = "parcel.lifecycle.v1";
        int partition = 2;
        long offset = 1050L;
        PoisonEventQuarantine existing = new PoisonEventQuarantine();
        existing.setStatus("QUARANTINED");

        when(quarantineRepository.findByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(
                consumerGroup, topic, partition, offset
        )).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));

        when(quarantineRepository.saveAndFlush(any(PoisonEventQuarantine.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key violation"));

        PoisonEventQuarantine result = quarantineService.quarantine(
                consumerGroup, topic, partition, offset, "key-1", "bad", new EventValidationException("Error")
        );

        assertThat(result).isSameAs(existing);
    }

    @Test
    @DisplayName("Reprocess quarantined event with unauthorized role: throws SecurityException")
    void reprocessQuarantinedEvent_whenUnauthorizedRole_throwsSecurityException() {
        UUID quarantineId = UUID.randomUUID();

        assertThatThrownBy(() -> quarantineService.reprocessQuarantinedEvent(
                quarantineId, "user-123", "ROLE_CUSTOMER", "Replaying after bugfix", "{}"
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("Reprocess quarantined event with blank reason: throws IllegalArgumentException")
    void reprocessQuarantinedEvent_whenBlankReason_throwsIllegalArgumentException() {
        UUID quarantineId = UUID.randomUUID();

        assertThatThrownBy(() -> quarantineService.reprocessQuarantinedEvent(
                quarantineId, "admin-123", SwiftDropRoles.ROLE_ADMIN, "   ", "{}"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("Reprocess non-existent quarantine ID: throws IllegalArgumentException")
    void reprocessQuarantinedEvent_whenRecordNotFound_throwsIllegalArgumentException() {
        UUID quarantineId = UUID.randomUUID();

        when(quarantineRepository.findById(quarantineId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> quarantineService.reprocessQuarantinedEvent(
                quarantineId, "admin-123", SwiftDropRoles.ROLE_ADMIN, "Manual replay", "{}"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Quarantined event not found");
    }

    @Test
    @DisplayName("Reprocess event that is not QUARANTINED: throws IllegalStateException")
    void reprocessQuarantinedEvent_whenStatusNotQuarantined_throwsIllegalStateException() {
        UUID quarantineId = UUID.randomUUID();
        PoisonEventQuarantine record = new PoisonEventQuarantine();
        record.setStatus("REPLAYED");

        when(quarantineRepository.findById(quarantineId)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> quarantineService.reprocessQuarantinedEvent(
                quarantineId, "admin-123", SwiftDropRoles.ROLE_ADMIN, "Manual replay", "{}"
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot be reprocessed from status REPLAYED");
    }

    @Test
    @DisplayName("Reprocess event with invalid JSON payload: throws EventValidationException")
    void reprocessQuarantinedEvent_whenInvalidJson_throwsEventValidationException() {
        UUID quarantineId = UUID.randomUUID();
        PoisonEventQuarantine record = new PoisonEventQuarantine();
        record.setStatus("QUARANTINED");

        when(quarantineRepository.findById(quarantineId)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> quarantineService.reprocessQuarantinedEvent(
                quarantineId, "admin-123", SwiftDropRoles.ROLE_ADMIN, "Manual replay", "not-valid-json"
        )).isInstanceOf(EventValidationException.class);
    }

    @Test
    @DisplayName("Reprocess valid corrected event: successfully processes and marks status REPLAYED")
    void reprocessQuarantinedEvent_whenValidPayloadAndAuthorized_processesAndMarksReplayed() {
        UUID quarantineId = UUID.randomUUID();
        PoisonEventQuarantine record = new PoisonEventQuarantine();
        record.setStatus("QUARANTINED");

        when(quarantineRepository.findById(quarantineId)).thenReturn(Optional.of(record));
        when(quarantineRepository.save(any(PoisonEventQuarantine.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        String validJson = "{\"eventId\":\"" + eventId + "\",\"payload\":{\"parcelId\":\"" + parcelId + "\"}}";

        ParcelStatusChangedEvent parsedEvent = new ParcelStatusChangedEvent(
                eventId,
                "PARCEL_STATUS_CHANGED",
                "1.0",
                parcelId,
                "PARCEL",
                1L,
                Instant.now(),
                "corr-1",
                "delivery-service",
                new ParcelStatusChangedPayload(
                        parcelId,
                        "TRK-12345",
                        "IN_TRANSIT",
                        "DELIVERED",
                        "Delivered safely",
                        UUID.randomUUID(),
                        "courier-1",
                        "Zone-A"
                )
        );

        when(eventParser.parseAndValidate(eq(parcelId.toString()), eq(validJson)))
                .thenReturn(parsedEvent);

        when(schedulingService.processLifecycleEvent(parsedEvent))
                .thenReturn(new SchedulingResult(eventId, false, true));

        PoisonEventQuarantine reprocessed = quarantineService.reprocessQuarantinedEvent(
                quarantineId,
                "admin-sub-1",
                SwiftDropRoles.ROLE_ADMIN,
                "Operator resolved schema defect",
                validJson
        );

        assertThat(reprocessed.getStatus()).isEqualTo("REPLAYED");
        assertThat(reprocessed.getReplayedByActorSub()).isEqualTo("admin-sub-1");
        assertThat(reprocessed.getReplayReason()).isEqualTo("Operator resolved schema defect");
        assertThat(reprocessed.getReplayedAt()).isNotNull();

        verify(schedulingService).processLifecycleEvent(parsedEvent);
        verify(communicationsMetrics).recordNotificationReplay("succeeded");
    }
}
