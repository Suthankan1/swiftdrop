package com.swiftdrop.deliveryservice.status;

import com.swiftdrop.deliveryservice.assignment.CourierAssignmentContract;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatusTransitionPersistencePostgresIT {

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

    @TestConfiguration
    static class TestAssignmentConfig {
        @Bean
        @Primary
        public CourierAssignmentContract testCourierAssignment() {
            // Test fake to simulate Issue #24 courier assignment verification
            return new CourierAssignmentContract() {
                @Override
                public boolean hasActiveAssignment(UUID parcelId, String courierSub) {
                    return "assigned-courier-it".equals(courierSub);
                }

                @Override
                public Optional<String> getActiveCourierSub(UUID parcelId) {
                    return Optional.of("assigned-courier-it");
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
    private StatusTransitionService statusTransitionService;

    private UUID parcelId;
    private String trackingNumber;
    private final String assignedCourier = "assigned-courier-it";

    @BeforeEach
    void setupParcel() {
        parcelId = UUID.randomUUID();
        trackingNumber = "SWD-IT-" + UUID.randomUUID().toString().substring(0, 8);

        Parcel parcel = new Parcel(
                parcelId,
                trackingNumber,
                "sender-customer-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026.1",
                150000L,
                "LKR",
                new BigDecimal("2.500")
        );
        parcelRepository.saveAndFlush(parcel);
    }

    @Test
    @DisplayName("1. State and History commit atomically in single local database transaction")
    void testStateAndHistoryAtomicCommit() {
        UpdateStatusRequest request = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                1L,
                "Courier picked up package from sender.",
                null,
                null
        );

        ParcelStatusChangeResponse response = statusTransitionService.updateStatus(
                parcelId,
                request,
                assignedCourier,
                List.of("ROLE_COURIER")
        );

        assertThat(response.currentStatus()).isEqualTo("PICKED_UP");
        assertThat(response.stateVersion()).isEqualTo(2L);
        assertThat(response.updatedParcelVersion()).isEqualTo(2L);

        // Verify Parcel aggregate in database
        Parcel updatedParcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(updatedParcel.getStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(updatedParcel.getVersion()).isEqualTo(2L);

        // Verify StatusHistory record in database
        List<StatusHistory> historyList = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcelId);
        assertThat(historyList).hasSize(1);
        StatusHistory history = historyList.get(0);
        assertThat(history.getPreviousStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(history.getNewStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(history.getActorSub()).isEqualTo(assignedCourier);
        assertThat(history.getResultingVersion()).isEqualTo(2L);
        assertThat(history.getNote()).isEqualTo("Courier picked up package from sender.");
    }

    @Test
    @DisplayName("2. Exception triggers full rollback: neither state nor history are persisted")
    void testFullRollbackOnException() {
        // Attempt illegal transition CREATED -> IN_TRANSIT
        UpdateStatusRequest invalidRequest = new UpdateStatusRequest(
                ParcelStatus.IN_TRANSIT,
                1L,
                "Illegal skipping of PICKED_UP stage",
                null,
                null
        );

        assertThatThrownBy(() -> statusTransitionService.updateStatus(
                parcelId,
                invalidRequest,
                assignedCourier,
                List.of("ROLE_COURIER")
        )).isInstanceOf(IllegalTransitionException.class);

        // Verify Parcel unchanged in database
        Parcel parcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(parcel.getVersion()).isEqualTo(1L);

        // Verify zero history rows inserted
        List<StatusHistory> historyList = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcelId);
        assertThat(historyList).isEmpty();
    }

    @Test
    @DisplayName("3. Stale expectedVersion aborts transaction with VersionConflictException and no history")
    void testStaleExpectedVersionRollback() {
        UpdateStatusRequest staleRequest = new UpdateStatusRequest(
                ParcelStatus.PICKED_UP,
                99L, // wrong version
                "Stale client request",
                null,
                null
        );

        assertThatThrownBy(() -> statusTransitionService.updateStatus(
                parcelId,
                staleRequest,
                assignedCourier,
                List.of("ROLE_COURIER")
        )).isInstanceOf(VersionConflictException.class);

        Parcel parcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.CREATED);
        assertThat(parcel.getVersion()).isEqualTo(1L);

        List<StatusHistory> historyList = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcelId);
        assertThat(historyList).isEmpty();
    }

    @Test
    @DisplayName("4. Sequential permitted transitions append chronological immutable history records")
    void testSequentialTransitionsAppendHistory() {
        // Step 1: CREATED -> PICKED_UP (version 1 -> 2)
        statusTransitionService.updateStatus(
                parcelId,
                new UpdateStatusRequest(ParcelStatus.PICKED_UP, 1L, "Picked up", null, null),
                assignedCourier,
                List.of("ROLE_COURIER")
        );

        // Step 2: PICKED_UP -> IN_TRANSIT (version 2 -> 3)
        statusTransitionService.updateStatus(
                parcelId,
                new UpdateStatusRequest(ParcelStatus.IN_TRANSIT, 2L, "In transit to hub", null, null),
                assignedCourier,
                List.of("ROLE_COURIER")
        );

        // Step 3: IN_TRANSIT -> OUT_FOR_DELIVERY (version 3 -> 4)
        statusTransitionService.updateStatus(
                parcelId,
                new UpdateStatusRequest(ParcelStatus.OUT_FOR_DELIVERY, 3L, "Out for delivery", null, null),
                assignedCourier,
                List.of("ROLE_COURIER")
        );

        Parcel finalParcel = parcelRepository.findById(parcelId).orElseThrow();
        assertThat(finalParcel.getStatus()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        assertThat(finalParcel.getVersion()).isEqualTo(4L);

        List<StatusHistory> historyList = statusHistoryRepository.findByParcelIdOrderByResultingVersionAsc(parcelId);
        assertThat(historyList).hasSize(3);

        assertThat(historyList.get(0).getNewStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(historyList.get(0).getResultingVersion()).isEqualTo(2L);

        assertThat(historyList.get(1).getNewStatus()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(historyList.get(1).getResultingVersion()).isEqualTo(3L);

        assertThat(historyList.get(2).getNewStatus()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        assertThat(historyList.get(2).getResultingVersion()).isEqualTo(4L);
    }
}
