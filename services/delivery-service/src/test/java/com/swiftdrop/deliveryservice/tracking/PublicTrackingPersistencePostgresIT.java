package com.swiftdrop.deliveryservice.tracking;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.deliveryservice.tracking.dto.PublicTrackingResponse;
import com.swiftdrop.deliveryservice.tracking.exception.TrackingNotFoundException;
import com.swiftdrop.deliveryservice.tracking.service.PublicTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicTrackingPersistencePostgresIT {

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
    private StatusHistoryRepository statusHistoryRepository;

    @Autowired
    private PublicTrackingService publicTrackingService;

    @BeforeEach
    void cleanUp() {
        statusHistoryRepository.deleteAll();
        parcelRepository.deleteAll();
    }

    @Test
    @DisplayName("PostgreSQL: Querying valid tracking number returns persisted state and history in chronological order")
    void testPublicTrackingPostgresQuery() {
        UUID parcelId = UUID.randomUUID();
        String trackingNumber = "SWD-20261023-PG0001";

        Parcel parcel = new Parcel(
                parcelId,
                trackingNumber,
                "sender-customer-sub",
                null,
                null,
                "2026.1",
                50000L,
                "LKR",
                new BigDecimal("1.500")
        );
        parcel.setStatus(ParcelStatus.IN_TRANSIT);
        parcelRepository.saveAndFlush(parcel);

        Instant t1 = Instant.now().minusSeconds(120);
        Instant t2 = Instant.now().minusSeconds(60);

        statusHistoryRepository.saveAndFlush(new StatusHistory(
                UUID.randomUUID(), parcelId, null, ParcelStatus.CREATED,
                "Order booked", null, null, "sender-customer-sub", 1L, t1
        ));

        statusHistoryRepository.saveAndFlush(new StatusHistory(
                UUID.randomUUID(), parcelId, ParcelStatus.CREATED, ParcelStatus.IN_TRANSIT,
                "In transit", null, null, "courier-sub", 2L, t2
        ));

        PublicTrackingResponse response = publicTrackingService.getPublicTracking(trackingNumber);

        assertThat(response).isNotNull();
        assertThat(response.trackingNumber()).isEqualTo(trackingNumber);
        assertThat(response.currentStatus()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(response.originCity()).isEqualTo("Colombo");
        assertThat(response.destinationCity()).isEqualTo("Gampaha");
        assertThat(response.timeline()).hasSize(2);
        assertThat(response.timeline().get(0).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(response.timeline().get(1).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
    }

    @Test
    @DisplayName("PostgreSQL: Querying non-existent tracking reference throws TrackingNotFoundException")
    void testNonExistentTrackingNumberThrowsNotFound() {
        assertThatThrownBy(() -> publicTrackingService.getPublicTracking("SWD-20261023-PG9999"))
                .isInstanceOf(TrackingNotFoundException.class)
                .hasMessageContaining("was not found");
    }
}
