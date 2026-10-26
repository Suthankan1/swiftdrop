package com.swiftdrop.deliveryservice.query;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignment;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParcelReadPersistencePostgresIT {

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

    @BeforeEach
    void cleanUp() {
        assignmentRepository.deleteAll();
        historyRepository.deleteAll();
        parcelRepository.deleteAll();
    }

    @Test
    @DisplayName("Verify status history queries enforce deterministic resultingVersion order and pagination")
    void testStatusHistoryPaginationAndOrder() {
        UUID parcelId = UUID.randomUUID();
        Parcel parcel = new Parcel(
                parcelId,
                "SWD-20261025-PST001",
                "customer-sub-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "RATE-V1",
                250000L,
                "LKR",
                new BigDecimal("1.500")
        );
        parcelRepository.saveAndFlush(parcel);

        Instant now = Instant.now();
        // Insert history in non-chronological order to test DB sorting
        StatusHistory h2 = new StatusHistory(
                UUID.randomUUID(), parcelId, ParcelStatus.CREATED, ParcelStatus.PICKED_UP,
                null, "Picked up", null, "courier-sub-1", 2L, now.plusSeconds(10)
        );
        StatusHistory h1 = new StatusHistory(
                UUID.randomUUID(), parcelId, null, ParcelStatus.CREATED,
                null, "Booked", null, "customer-sub-1", 1L, now
        );
        StatusHistory h3 = new StatusHistory(
                UUID.randomUUID(), parcelId, ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT,
                null, "In transit", null, "courier-sub-1", 3L, now.plusSeconds(20)
        );

        historyRepository.save(h2);
        historyRepository.save(h1);
        historyRepository.save(h3);
        historyRepository.flush();

        Page<StatusHistory> page = historyRepository.findByParcelIdOrderByResultingVersionAsc(
                parcelId,
                PageRequest.of(0, 2)
        );

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getResultingVersion()).isEqualTo(1L);
        assertThat(page.getContent().get(1).getResultingVersion()).isEqualTo(2L);

        // Page 2
        Page<StatusHistory> page2 = historyRepository.findByParcelIdOrderByResultingVersionAsc(
                parcelId,
                PageRequest.of(1, 2)
        );
        assertThat(page2.getContent()).hasSize(1);
        assertThat(page2.getContent().get(0).getResultingVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Verify own-assignment queries filter by courier and active flag with pagination")
    void testCourierAssignmentQueries() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        parcelRepository.save(new Parcel(p1, "SWD-20261025-PST002", "c1", null, null, "V1", 100L, "LKR", null));
        parcelRepository.save(new Parcel(p2, "SWD-20261025-PST003", "c1", null, null, "V1", 100L, "LKR", null));
        parcelRepository.save(new Parcel(p3, "SWD-20261025-PST004", "c1", null, null, "V1", 100L, "LKR", null));
        parcelRepository.flush();

        String courier1 = "courier-alice";
        String courier2 = "courier-bob";
        Instant now = Instant.now();

        // Active assignment for courier1 on p1
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p1, courier1, "disp", now, null, true));
        // Ended assignment for courier1 on p2
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p2, courier1, "disp", now.minusSeconds(3600), now.minusSeconds(1800), false));
        // Active assignment for courier2 on p3
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p3, courier2, "disp", now, null, true));
        assignmentRepository.flush();

        // Courier1 active-only query
        Page<DeliveryAssignment> activePage = assignmentRepository.findByCourierSubAndActiveTrueOrderByAssignedAtDesc(
                courier1,
                PageRequest.of(0, 10)
        );
        assertThat(activePage.getTotalElements()).isEqualTo(1);
        assertThat(activePage.getContent().get(0).getParcelId()).isEqualTo(p1);

        // Courier1 all assignments query (active + historical)
        Page<DeliveryAssignment> allPage = assignmentRepository.findByCourierSubOrderByAssignedAtDesc(
                courier1,
                PageRequest.of(0, 10)
        );
        assertThat(allPage.getTotalElements()).isEqualTo(2);

        // Courier2 active query
        Page<DeliveryAssignment> courier2Page = assignmentRepository.findByCourierSubAndActiveTrueOrderByAssignedAtDesc(
                courier2,
                PageRequest.of(0, 10)
        );
        assertThat(courier2Page.getTotalElements()).isEqualTo(1);
        assertThat(courier2Page.getContent().get(0).getParcelId()).isEqualTo(p3);
    }
}
