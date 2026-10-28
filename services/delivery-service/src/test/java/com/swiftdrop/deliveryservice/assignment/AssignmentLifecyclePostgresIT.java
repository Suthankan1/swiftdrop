package com.swiftdrop.deliveryservice.assignment;

import com.swiftdrop.deliveryservice.assignment.dto.AssignCourierRequest;
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentAuditResponse;
import com.swiftdrop.deliveryservice.assignment.dto.AssignmentResponse;
import com.swiftdrop.deliveryservice.assignment.dto.CloseAssignmentRequest;
import com.swiftdrop.deliveryservice.assignment.dto.ReassignCourierRequest;
import com.swiftdrop.deliveryservice.assignment.exception.AssignmentConflictException;
import com.swiftdrop.deliveryservice.assignment.exception.IneligibleCourierException;
import com.swiftdrop.deliveryservice.assignment.exception.InvalidParcelStateException;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.StatusTransitionService;
import com.swiftdrop.deliveryservice.status.dto.UpdateStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
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
class AssignmentLifecyclePostgresIT {

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
        registry.add("swiftdrop.accounts.grpc.enabled", () -> "false");
    }

    public static class TestCourierEligibilityClient implements CourierEligibilityClient {
        private volatile boolean throwException = false;
        private volatile boolean eligible = true;
        private volatile String rejectionReason = null;

        public void setThrowException(boolean throwException) {
            this.throwException = throwException;
        }

        public void setEligible(boolean eligible, String rejectionReason) {
            this.eligible = eligible;
            this.rejectionReason = rejectionReason;
        }

        public void reset() {
            this.throwException = false;
            this.eligible = true;
            this.rejectionReason = null;
        }

        @Override
        public CourierEligibilityResult checkEligibility(String courierSub) {
            if (throwException) {
                throw new RuntimeException("Simulated Accounts gRPC DEADLINE_EXCEEDED / UNAVAILABLE");
            }
            if (!eligible) {
                return new CourierEligibilityResult(courierSub, false, false, rejectionReason != null ? rejectionReason : "INELIGIBLE");
            }
            return new CourierEligibilityResult(courierSub, true, true, null);
        }
    }

    @TestConfiguration
    static class TestEligibilityConfig {
        @Bean
        @Primary
        public TestCourierEligibilityClient testCourierEligibilityClient() {
            return new TestCourierEligibilityClient();
        }
    }

    @Autowired
    private DeliveryAssignmentService assignmentService;

    @Autowired
    private StatusTransitionService statusTransitionService;

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private DeliveryAssignmentRepository assignmentRepository;

    @Autowired
    private DeliveryAssignmentAuditRepository auditRepository;

    @Autowired
    private TestCourierEligibilityClient eligibilityClient;

    private static final List<String> DISPATCHER_ROLES = List.of("ROLE_DISPATCHER");
    private static final List<String> COURIER_ROLES = List.of("ROLE_COURIER");
    private static final String DISPATCHER_SUB = "dispatcher-test-sub";

    @BeforeEach
    void resetClient() {
        eligibilityClient.reset();
    }

    private Parcel createParcel() {
        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-IT-" + UUID.randomUUID().toString().substring(0, 8),
                "sender-customer-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026.1",
                100000L,
                "LKR",
                new BigDecimal("1.500")
        );
        return parcelRepository.saveAndFlush(parcel);
    }

    @Test
    @DisplayName("Scenario A: Concurrent reassignments with same expectedVersion - exactly one succeeds, loser fails with conflict")
    void testConcurrentReassignments() throws Exception {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // 1. Initial assignment (expectedVersion = 1) -> resulting version is 2
        AssignmentResponse initialAssignment = assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-initial", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );
        assertThat(initialAssignment.newParcelVersion()).isEqualTo(2L);

        // 2. Concurrently reassign to courier-A and courier-B with expectedVersion = 2L
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Future<?> f1 = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                assignmentService.reassignCourier(
                        new ReassignCourierRequest(parcelId, "courier-replacement-A", 2L, null, "Reassign to A"),
                        "dispatcher-1",
                        DISPATCHER_ROLES
                );
                successCount.incrementAndGet();
            } catch (AssignmentConflictException | OptimisticLockingFailureException | org.springframework.dao.DataIntegrityViolationException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                if (e.getCause() instanceof AssignmentConflictException
                        || e.getCause() instanceof OptimisticLockingFailureException
                        || e.getCause() instanceof org.springframework.dao.DataIntegrityViolationException) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(e);
                }
            }
        });

        Future<?> f2 = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                assignmentService.reassignCourier(
                        new ReassignCourierRequest(parcelId, "courier-replacement-B", 2L, null, "Reassign to B"),
                        "dispatcher-2",
                        DISPATCHER_ROLES
                );
                successCount.incrementAndGet();
            } catch (AssignmentConflictException | OptimisticLockingFailureException | org.springframework.dao.DataIntegrityViolationException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                if (e.getCause() instanceof AssignmentConflictException
                        || e.getCause() instanceof OptimisticLockingFailureException
                        || e.getCause() instanceof org.springframework.dao.DataIntegrityViolationException) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(e);
                }
            }
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Exactly one winner, one conflict
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Verify single active assignment in PostgreSQL
        List<DeliveryAssignment> activeAssignments = assignmentRepository.findByParcelIdOrderByAssignedAtDesc(parcelId)
                .stream()
                .filter(DeliveryAssignment::isActive)
                .toList();
        assertThat(activeAssignments).hasSize(1);
        String activeCourier = activeAssignments.get(0).getCourierSub();
        assertThat(activeCourier).isIn("courier-replacement-A", "courier-replacement-B");

        // Verify audit trail contains exactly 1 ASSIGNED and 1 REASSIGNED
        List<DeliveryAssignmentAudit> audits = auditRepository.findByParcelIdOrderByOccurredAtAsc(parcelId);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).getAction()).isEqualTo("ASSIGNED");
        assertThat(audits.get(0).getCourierSub()).isEqualTo("courier-initial");
        assertThat(audits.get(1).getAction()).isEqualTo("REASSIGNED");
        assertThat(audits.get(1).getCourierSub()).isEqualTo(activeCourier);
        assertThat(audits.get(1).getPreviousCourierSub()).isEqualTo("courier-initial");
    }

    @Test
    @DisplayName("Scenario B: Reassignment versus pickup race - serializes cleanly, no inconsistent state")
    void testReassignmentVsPickupRace() throws Exception {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // Initial assignment to courier-original
        assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-original", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger reassignmentSuccess = new AtomicInteger(0);
        AtomicInteger pickupSuccess = new AtomicInteger(0);

        // Thread 1: Dispatcher reassigns to courier-replacement (expectedVersion = 2)
        Future<?> f1 = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                assignmentService.reassignCourier(
                        new ReassignCourierRequest(parcelId, "courier-replacement", 2L, null, "Dispatcher reassignment"),
                        "dispatcher-1",
                        DISPATCHER_ROLES
                );
                reassignmentSuccess.incrementAndGet();
            } catch (Exception ignored) {
            }
        });

        // Thread 2: Courier attempts pickup (expectedVersion = 2)
        Future<?> f2 = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                statusTransitionService.updateStatus(
                        parcelId,
                        new UpdateStatusRequest(ParcelStatus.PICKED_UP, 2L, "Picked up", null, null),
                        "courier-original",
                        COURIER_ROLES
                );
                pickupSuccess.incrementAndGet();
            } catch (Exception ignored) {
            }
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Exactly one should succeed since both required version 2 and parcel state CREATED
        int totalSuccess = reassignmentSuccess.get() + pickupSuccess.get();
        assertThat(totalSuccess).isEqualTo(1);

        Parcel currentParcel = parcelRepository.findById(parcelId).orElseThrow();
        if (reassignmentSuccess.get() == 1) {
            // Reassignment won: parcel still CREATED, version is 3, active courier is courier-replacement
            assertThat(currentParcel.getStatus()).isEqualTo(ParcelStatus.CREATED);
            assertThat(currentParcel.getVersion()).isEqualTo(3L);
            assertThat(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, "courier-replacement")).isTrue();
            assertThat(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, "courier-original")).isFalse();
        } else {
            // Pickup won: parcel is PICKED_UP, version is 3, active courier is courier-original
            assertThat(currentParcel.getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
            assertThat(currentParcel.getVersion()).isEqualTo(3L);
            assertThat(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, "courier-original")).isTrue();
        }
    }

    @Test
    @DisplayName("Scenario C: Reassignment on terminal FAILED parcel is rejected, and active assignment was closed")
    void testReassignmentOnTerminalFailedParcelRejected() {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // 1. Initial assignment
        assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-initial", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // 2. Transition parcel to FAILED (terminal transition closes active assignment atomically)
        statusTransitionService.updateStatus(
                parcelId,
                new UpdateStatusRequest(ParcelStatus.FAILED, 2L, "Cancelled by sender", "CANCELLED_BY_SENDER", null),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // Verify assignment was automatically closed
        Optional<DeliveryAssignment> activeOpt = assignmentRepository.findByParcelIdAndActiveTrue(parcelId);
        assertThat(activeOpt).isEmpty();

        // Verify TERMINATED audit event recorded
        List<DeliveryAssignmentAudit> audits = auditRepository.findByParcelIdOrderByOccurredAtAsc(parcelId);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1).getAction()).isEqualTo("TERMINATED");
        assertThat(audits.get(1).getCourierSub()).isEqualTo("courier-initial");

        // 3. Attempting reassignment on FAILED parcel must be rejected
        Parcel failedParcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThatThrownBy(() -> assignmentService.reassignCourier(
                new ReassignCourierRequest(parcelId, "courier-replacement", failedParcel.getVersion(), null, "Attempting reassignment"),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        )).isInstanceOf(InvalidParcelStateException.class)
          .hasMessageContaining("FAILED")
          .hasMessageContaining("CREATED");

        // DB remains unchanged
        assertThat(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).isEmpty();
    }

    @Test
    @DisplayName("Scenario D: Concurrent closure with same expectedVersion - exactly one succeeds, loser receives conflict")
    void testConcurrentClosure() throws Exception {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // Initial assignment (version becomes 2)
        assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-to-close", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Future<?> f1 = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                assignmentService.closeAssignment(
                        new CloseAssignmentRequest(parcelId, 2L, null, "Dispatcher close 1"),
                        "dispatcher-1",
                        DISPATCHER_ROLES
                );
                successCount.incrementAndGet();
            } catch (AssignmentConflictException | OptimisticLockingFailureException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                if (e.getCause() instanceof AssignmentConflictException || e.getCause() instanceof OptimisticLockingFailureException) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(e);
                }
            }
        });

        Future<?> f2 = executor.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                assignmentService.closeAssignment(
                        new CloseAssignmentRequest(parcelId, 2L, null, "Dispatcher close 2"),
                        "dispatcher-2",
                        DISPATCHER_ROLES
                );
                successCount.incrementAndGet();
            } catch (AssignmentConflictException | OptimisticLockingFailureException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                if (e.getCause() instanceof AssignmentConflictException || e.getCause() instanceof OptimisticLockingFailureException) {
                    conflictCount.incrementAndGet();
                } else {
                    throw new RuntimeException(e);
                }
            }
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Assignment is no longer active
        assertThat(assignmentRepository.findByParcelIdAndActiveTrue(parcelId)).isEmpty();

        // Audit trail has exactly 1 ASSIGNED and 1 CLOSED
        List<DeliveryAssignmentAudit> audits = auditRepository.findByParcelIdOrderByOccurredAtAsc(parcelId);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).getAction()).isEqualTo("ASSIGNED");
        assertThat(audits.get(1).getAction()).isEqualTo("CLOSED");
        assertThat(audits.get(1).getCourierSub()).isEqualTo("courier-to-close");
    }

    @Test
    @DisplayName("Scenario E: Accounts eligibility provider failure - rolls back cleanly, original assignment untouched")
    void testEligibilityProviderFailureRollsBackCleanly() {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // Initial assignment
        assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-stable", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // Configure eligibility provider to simulate gRPC failure
        eligibilityClient.setThrowException(true);

        assertThatThrownBy(() -> assignmentService.reassignCourier(
                new ReassignCourierRequest(parcelId, "courier-replacement", 2L, null, "Attempting reassignment with failed gRPC"),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        )).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("DEADLINE_EXCEEDED");

        // Verify original assignment is still active and unchanged
        Optional<DeliveryAssignment> activeOpt = assignmentRepository.findByParcelIdAndActiveTrue(parcelId);
        assertThat(activeOpt).isPresent();
        assertThat(activeOpt.get().getCourierSub()).isEqualTo("courier-stable");
        assertThat(activeOpt.get().isActive()).isTrue();

        // Verify no orphan audit records
        List<DeliveryAssignmentAudit> audits = auditRepository.findByParcelIdOrderByOccurredAtAsc(parcelId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getAction()).isEqualTo("ASSIGNED");
        assertThat(audits.get(0).getCourierSub()).isEqualTo("courier-stable");

        // Parcel version remains 2
        Parcel currentParcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(currentParcel.getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Scenario F: Reassignment atomicity on ineligibility rejection - rolls back, leaves original assignment active")
    void testReassignmentAtomicityOnIneligibleCourier() {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // Initial assignment
        assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-alpha", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // Set replacement courier as ineligible
        eligibilityClient.setEligible(false, "COURIER_SUSPENDED");

        assertThatThrownBy(() -> assignmentService.reassignCourier(
                new ReassignCourierRequest(parcelId, "courier-beta", 2L, null, "Reassignment to suspended courier"),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        )).isInstanceOf(IneligibleCourierException.class)
          .hasMessageContaining("COURIER_SUSPENDED");

        // Original courier-alpha is still active
        assertThat(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(parcelId, "courier-alpha")).isTrue();

        // Audit log only has initial assignment
        List<DeliveryAssignmentAudit> audits = auditRepository.findByParcelIdOrderByOccurredAtAsc(parcelId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getAction()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("Scenario G: Lost-response retry with stale version receives 409 conflict, no duplicate assignment")
    void testLostResponseRetryWithStaleVersionFailsWithConflict() {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // 1. Initial assignment -> version 2
        assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-1", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // 2. Reassignment with expectedVersion = 2 -> version becomes 3
        AssignmentResponse response = assignmentService.reassignCourier(
                new ReassignCourierRequest(parcelId, "courier-2", 2L, null, "First reassignment"),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );
        assertThat(response.newParcelVersion()).isEqualTo(3L);

        // 3. Client retries with stale expectedVersion = 2L
        assertThatThrownBy(() -> assignmentService.reassignCourier(
                new ReassignCourierRequest(parcelId, "courier-2", 2L, null, "Retry first reassignment"),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        )).isInstanceOf(AssignmentConflictException.class)
          .hasMessageContaining("Optimistic lock failure: expected parcel version 2 but was 3");

        // Verify single active assignment exists (courier-2)
        List<DeliveryAssignment> activeAssignments = assignmentRepository.findByParcelIdOrderByAssignedAtDesc(parcelId)
                .stream().filter(DeliveryAssignment::isActive).toList();
        assertThat(activeAssignments).hasSize(1);
        assertThat(activeAssignments.get(0).getCourierSub()).isEqualTo("courier-2");

        // Audit trail has exactly ASSIGNED and REASSIGNED
        List<DeliveryAssignmentAudit> audits = auditRepository.findByParcelIdOrderByOccurredAtAsc(parcelId);
        assertThat(audits).hasSize(2);
    }

    @Test
    @DisplayName("Audit History Read: Authorized dispatcher can retrieve audit history in chronological order")
    void testAuditHistoryRetrieval() {
        Parcel parcel = createParcel();
        UUID parcelId = parcel.getParcelId();

        // 1. Assign
        assignmentService.assignCourier(
                new AssignCourierRequest(parcelId, "courier-1", 1L),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // 2. Reassign
        assignmentService.reassignCourier(
                new ReassignCourierRequest(parcelId, "courier-2", 2L, null, "Reassign reason"),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // 3. Close
        assignmentService.closeAssignment(
                new CloseAssignmentRequest(parcelId, 3L, null, "Close reason"),
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        List<AssignmentAuditResponse> history = assignmentService.getAssignmentAuditHistory(
                parcelId,
                DISPATCHER_SUB,
                DISPATCHER_ROLES
        );

        // History is ordered DESC (latest first)
        assertThat(history).hasSize(3);
        assertThat(history.get(0).action()).isEqualTo("CLOSED");
        assertThat(history.get(0).courierSub()).isEqualTo("courier-2");
        assertThat(history.get(1).action()).isEqualTo("REASSIGNED");
        assertThat(history.get(1).courierSub()).isEqualTo("courier-2");
        assertThat(history.get(1).previousCourierSub()).isEqualTo("courier-1");
        assertThat(history.get(2).action()).isEqualTo("ASSIGNED");
        assertThat(history.get(2).courierSub()).isEqualTo("courier-1");
    }
}

