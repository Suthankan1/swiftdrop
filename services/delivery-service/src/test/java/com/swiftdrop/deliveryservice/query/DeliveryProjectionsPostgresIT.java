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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryProjectionsPostgresIT {

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
    @DisplayName("findUnassignedParcels returns only CREATED parcels without active assignments, with deterministic ordering")
    void testFindUnassignedParcels() {
        UUID p1 = UUID.randomUUID(); // Unassigned, CREATED
        UUID p2 = UUID.randomUUID(); // Assigned (active = true), CREATED
        UUID p3 = UUID.randomUUID(); // Assigned in past, now ended (active = false), CREATED -> should be eligible
        UUID p4 = UUID.randomUUID(); // DELIVERED -> terminal, excluded
        UUID p5 = UUID.randomUUID(); // Unassigned, CREATED (later time)

        Instant baseTime = Instant.parse("2026-10-29T08:00:00Z");

        Parcel parcel1 = new Parcel(p1, "SWD-20261029-001", "sender-1", null, null, "v1", 1000L, "LKR", new BigDecimal("1.000"));
        parcel1.setCreatedAt(baseTime);
        parcelRepository.save(parcel1);

        Parcel parcel2 = new Parcel(p2, "SWD-20261029-002", "sender-1", null, null, "v1", 1000L, "LKR", new BigDecimal("1.000"));
        parcel2.setCreatedAt(baseTime.plusSeconds(60));
        parcelRepository.save(parcel2);

        Parcel parcel3 = new Parcel(p3, "SWD-20261029-003", "sender-1", null, null, "v1", 1000L, "LKR", new BigDecimal("1.000"));
        parcel3.setCreatedAt(baseTime.plusSeconds(120));
        parcelRepository.save(parcel3);

        Parcel parcel4 = new Parcel(p4, "SWD-20261029-004", "sender-1", null, null, "v1", 1000L, "LKR", new BigDecimal("1.000"));
        parcel4.setCreatedAt(baseTime.plusSeconds(180));
        parcel4.setStatus(ParcelStatus.DELIVERED);
        parcelRepository.save(parcel4);

        Parcel parcel5 = new Parcel(p5, "SWD-20261029-005", "sender-1", null, null, "v1", 1000L, "LKR", new BigDecimal("1.000"));
        parcel5.setCreatedAt(baseTime.plusSeconds(240));
        parcelRepository.save(parcel5);
        parcelRepository.flush();

        // Active assignment for p2
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p2, "courier-1", "disp", baseTime, null, true));
        // Ended assignment for p3
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p3, "courier-1", "disp", baseTime.minusSeconds(100), baseTime.minusSeconds(50), false));
        assignmentRepository.flush();

        Page<Parcel> unassigned = parcelRepository.findUnassignedParcels(PageRequest.of(0, 10));

        // p1, p3, and p5 should be returned (3 total). p2 (active assignment) and p4 (DELIVERED) are excluded.
        assertThat(unassigned.getTotalElements()).isEqualTo(3);
        assertThat(unassigned.getContent()).extracting(Parcel::getParcelId)
                .containsExactly(p1, p3, p5);
    }

    @Test
    @DisplayName("countActiveAssignmentsByCourierSubs groups active assignments by courier accurately")
    void testCountActiveAssignmentsByCourierSubs() {
        String courier1 = "courier-alpha";
        String courier2 = "courier-beta";
        Instant now = Instant.now();

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID p4 = UUID.randomUUID();

        parcelRepository.save(new Parcel(p1, "SWD-ASG-001", "sender", null, null, "v1", 100L, "LKR", null));
        parcelRepository.save(new Parcel(p2, "SWD-ASG-002", "sender", null, null, "v1", 100L, "LKR", null));
        parcelRepository.save(new Parcel(p3, "SWD-ASG-003", "sender", null, null, "v1", 100L, "LKR", null));
        parcelRepository.save(new Parcel(p4, "SWD-ASG-004", "sender", null, null, "v1", 100L, "LKR", null));
        parcelRepository.flush();

        // Courier 1 has 2 active and 1 ended assignment
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p1, courier1, "disp", now, null, true));
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p2, courier1, "disp", now, null, true));
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p3, courier1, "disp", now.minusSeconds(500), now.minusSeconds(200), false));

        // Courier 2 has 1 active assignment
        assignmentRepository.save(new DeliveryAssignment(UUID.randomUUID(), p4, courier2, "disp", now, null, true));
        assignmentRepository.flush();

        List<Object[]> results = assignmentRepository.countActiveAssignmentsByCourierSubs(List.of(courier1, courier2, "courier-gamma"));

        assertThat(results).hasSize(2);
        for (Object[] row : results) {
            String sub = (String) row[0];
            long count = (Long) row[1];
            if (sub.equals(courier1)) {
                assertThat(count).isEqualTo(2L);
            } else if (sub.equals(courier2)) {
                assertThat(count).isEqualTo(1L);
            }
        }
    }

    @Test
    @DisplayName("Daily outcome count and duration queries enforce half-open interval and accurate durations")
    void testDailyOutcomeQueries() {
        Instant dayStart = Instant.parse("2026-10-29T00:00:00Z");
        Instant dayEnd = Instant.parse("2026-10-30T00:00:00Z");

        UUID p1 = UUID.randomUUID(); // Created and delivered on Oct 29
        UUID p2 = UUID.randomUUID(); // Created on Oct 29, failed on Oct 29
        UUID p3 = UUID.randomUUID(); // Created on Oct 29, in transit
        UUID p4 = UUID.randomUUID(); // Created on Oct 28, delivered on Oct 29
        UUID p5 = UUID.randomUUID(); // Created on Oct 30 (outside window)

        // p1: created 2h after dayStart, delivered 5h after dayStart (duration: 3h)
        Parcel parcel1 = new Parcel(p1, "SWD-20261029-D01", "sender", null, null, "v1", 100L, "LKR", null);
        parcel1.setCreatedAt(dayStart.plus(Duration.ofHours(2)));
        parcel1.setStatus(ParcelStatus.DELIVERED);
        parcelRepository.save(parcel1);

        // p2: created 1h after dayStart, failed 4h after dayStart
        Parcel parcel2 = new Parcel(p2, "SWD-20261029-D02", "sender", null, null, "v1", 100L, "LKR", null);
        parcel2.setCreatedAt(dayStart.plus(Duration.ofHours(1)));
        parcel2.setStatus(ParcelStatus.FAILED);
        parcelRepository.save(parcel2);

        // p3: created 3h after dayStart, in transit
        Parcel parcel3 = new Parcel(p3, "SWD-20261029-D03", "sender", null, null, "v1", 100L, "LKR", null);
        parcel3.setCreatedAt(dayStart.plus(Duration.ofHours(3)));
        parcel3.setStatus(ParcelStatus.IN_TRANSIT);
        parcelRepository.save(parcel3);

        // p4: created before window, delivered on Oct 29 at 6h after dayStart (duration: 30h)
        Parcel parcel4 = new Parcel(p4, "SWD-20261029-D04", "sender", null, null, "v1", 100L, "LKR", null);
        parcel4.setCreatedAt(dayStart.minus(Duration.ofHours(24)));
        parcel4.setStatus(ParcelStatus.DELIVERED);
        parcelRepository.save(parcel4);

        // p5: created on Oct 30 exactly at dayEnd -> excluded by half-open [dayStart, dayEnd)
        Parcel parcel5 = new Parcel(p5, "SWD-20261029-D05", "sender", null, null, "v1", 100L, "LKR", null);
        parcel5.setCreatedAt(dayEnd);
        parcelRepository.save(parcel5);
        parcelRepository.flush();

        // Status history records
        historyRepository.save(new StatusHistory(
                UUID.randomUUID(), p1, ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED,
                null, "Delivered", null, "courier-1", 4L, dayStart.plus(Duration.ofHours(5))
        ));
        historyRepository.save(new StatusHistory(
                UUID.randomUUID(), p2, ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED,
                "Customer not available", "Failed", null, "courier-1", 3L, dayStart.plus(Duration.ofHours(4))
        ));
        historyRepository.save(new StatusHistory(
                UUID.randomUUID(), p4, ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED,
                null, "Delivered", null, "courier-2", 4L, dayStart.plus(Duration.ofHours(6))
        ));
        historyRepository.flush();

        // Test created count: p1, p2, p3 (3 parcels created in window)
        long createdCount = parcelRepository.countCreatedBetween(dayStart, dayEnd);
        assertThat(createdCount).isEqualTo(3);

        // Test delivered count: p1, p4 (2 parcels delivered in window)
        long deliveredCount = historyRepository.countDeliveredBetween(dayStart, dayEnd);
        assertThat(deliveredCount).isEqualTo(2);

        // Test failed count: p2 (1 parcel failed in window)
        long failedCount = historyRepository.countFailedBetween(dayStart, dayEnd);
        assertThat(failedCount).isEqualTo(1);

        // Test in-transit count before or at dayEnd: p3
        long inTransitCount = parcelRepository.countInTransitBeforeOrAt(dayEnd);
        assertThat(inTransitCount).isEqualTo(1);

        // Test duration query: p1 (3h) and p4 (30h)
        List<Object[]> durations = historyRepository.findDeliveredParcelDurations(dayStart, dayEnd);
        assertThat(durations).hasSize(2);

        double totalHours = 0.0;
        for (Object[] row : durations) {
            Instant created = (Instant) row[0];
            Instant delivered = (Instant) row[1];
            totalHours += Duration.between(created, delivered).toMillis() / (1000.0 * 60 * 60);
        }
        double avgHours = totalHours / durations.size();
        assertThat(avgHours).isEqualTo(16.5); // (3h + 30h) / 2 = 16.5h
    }

    @Test
    @DisplayName("Asia/Colombo day boundary converts to UTC correctly without date boundary leakage")
    void testAsiaColomboDayBoundary() {
        ZoneId colombo = ZoneId.of("Asia/Colombo");
        LocalDate date = LocalDate.of(2026, 10, 29);

        // Colombo is UTC+05:30. Oct 29 00:00 Colombo is Oct 28 18:30 UTC
        Instant colomboDayStart = date.atStartOfDay(colombo).toInstant();
        Instant colomboDayEnd = date.plusDays(1).atStartOfDay(colombo).toInstant();

        assertThat(colomboDayStart).isEqualTo(Instant.parse("2026-10-28T18:30:00Z"));
        assertThat(colomboDayEnd).isEqualTo(Instant.parse("2026-10-29T18:30:00Z"));

        // Parcel created at 18:29 UTC on Oct 28 -> prior Colombo day
        UUID pOld = UUID.randomUUID();
        Parcel oldParcel = new Parcel(pOld, "SWD-COL-001", "s", null, null, "v", 1L, "LKR", null);
        oldParcel.setCreatedAt(Instant.parse("2026-10-28T18:29:59Z"));
        parcelRepository.save(oldParcel);

        // Parcel created at 18:31 UTC on Oct 28 -> within Colombo Oct 29
        UUID pIn = UUID.randomUUID();
        Parcel inParcel = new Parcel(pIn, "SWD-COL-002", "s", null, null, "v", 1L, "LKR", null);
        inParcel.setCreatedAt(Instant.parse("2026-10-28T18:31:00Z"));
        parcelRepository.save(inParcel);
        parcelRepository.flush();

        long createdColombo = parcelRepository.countCreatedBetween(colomboDayStart, colomboDayEnd);
        assertThat(createdColombo).isEqualTo(1L);
    }
}
