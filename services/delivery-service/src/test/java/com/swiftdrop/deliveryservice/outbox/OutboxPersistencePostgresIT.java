package com.swiftdrop.deliveryservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.deliveryservice.status.StatusTransitionService;
import com.swiftdrop.deliveryservice.status.dto.ParcelStatusChangeResponse;
import com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("delivery_db")
            .withUsername("delivery_app")
            .withPassword("delivery_secret");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("swiftdrop.outbox.relay.enabled", () -> "false"); // Disable automatic scheduled polling in test
    }

    @TestConfiguration
    static class TestAssignmentConfig {
        @Bean
        @Primary
        public CourierAssignmentContract testCourierAssignment() {
            return new CourierAssignmentContract() {
                @Override
                public boolean hasActiveAssignment(UUID parcelId, String courierSub) {
                    return true;
                }

                @Override
                public Optional<String> getActiveCourierSub(UUID parcelId) {
                    return Optional.of("courier-it-outbox");
                }

                @Override
                public int getActiveCourierWorkload(String courierSub) {
                    return 1;
                }
            };
        }
    }

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private StatusHistoryRepository statusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private StatusTransitionService statusTransitionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID parcelId;
    private final String courierSub = "courier-it-outbox";

    @BeforeEach
    void setUp() {
        parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-OUTBOX-" + UUID.randomUUID().toString().substring(0, 8),
                "sender-customer-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026.1",
                120000L,
                "LKR",
                new BigDecimal("1.200")
        );
        parcelRepository.saveAndFlush(parcel);
    }

    @Test
    @DisplayName("1. State transition atomically commits Parcel version, StatusHistory row, and OutboxEvent row")
    void testAtomicCommitThreeWay() throws Exception {
        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                "Courier received parcel from merchant warehouse.",
                null,
                null
        );

        ParcelStatusChangeResponse response = statusTransitionService.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        );

        assertThat(response.currentStatus()).isEqualTo("PICKED_UP");
        assertThat(response.stateVersion()).isEqualTo(2L);

        // Verify Parcel
        Parcel updatedParcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(updatedParcel.getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(updatedParcel.getVersion()).isEqualTo(2L);

        // Verify History
        List<StatusHistory> histories = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcelId);
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getNewStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(histories.get(0).getResultingVersion()).isEqualTo(2L);

        // Verify Outbox Event
        List<OutboxEvent> outboxEvents = outboxEventRepository.findByAggregateIdOrderByAggregateVersionAsc(parcelId);
        assertThat(outboxEvents).hasSize(1);
        OutboxEvent event = outboxEvents.get(0);
        assertThat(event.getAggregateId()).isEqualTo(parcelId);
        assertThat(event.getAggregateType()).isEqualTo("PARCEL");
        assertThat(event.getAggregateVersion()).isEqualTo(2L);
        assertThat(event.getEventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(event.getTopic()).isEqualTo("parcel.lifecycle.v1");
        assertThat(event.getPartitionKey()).isEqualTo(parcelId.toString());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(0);

        JsonNode root = objectMapper.readTree(event.getPayload());
        assertThat(root.get("aggregateVersion").asLong()).isEqualTo(2L);
        assertThat(root.get("payload").get("newStatus").asText()).isEqualTo("PICKED_UP");
    }

    @Test
    @DisplayName("2. Database constraint failure in outbox_events rolls back Parcel and StatusHistory completely")
    void testForcedRollbackAllThree() {
        // Pre-insert an outbox event with version 2 to induce unique constraint violation (uq_outbox_aggregate_version)
        OutboxEvent conflictingEvent = new OutboxEvent(
                UUID.randomUUID(),
                "PARCEL",
                parcelId,
                2L,
                "parcel.status.changed.v1",
                "1.0.0",
                "{\"conflict\":true}",
                "parcel.lifecycle.v1",
                parcelId.toString()
        );
        outboxEventRepository.saveAndFlush(conflictingEvent);

        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                "Should fail due to duplicate outbox aggregate version",
                null,
                null
        );

        // Executing updateStatus should trigger DataIntegrityViolationException on outbox save
        assertThatThrownBy(() -> statusTransitionService.updateStatus(
                parcelId,
                request,
                courierSub,
                List.of("ROLE_COURIER")
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Verify rollback: Parcel remains CREATED at version 1
        Parcel parcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(parcel.getVersion()).isEqualTo(1L);

        // Verify rollback: StatusHistory remains empty (0 rows)
        List<StatusHistory> histories = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcelId);
        assertThat(histories).isEmpty();

        // Verify outbox has only the pre-existing conflict event
        List<OutboxEvent> outboxEvents = outboxEventRepository.findByAggregateIdOrderByAggregateVersionAsc(parcelId);
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getEventId()).isEqualTo(conflictingEvent.getEventId());
    }

    @Test
    @DisplayName("3. Multi-worker concurrent claiming with FOR UPDATE SKIP LOCKED guarantees disjoint claim sets")
    void testMultiWorkerConcurrentClaiming() throws Exception {
        // Create 10 parcels and 10 matching pending events to satisfy foreign key constraint
        List<Parcel> parcels = new ArrayList<>();
        List<OutboxEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID pId = UUID.randomUUID();
            parcels.add(new Parcel(
                    pId,
                    "SWD-CONC-" + i + "-" + UUID.randomUUID().toString().substring(0, 8),
                    "sender-1",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "2026.1",
                    100000L,
                    "LKR",
                    new BigDecimal("1.000")
            ));
            events.add(new OutboxEvent(
                    UUID.randomUUID(),
                    "PARCEL",
                    pId,
                    1L,
                    "parcel.status.changed.v1",
                    "1.0.0",
                    "{}",
                    "parcel.lifecycle.v1",
                    pId.toString()
            ));
        }
        parcelRepository.saveAllAndFlush(parcels);
        outboxEventRepository.saveAllAndFlush(events);

        int workerCount = 2;
        int batchSize = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<List<UUID>>> futures = new ArrayList<>();

        for (int w = 0; w < workerCount; w++) {
            final String workerId = "worker-" + w;
            futures.add(executor.submit(() -> {
                startLatch.await();
                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                return txTemplate.execute(status -> {
                    List<OutboxEvent> claimed = outboxEventRepository.claimNextBatch(
                            Instant.now(),
                            5,
                            batchSize
                    );
                    List<UUID> ids = new ArrayList<>();
                    for (OutboxEvent e : claimed) {
                        e.setClaimedBy(workerId);
                        ids.add(e.getEventId());
                    }
                    outboxEventRepository.saveAll(claimed);
                    return ids;
                });
            }));
        }

        // Release latch to trigger concurrent execution
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        List<UUID> worker1Claims = futures.get(0).get();
        List<UUID> worker2Claims = futures.get(1).get();

        assertThat(worker1Claims).hasSize(5);
        assertThat(worker2Claims).hasSize(5);

        // Verify mutual exclusion (disjoint sets)
        Set<UUID> intersection = new HashSet<>(worker1Claims);
        intersection.retainAll(worker2Claims);
        assertThat(intersection).isEmpty();
    }

    @Test
    @DisplayName("4. Expired in-flight claim is safely reclaimed by subsequent polling")
    void testExpiredClaimReclaimed() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID expiredEventId = UUID.randomUUID();

        txTemplate.executeWithoutResult(status -> {
            OutboxEvent expiredEvent = new OutboxEvent(
                    expiredEventId,
                    "PARCEL",
                    parcelId,
                    10L,
                    "parcel.status.changed.v1",
                    "1.0.0",
                    "{}",
                    "parcel.lifecycle.v1",
                    parcelId.toString()
            );
            expiredEvent.setStatus(OutboxStatus.IN_FLIGHT);
            expiredEvent.setClaimedBy("crashed-worker-old");
            // Claim expired 10 seconds ago
            expiredEvent.setClaimExpiresAt(Instant.now().minus(Duration.ofSeconds(10)));
            outboxEventRepository.saveAndFlush(expiredEvent);
        });

        // Claiming next batch should pick up the expired IN_FLIGHT event
        List<OutboxEvent> reclaimed = txTemplate.execute(status ->
                outboxEventRepository.claimNextBatch(
                        Instant.now(),
                        5,
                        10
                )
        );

        assertThat(reclaimed).isNotNull();
        boolean found = reclaimed.stream().anyMatch(e -> e.getEventId().equals(expiredEventId));
        assertThat(found).isTrue();
    }
}
