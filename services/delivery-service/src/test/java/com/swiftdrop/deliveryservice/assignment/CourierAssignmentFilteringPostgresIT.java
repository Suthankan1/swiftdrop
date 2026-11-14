package com.swiftdrop.deliveryservice.assignment;

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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CourierAssignmentFilteringPostgresIT {

    private static final String COURIER = "courier-filter-owner";
    private static final String OTHER_COURIER = "courier-filter-other";
    private static final Instant DAY_START = Instant.parse("2026-11-14T00:00:00Z");

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
    private StatusHistoryRepository historyRepository;

    @BeforeEach
    void isolateData() {
        historyRepository.deleteAllInBatch();
        assignmentRepository.deleteAllInBatch();
        parcelRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Active assignment filters support every combination with stable paging and courier isolation")
    void activeFilterMatrixUsesTypedDynamicPredicates() {
        ActiveFixture fixture = seedActiveAssignments();
        PageRequest all = PageRequest.of(0, 20);

        assertIds(active(null, null, null, all),
                fixture.inTransit(), fixture.pickedUp(), fixture.created(), fixture.outForDelivery());
        assertIds(active(null, "swd-20261114-a29dd4a5", null, all), fixture.created());
        assertIds(active(null, "A29D", null, all), fixture.created());
        assertThat(active(null, "DOES-NOT-EXIST", null, all)).isEmpty();

        assertIds(active(null, null, ParcelStatus.CREATED, all), fixture.created());
        assertIds(active(null, null, ParcelStatus.PICKED_UP, all), fixture.pickedUp());
        assertIds(active(null, null, ParcelStatus.IN_TRANSIT, all), fixture.inTransit());
        assertIds(active(null, null, ParcelStatus.OUT_FOR_DELIVERY, all), fixture.outForDelivery());

        assertIds(active(DAY_START, null, null, all),
                fixture.inTransit(), fixture.pickedUp(), fixture.created());
        assertIds(active(null, "TRANSIT77", ParcelStatus.IN_TRANSIT, all), fixture.inTransit());
        assertIds(active(DAY_START, "PICKED88", null, all), fixture.pickedUp());
        assertIds(active(DAY_START, null, ParcelStatus.CREATED, all), fixture.created());
        assertIds(active(DAY_START, "A29D", ParcelStatus.CREATED, all), fixture.created());

        assertIds(active(null, null, null, all),
                fixture.inTransit(), fixture.pickedUp(), fixture.created(), fixture.outForDelivery());
        assertIds(active(null, "   ", null, all),
                fixture.inTransit(), fixture.pickedUp(), fixture.created(), fixture.outForDelivery());

        Page<DeliveryAssignment> firstPage = active(null, null, null, PageRequest.of(0, 2));
        Page<DeliveryAssignment> secondPage = active(null, null, null, PageRequest.of(1, 2));
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertIds(firstPage, fixture.inTransit(), fixture.pickedUp());
        assertIds(secondPage, fixture.created(), fixture.outForDelivery());

        assertThat(assignmentRepository.findFilteredActiveByCourierSub(
                OTHER_COURIER, null, null, null, all))
                .extracting(DeliveryAssignment::getAssignmentId)
                .containsExactly(fixture.otherCourier());
        assertThat(active(null, "%", null, all)).isEmpty();
        assertThat(active(null, "_", null, all)).isEmpty();
    }

    @Test
    @DisplayName("Courier history filters terminal events, date boundaries and pages without leaking couriers")
    void historyFilterMatrixUsesTypedDynamicPredicates() {
        HistoryFixture fixture = seedHistory();
        PageRequest all = PageRequest.of(0, 20);

        assertIds(history(null, null, null, all), fixture.failedToday(), fixture.deliveredToday(), fixture.deliveredBeforeToday());
        assertIds(history(ParcelStatus.DELIVERED, null, null, all), fixture.deliveredToday(), fixture.deliveredBeforeToday());
        assertIds(history(ParcelStatus.FAILED, null, null, all), fixture.failedToday());
        assertIds(history(null, DAY_START, DAY_START.plus(1, ChronoUnit.DAYS), all),
                fixture.failedToday(), fixture.deliveredToday());
        assertIds(history(ParcelStatus.DELIVERED, DAY_START, DAY_START.plus(1, ChronoUnit.DAYS), all),
                fixture.deliveredToday());
        assertIds(history(ParcelStatus.FAILED, DAY_START, DAY_START.plus(1, ChronoUnit.DAYS), all),
                fixture.failedToday());

        assertIds(history(null, DAY_START, DAY_START.plus(12, ChronoUnit.HOURS), all), fixture.deliveredToday());
        assertIds(history(null, DAY_START.plus(12, ChronoUnit.HOURS), DAY_START.plus(1, ChronoUnit.DAYS), all),
                fixture.failedToday());
        assertThat(history(null, DAY_START.plus(2, ChronoUnit.DAYS), DAY_START.plus(3, ChronoUnit.DAYS), all)).isEmpty();

        Page<DeliveryAssignment> firstPage = history(null, null, null, PageRequest.of(0, 2));
        Page<DeliveryAssignment> secondPage = history(null, null, null, PageRequest.of(1, 2));
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertIds(firstPage, fixture.failedToday(), fixture.deliveredToday());
        assertIds(secondPage, fixture.deliveredBeforeToday());

        assertThat(assignmentRepository.findCourierHistory(
                OTHER_COURIER, null, null, null, all))
                .extracting(DeliveryAssignment::getAssignmentId)
                .containsExactly(fixture.otherCourier());
        assertThat(assignmentRepository.findCourierHistory(
                "unrelated-courier", null, null, null, all)).isEmpty();

        DeliveryAssignment historical = assignmentRepository.findById(fixture.deliveredToday()).orElseThrow();
        Parcel parcel = parcelRepository.findById(historical.getParcelId()).orElseThrow();
        assertThat(parcel.getTrackingNumber()).isEqualTo("SWD-20261114-HISTD001");
        assertThat(parcel.getStatus()).isEqualTo(ParcelStatus.DELIVERED);
    }

    private Page<DeliveryAssignment> active(
            Instant since,
            String tracking,
            ParcelStatus status,
            PageRequest page) {
        return assignmentRepository.findFilteredActiveByCourierSub(COURIER, since, tracking, status, page);
    }

    private Page<DeliveryAssignment> history(
            ParcelStatus status,
            Instant since,
            Instant until,
            PageRequest page) {
        return assignmentRepository.findCourierHistory(COURIER, status, since, until, page);
    }

    private ActiveFixture seedActiveAssignments() {
        UUID created = saveActive("SWD-20261114-A29DD4A5", ParcelStatus.CREATED, DAY_START.plus(8, ChronoUnit.HOURS), COURIER);
        UUID pickedUp = saveActive("SWD-20261114-PICKED88", ParcelStatus.PICKED_UP, DAY_START.plus(9, ChronoUnit.HOURS), COURIER);
        UUID inTransit = saveActive("SWD-20261114-TRANSIT77", ParcelStatus.IN_TRANSIT, DAY_START.plus(10, ChronoUnit.HOURS), COURIER);
        UUID outForDelivery = saveActive("SWD-20261113-OUTFOR66", ParcelStatus.OUT_FOR_DELIVERY, DAY_START.minus(1, ChronoUnit.HOURS), COURIER);
        UUID otherCourier = saveActive("SWD-20261114-OTHER555", ParcelStatus.CREATED, DAY_START.plus(11, ChronoUnit.HOURS), OTHER_COURIER);
        return new ActiveFixture(created, pickedUp, inTransit, outForDelivery, otherCourier);
    }

    private HistoryFixture seedHistory() {
        UUID deliveredBefore = saveHistory(
                "SWD-20261113-HISTOLD1", ParcelStatus.DELIVERED, DAY_START.minus(1, ChronoUnit.SECONDS), COURIER);
        UUID deliveredToday = saveHistory(
                "SWD-20261114-HISTD001", ParcelStatus.DELIVERED, DAY_START, COURIER);
        UUID failedToday = saveHistory(
                "SWD-20261114-HISTF001", ParcelStatus.FAILED, DAY_START.plus(12, ChronoUnit.HOURS), COURIER);
        UUID otherCourier = saveHistory(
                "SWD-20261114-HISTOTHR", ParcelStatus.DELIVERED, DAY_START.plus(18, ChronoUnit.HOURS), OTHER_COURIER);
        return new HistoryFixture(deliveredBefore, deliveredToday, failedToday, otherCourier);
    }

    private UUID saveActive(String tracking, ParcelStatus status, Instant assignedAt, String courierSub) {
        Parcel parcel = saveParcel(tracking, status);
        DeliveryAssignment assignment = new DeliveryAssignment(
                UUID.randomUUID(), parcel.getParcelId(), courierSub, "dispatcher", assignedAt, null, true);
        return assignmentRepository.saveAndFlush(assignment).getAssignmentId();
    }

    private UUID saveHistory(String tracking, ParcelStatus status, Instant completedAt, String courierSub) {
        Parcel parcel = saveParcel(tracking, status);
        Instant assignedAt = completedAt.minus(2, ChronoUnit.HOURS);
        DeliveryAssignment assignment = assignmentRepository.saveAndFlush(new DeliveryAssignment(
                UUID.randomUUID(), parcel.getParcelId(), courierSub, "dispatcher", assignedAt, completedAt, false));
        historyRepository.saveAndFlush(new StatusHistory(
                UUID.randomUUID(),
                parcel.getParcelId(),
                ParcelStatus.OUT_FOR_DELIVERY,
                status,
                status == ParcelStatus.FAILED ? "RECIPIENT_UNAVAILABLE" : null,
                null,
                null,
                courierSub,
                5L,
                completedAt));
        return assignment.getAssignmentId();
    }

    private Parcel saveParcel(String tracking, ParcelStatus status) {
        Parcel parcel = new Parcel(
                UUID.randomUUID(), tracking, "customer", null, UUID.randomUUID(), "v1", 1000L, "LKR", BigDecimal.ONE);
        parcel.setStatus(status);
        return parcelRepository.saveAndFlush(parcel);
    }

    private void assertIds(Page<DeliveryAssignment> page, UUID... expected) {
        assertThat(page.getContent())
                .extracting(DeliveryAssignment::getAssignmentId)
                .containsExactly(expected);
    }

    private record ActiveFixture(
            UUID created,
            UUID pickedUp,
            UUID inTransit,
            UUID outForDelivery,
            UUID otherCourier) {
    }

    private record HistoryFixture(
            UUID deliveredBeforeToday,
            UUID deliveredToday,
            UUID failedToday,
            UUID otherCourier) {
    }
}
