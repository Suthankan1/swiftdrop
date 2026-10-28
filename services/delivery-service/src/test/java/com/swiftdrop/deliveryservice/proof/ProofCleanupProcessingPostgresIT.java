package com.swiftdrop.deliveryservice.proof;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectUpload;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = {com.swiftdrop.deliveryservice.DeliveryServiceApplication.class,
                ProofCleanupProcessingPostgresIT.StorageTestConfiguration.class}
)
class ProofCleanupProcessingPostgresIT {

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
        registry.add("swiftdrop.outbox.relay.enabled", () -> "false");
    }

    @TestConfiguration
    static class StorageTestConfiguration {
        @Bean
        @Primary
        TestProofObjectStorage testProofObjectStorage() {
            return new TestProofObjectStorage();
        }
    }

    @Autowired
    private ProofCleanupTaskRepository cleanupTaskRepository;

    @Autowired
    private ProofOfDeliveryRepository proofRepository;

    @Autowired
    private ParcelRepository parcelRepository;

    @Autowired
    private ProofCleanupTaskCoordinator coordinator;

    @Autowired
    private ProofCompensationService compensationService;

    @Autowired
    private TestProofObjectStorage storage;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        cleanupTaskRepository.deleteAll();
        storage.reset();
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    @DisplayName("REQUIRES_NEW commits a completed task even when the caller transaction rolls back")
    void completedTaskCommitsIndependentlyOfOuterRollback() {
        ProofCleanupTask task = pendingTask();
        storage.add(task.getObjectReference());
        cleanupTaskRepository.saveAndFlush(task);

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            assertThat(coordinator.processTask(task.getTaskId()))
                    .isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.COMPLETED);
            status.setRollbackOnly();
        });

        ProofCleanupTask persisted = cleanupTaskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ProofCleanupTask.STATUS_COMPLETED);
        assertThat(persisted.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("two workers serialize on the row lock and physically delete only once")
    void concurrentWorkersCannotDoubleProcessTask() throws Exception {
        ProofCleanupTask task = pendingTask();
        storage.add(task.getObjectReference());
        cleanupTaskRepository.saveAndFlush(task);
        storage.blockDeletion();

        CompletableFuture<ProofCleanupTaskCoordinator.ProcessingResult> first =
                CompletableFuture.supplyAsync(() -> coordinator.processTask(task.getTaskId()), workers);
        assertThat(storage.awaitDeleteEntered()).isTrue();
        CompletableFuture<ProofCleanupTaskCoordinator.ProcessingResult> second =
                CompletableFuture.supplyAsync(() -> coordinator.processTask(task.getTaskId()), workers);

        storage.releaseDeletion();

        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(
                        ProofCleanupTaskCoordinator.ProcessingResult.COMPLETED,
                        ProofCleanupTaskCoordinator.ProcessingResult.SKIPPED
                );
        assertThat(storage.deleteCalls(task.getObjectReference())).isEqualTo(1);
        assertThat(cleanupTaskRepository.findById(task.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(ProofCleanupTask.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("a timeout after physical deletion remains retryable and reconciles safely")
    void crashRecoveryReconcilesDeletedObject() {
        ProofCleanupTask task = pendingTask();
        storage.add(task.getObjectReference());
        storage.timeoutAfterDelete(task.getObjectReference());
        cleanupTaskRepository.saveAndFlush(task);

        assertThat(coordinator.processTask(task.getTaskId()))
                .isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.RETRYABLE_FAILURE);
        ProofCleanupTask afterTimeout = cleanupTaskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(afterTimeout.getRetryCount()).isEqualTo(1);
        assertThat(afterTimeout.getStatus()).isEqualTo(ProofCleanupTask.STATUS_PENDING);

        storage.clearTimeoutAfterDelete(task.getObjectReference());
        assertThat(coordinator.processTask(task.getTaskId()))
                .isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.COMPLETED);
        ProofCleanupTask reconciled = cleanupTaskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(reconciled.getRetryCount()).isEqualTo(1);
        assertThat(reconciled.getStatus()).isEqualTo(ProofCleanupTask.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("batch orchestration has no stale persistence context and counts completion once")
    void batchDoesNotOverwriteCoordinatorCommit() {
        ProofCleanupTask task = pendingTask();
        storage.add(task.getObjectReference());
        cleanupTaskRepository.saveAndFlush(task);

        assertThat(compensationService.processPendingCleanups()).isEqualTo(1);
        assertThat(compensationService.processPendingCleanups()).isZero();

        ProofCleanupTask persisted = cleanupTaskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ProofCleanupTask.STATUS_COMPLETED);
        assertThat(persisted.getRetryCount()).isZero();
        assertThat(storage.deleteCalls(task.getObjectReference())).isEqualTo(1);
    }

    @Test
    @DisplayName("active proof metadata blocks deletion in the locked transaction")
    void activeReferenceCannotBeDeleted() {
        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-CLEANUP-" + UUID.randomUUID().toString().substring(0, 8),
                "customer-cleanup-it",
                null,
                UUID.randomUUID(),
                "2026.1",
                1000L,
                "LKR",
                new BigDecimal("1.000")
        );
        parcel.setStatus(ParcelStatus.DELIVERED);
        parcelRepository.saveAndFlush(parcel);

        ProofCleanupTask task = pendingTask();
        storage.add(task.getObjectReference());
        proofRepository.saveAndFlush(new ProofOfDelivery(
                UUID.randomUUID(), parcelId, task.getObjectReference(), "PHOTO", "image/jpeg", 100L,
                "Recipient", "Private note", "courier-cleanup-it", Instant.now()
        ));
        cleanupTaskRepository.saveAndFlush(task);

        assertThat(coordinator.processTask(task.getTaskId()))
                .isEqualTo(ProofCleanupTaskCoordinator.ProcessingResult.TERMINAL_FAILURE);
        ProofCleanupTask persisted = cleanupTaskRepository.findById(task.getTaskId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ProofCleanupTask.STATUS_FAILED);
        assertThat(persisted.getRetryCount()).isZero();
        assertThat(storage.deleteCalls(task.getObjectReference())).isZero();
    }

    @Test
    @DisplayName("one invocation processes only the bounded first fifty pending tasks")
    void batchSelectionIsBounded() {
        List<ProofCleanupTask> tasks = new ArrayList<>();
        for (int index = 0; index < ProofCompensationService.CLEANUP_BATCH_SIZE + 5; index++) {
            ProofCleanupTask task = pendingTask();
            tasks.add(task);
            storage.add(task.getObjectReference());
        }
        cleanupTaskRepository.saveAllAndFlush(tasks);

        assertThat(compensationService.processPendingCleanups())
                .isEqualTo(ProofCompensationService.CLEANUP_BATCH_SIZE);
        assertThat(cleanupTaskRepository.findByStatus(ProofCleanupTask.STATUS_COMPLETED))
                .hasSize(ProofCompensationService.CLEANUP_BATCH_SIZE);
        assertThat(cleanupTaskRepository.findByStatus(ProofCleanupTask.STATUS_PENDING)).hasSize(5);
    }

    private ProofCleanupTask pendingTask() {
        return new ProofCleanupTask(
                UUID.randomUUID(),
                "proofs/v1/" + UUID.randomUUID(),
                "POSTGRES_IT"
        );
    }

    static final class TestProofObjectStorage implements ProofObjectStorage {
        private final Set<String> objects = ConcurrentHashMap.newKeySet();
        private final Set<String> timeoutAfterDelete = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, AtomicInteger> deleteCalls = new ConcurrentHashMap<>();
        private volatile CountDownLatch deleteEntered;
        private volatile CountDownLatch deleteRelease;

        @Override
        public StoredProofObject store(ProofObjectUpload upload) {
            byte[] bytes;
            try {
                bytes = upload.content().readAllBytes();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            ProofObjectReference reference = new ProofObjectReference("proofs/v1/" + UUID.randomUUID());
            objects.add(reference.value());
            return new StoredProofObject(reference, bytes.length, upload.contentType());
        }

        @Override
        public RetrievedProofObject retrieve(ProofObjectReference reference) {
            if (!objects.contains(reference.value())) {
                throw new ProofObjectNotFoundException("Private proof object was not found");
            }
            return new RetrievedProofObject(new ByteArrayInputStream(new byte[0]), 0L, "image/jpeg");
        }

        @Override
        public void delete(ProofObjectReference reference) {
            deleteCalls.computeIfAbsent(reference.value(), ignored -> new AtomicInteger()).incrementAndGet();
            CountDownLatch entered = deleteEntered;
            CountDownLatch release = deleteRelease;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release synthetic deletion");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Synthetic deletion interrupted", exception);
                }
            }
            if (!objects.remove(reference.value())) {
                throw new ProofObjectNotFoundException("Private proof object was not found");
            }
            if (timeoutAfterDelete.contains(reference.value())) {
                throw new ProofStorageTimeoutException("Private proof storage operation timed out", null);
            }
        }

        @Override
        public boolean supportsDeletion() {
            return true;
        }

        void add(String reference) {
            objects.add(reference);
        }

        void timeoutAfterDelete(String reference) {
            timeoutAfterDelete.add(reference);
        }

        void clearTimeoutAfterDelete(String reference) {
            timeoutAfterDelete.remove(reference);
        }

        int deleteCalls(String reference) {
            return deleteCalls.getOrDefault(reference, new AtomicInteger()).get();
        }

        void blockDeletion() {
            deleteEntered = new CountDownLatch(1);
            deleteRelease = new CountDownLatch(1);
        }

        boolean awaitDeleteEntered() throws InterruptedException {
            return deleteEntered.await(10, TimeUnit.SECONDS);
        }

        void releaseDeletion() {
            deleteRelease.countDown();
        }

        void reset() {
            objects.clear();
            timeoutAfterDelete.clear();
            deleteCalls.clear();
            deleteEntered = null;
            deleteRelease = null;
        }
    }
}
