package com.swiftdrop.deliveryservice.query;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignment;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.outbox.OutboxEventRepository;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryConcurrencyProjectionsIT {

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
    }

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private StatusHistoryRepository historyRepository;

    @Autowired
    private DeliveryAssignmentRepository assignmentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void cleanUp() {
        assignmentRepository.deleteAll();
        historyRepository.deleteAll();
        outboxEventRepository.deleteAll();
        parcelRepository.deleteAll();
    }

    @Test
    @DisplayName("Concurrent assignment writes during queue reads maintain query safety and zero side effects")
    void testConcurrentAssignmentWritesDuringQueueReads() throws Exception {
        int initialParcels = 30;
        List<UUID> parcelIds = new ArrayList<>();
        Instant baseTime = Instant.now().minusSeconds(3600);

        for (int i = 0; i < initialParcels; i++) {
            UUID id = UUID.randomUUID();
            parcelIds.add(id);
            Parcel p = new Parcel(id, "SWD-CONC-" + i, "sender", null, null, "v1", 1000L, "LKR", new BigDecimal("1.5"));
            p.setCreatedAt(baseTime.plusSeconds(i));
            parcelRepository.save(p);
        }
        parcelRepository.flush();

        long outboxCountBefore = outboxEventRepository.count();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        int readTasks = 20;
        int writeTasks = 10;

        List<Future<Integer>> readFutures = new ArrayList<>();
        List<Future<Boolean>> writeFutures = new ArrayList<>();

        // Reader tasks: repeatedly query unassigned queue
        for (int i = 0; i < readTasks; i++) {
            readFutures.add(executor.submit(() -> {
                startLatch.await();
                Page<Parcel> page = parcelRepository.findUnassignedParcels(PageRequest.of(0, 50));
                return page.getNumberOfElements();
            }));
        }

        // Writer tasks: assign some parcels concurrently
        for (int i = 0; i < writeTasks; i++) {
            final UUID targetParcelId = parcelIds.get(i);
            final int index = i;
            writeFutures.add(executor.submit(() -> {
                startLatch.await();
                DeliveryAssignment assignment = new DeliveryAssignment(
                        UUID.randomUUID(), targetParcelId, "courier-" + (index % 3), "dispatcher", Instant.now(), null, true
                );
                assignmentRepository.saveAndFlush(assignment);
                return true;
            }));
        }

        startLatch.countDown();

        for (Future<Integer> f : readFutures) {
            Integer count = f.get(5, TimeUnit.SECONDS);
            // Must see between 20 (all writes completed) and 30 (no writes completed)
            assertThat(count).isBetween(20, 30);
        }

        for (Future<Boolean> f : writeFutures) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isTrue();
        }

        executor.shutdown();

        // Verify read queries caused zero side-effects:
        // No extra outbox events created by reads
        long outboxCountAfter = outboxEventRepository.count();
        assertThat(outboxCountAfter).isEqualTo(outboxCountBefore);

        // Final unassigned queue should have exactly 20 parcels remaining
        Page<Parcel> finalQueue = parcelRepository.findUnassignedParcels(PageRequest.of(0, 50));
        assertThat(finalQueue.getTotalElements()).isEqualTo(20);
    }

    @Test
    @DisplayName("Concurrent assignment completion during workload aggregation produces consistent counts")
    void testConcurrentAssignmentCompletionDuringWorkloadAggregation() throws Exception {
        String courier = "courier-concurrent";
        List<UUID> parcelIds = new ArrayList<>();
        List<DeliveryAssignment> assignments = new ArrayList<>();
        Instant now = Instant.now();

        for (int i = 0; i < 5; i++) {
            UUID pId = UUID.randomUUID();
            parcelIds.add(pId);
            parcelRepository.save(new Parcel(pId, "SWD-WL-" + i, "s", null, null, "v1", 100L, "LKR", null));

            DeliveryAssignment da = new DeliveryAssignment(
                    UUID.randomUUID(), pId, courier, "disp", now.minusSeconds(100 + i), null, true
            );
            assignments.add(da);
            assignmentRepository.save(da);
        }
        parcelRepository.flush();
        assignmentRepository.flush();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        // Task 1: end 2 assignments
        Future<Boolean> endFuture = executor.submit(() -> {
            latch.await();
            DeliveryAssignment da0 = assignments.get(0);
            da0.setActive(false);
            da0.setEndedAt(Instant.now());
            assignmentRepository.save(da0);

            DeliveryAssignment da1 = assignments.get(1);
            da1.setActive(false);
            da1.setEndedAt(Instant.now());
            assignmentRepository.saveAndFlush(da1);
            return true;
        });

        // Task 2: query workload
        Future<Integer> countFuture = executor.submit(() -> {
            latch.await();
            return assignmentRepository.countByCourierSubAndActiveTrue(courier);
        });

        latch.countDown();

        assertThat(endFuture.get(5, TimeUnit.SECONDS)).isTrue();
        Integer observedCount = countFuture.get(5, TimeUnit.SECONDS);

        // Under READ_COMMITTED, the workload query either observes the pre-completion count (5) or post-completion count (3)
        assertThat(observedCount).isIn(3, 4, 5);

        executor.shutdown();

        // Final query must be exactly 3
        int finalCount = assignmentRepository.countByCourierSubAndActiveTrue(courier);
        assertThat(finalCount).isEqualTo(3);
    }
}
