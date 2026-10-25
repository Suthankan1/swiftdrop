package com.swiftdrop.deliveryservice.assignment;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryAssignmentPersistencePostgresIT {

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
    private DeliveryAssignmentRepository assignmentRepository;

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Verify partial unique index uq_delivery_assignments_active_parcel exists on PostgreSQL")
    void testPartialUniqueIndexExists() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'delivery_assignments' AND indexname = 'uq_delivery_assignments_active_parcel';",
                String.class
        );
        assertThat(indexes).contains("uq_delivery_assignments_active_parcel");

        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_delivery_assignments_active_parcel';",
                String.class
        );
        assertThat(indexDef).containsIgnoringCase("UNIQUE INDEX");
        assertThat(indexDef).containsIgnoringCase("WHERE (active = true)");
    }

    @Test
    @DisplayName("Persist active assignment and retrieve via repository")
    void testPersistAndRetrieveActiveAssignment() {
        UUID parcelId = createTestParcel();
        UUID assignmentId = UUID.randomUUID();
        String courierSub = "courier-sub-101";
        String dispatcherSub = "dispatcher-sub-001";

        DeliveryAssignment assignment = new DeliveryAssignment(
                assignmentId,
                parcelId,
                courierSub,
                dispatcherSub,
                Instant.now(),
                null,
                true
        );

        assignmentRepository.saveAndFlush(assignment);

        Optional<DeliveryAssignment> activeOpt = assignmentRepository.findByParcelIdAndActiveTrue(parcelId);
        assertThat(activeOpt).isPresent();
        assertThat(activeOpt.get().getAssignmentId()).isEqualTo(assignmentId);
        assertThat(activeOpt.get().getCourierSub()).isEqualTo(courierSub);
        assertThat(activeOpt.get().isActive()).isTrue();

        assertThat(assignmentRepository.existsByParcelIdAndActiveTrue(parcelId)).isTrue();
        assertThat(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, courierSub)).isTrue();
        assertThat(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, "other-courier")).isFalse();
    }

    @Test
    @DisplayName("Enforce single active assignment invariant: second active assignment throws DataIntegrityViolationException")
    void testSingleActiveAssignmentUniqueConstraintViolation() {
        UUID parcelId = createTestParcel();

        DeliveryAssignment firstAssignment = new DeliveryAssignment(
                UUID.randomUUID(),
                parcelId,
                "courier-alpha",
                "dispatcher-1",
                Instant.now(),
                null,
                true
        );
        assignmentRepository.saveAndFlush(firstAssignment);

        DeliveryAssignment secondAssignment = new DeliveryAssignment(
                UUID.randomUUID(),
                parcelId,
                "courier-beta",
                "dispatcher-2",
                Instant.now(),
                null,
                true
        );

        assertThatThrownBy(() -> assignmentRepository.saveAndFlush(secondAssignment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Historical retention: deactivating an assignment allows new active assignment, retaining both in history")
    void testHistoricalAssignmentRetention() {
        UUID parcelId = createTestParcel();

        // 1. First assignment
        DeliveryAssignment first = new DeliveryAssignment(
                UUID.randomUUID(),
                parcelId,
                "courier-original",
                "dispatcher-1",
                Instant.now().minusSeconds(3600),
                null,
                true
        );
        assignmentRepository.saveAndFlush(first);

        // 2. Deactivate first assignment (e.g. reassignment workflow)
        first.setActive(false);
        first.setEndedAt(Instant.now());
        assignmentRepository.saveAndFlush(first);

        // 3. Second assignment can now be activated for the same parcel
        DeliveryAssignment second = new DeliveryAssignment(
                UUID.randomUUID(),
                parcelId,
                "courier-replacement",
                "dispatcher-2",
                Instant.now(),
                null,
                true
        );
        assignmentRepository.saveAndFlush(second);

        // Verify state
        Optional<DeliveryAssignment> currentActive = assignmentRepository.findByParcelIdAndActiveTrue(parcelId);
        assertThat(currentActive).isPresent();
        assertThat(currentActive.get().getCourierSub()).isEqualTo("courier-replacement");

        // Verify history retains both records ordered by assigned_at desc
        List<DeliveryAssignment> history = assignmentRepository.findByParcelIdOrderByAssignedAtDesc(parcelId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getCourierSub()).isEqualTo("courier-replacement");
        assertThat(history.get(0).isActive()).isTrue();
        assertThat(history.get(1).getCourierSub()).isEqualTo("courier-original");
        assertThat(history.get(1).isActive()).isFalse();
    }

    @Test
    @DisplayName("Workload count accurately reflects only active assignments for a courier")
    void testWorkloadCount() {
        String courierSub = "courier-workload-test-" + UUID.randomUUID();

        UUID p1 = createTestParcel();
        UUID p2 = createTestParcel();
        UUID p3 = createTestParcel();

        // Active assignment on p1
        assignmentRepository.saveAndFlush(new DeliveryAssignment(
                UUID.randomUUID(), p1, courierSub, "disp", Instant.now(), null, true
        ));

        // Active assignment on p2
        assignmentRepository.saveAndFlush(new DeliveryAssignment(
                UUID.randomUUID(), p2, courierSub, "disp", Instant.now(), null, true
        ));

        // Inactive assignment on p3
        assignmentRepository.saveAndFlush(new DeliveryAssignment(
                UUID.randomUUID(), p3, courierSub, "disp", Instant.now().minusSeconds(100), Instant.now(), false
        ));

        long count = assignmentRepository.countByCourierSubAndActiveTrue(courierSub);
        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("Concurrent database insert race: exactly one wins, other fails with DataIntegrityViolationException")
    void testConcurrentAssignmentDatabaseRace() throws Exception {
        UUID parcelId = createTestParcel();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        try {
            Future<?>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures[i] = executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        TransactionTemplate tx = new TransactionTemplate(transactionManager);
                        tx.executeWithoutResult(status -> {
                            assignmentRepository.saveAndFlush(new DeliveryAssignment(
                                    UUID.randomUUID(),
                                    parcelId,
                                    "courier-" + idx,
                                    "dispatcher-" + idx,
                                    Instant.now(),
                                    null,
                                    true
                            ));
                        });
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        conflictCount.incrementAndGet();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(threads - 1);

            // Exactly 1 active assignment exists in PostgreSQL
            List<DeliveryAssignment> assignments = assignmentRepository.findByParcelIdOrderByAssignedAtDesc(parcelId);
            assertThat(assignments).hasSize(1);
            assertThat(assignments.get(0).isActive()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID createTestParcel() {
        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-ASG-" + UUID.randomUUID().toString().substring(0, 8),
                "customer-test",
                null,
                null,
                "v1",
                50000L,
                "LKR",
                new BigDecimal("1.000")
        );
        parcelRepository.saveAndFlush(parcel);
        return parcelId;
    }
}
