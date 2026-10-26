package com.swiftdrop.deliveryservice.query;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignment;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.query.dto.PagedResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelStatusHistoryResponse;
import com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException;
import com.swiftdrop.deliveryservice.query.service.DefaultParcelQueryService;
import com.swiftdrop.deliveryservice.query.service.ParcelAuthorizationService;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParcelQueryServiceTest {

    @Mock
    private ParcelRepository parcelRepository;

    @Mock
    private DeliveryAssignmentRepository assignmentRepository;

    @Mock
    private StatusHistoryRepository historyRepository;

    private ParcelAuthorizationService authorizationService;
    private DefaultParcelQueryService queryService;

    private static final UUID PARCEL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_MERCHANT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SENDER_SUB = "cust-alice-sub";
    private static final String OTHER_SENDER_SUB = "cust-bob-sub";
    private static final String COURIER_SUB = "courier-charlie-sub";
    private static final String OTHER_COURIER_SUB = "courier-dave-sub";

    private Parcel testParcel;

    @BeforeEach
    void setUp() {
        authorizationService = new ParcelAuthorizationService(assignmentRepository);
        queryService = new DefaultParcelQueryService(
                parcelRepository,
                assignmentRepository,
                historyRepository,
                authorizationService
        );

        testParcel = new Parcel(
                PARCEL_ID,
                "SWD-20261025-A1B2C3",
                SENDER_SUB,
                MERCHANT_ID,
                UUID.randomUUID(),
                "RATE-2026-V1",
                450000L,
                "LKR",
                new BigDecimal("2.750")
        );
        testParcel.setStatus(ParcelStatus.IN_TRANSIT);
        testParcel.setVersion(3L);
        testParcel.setCreatedAt(Instant.now().minusSeconds(3600));
    }

    private Jwt buildJwt(String sub, List<String> roles, Map<String, Object> customClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject(sub)
                .claim("roles", roles);

        if (customClaims != null) {
            customClaims.forEach(builder::claim);
        }
        return builder.build();
    }

    @Nested
    @DisplayName("Authorization Service Tests")
    class AuthorizationTests {

        @Test
        @DisplayName("Customer reading own parcel is allowed")
        void customerOwnParcelAllowed() {
            Jwt jwt = buildJwt(SENDER_SUB, List.of("CUSTOMER"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isTrue();
        }

        @Test
        @DisplayName("Customer reading other customer parcel is denied")
        void customerOtherParcelDenied() {
            Jwt jwt = buildJwt(OTHER_SENDER_SUB, List.of("CUSTOMER"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isFalse();
        }

        @Test
        @DisplayName("Merchant reading own approved tenant parcel is allowed")
        void merchantOwnTenantAllowed() {
            Jwt jwt = buildJwt("merchant-user", List.of("MERCHANT"), Map.of(
                    "merchant_id", MERCHANT_ID.toString(),
                    "merchant_status", "APPROVED"
            ));
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isTrue();
        }

        @Test
        @DisplayName("Merchant crossing tenant boundary is denied")
        void merchantCrossTenantDenied() {
            Jwt jwt = buildJwt("merchant-user", List.of("MERCHANT"), Map.of(
                    "merchant_id", OTHER_MERCHANT_ID.toString(),
                    "merchant_status", "APPROVED"
            ));
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isFalse();
        }

        @Test
        @DisplayName("Merchant with status PENDING is denied")
        void merchantPendingDenied() {
            Jwt jwt = buildJwt("merchant-user", List.of("MERCHANT"), Map.of(
                    "merchant_id", MERCHANT_ID.toString(),
                    "merchant_status", "PENDING"
            ));
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isFalse();
        }

        @Test
        @DisplayName("Merchant with status SUSPENDED is denied")
        void merchantSuspendedDenied() {
            Jwt jwt = buildJwt("merchant-user", List.of("MERCHANT"), Map.of(
                    "merchant_id", MERCHANT_ID.toString(),
                    "merchant_status", "SUSPENDED"
            ));
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isFalse();
        }

        @Test
        @DisplayName("Currently active assigned courier is allowed")
        void courierActiveAssignmentAllowed() {
            when(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(PARCEL_ID, COURIER_SUB))
                    .thenReturn(true);

            Jwt jwt = buildJwt(COURIER_SUB, List.of("COURIER"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isTrue();
        }

        @Test
        @DisplayName("Unassigned courier is denied")
        void courierUnassignedDenied() {
            when(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(PARCEL_ID, OTHER_COURIER_SUB))
                    .thenReturn(false);

            Jwt jwt = buildJwt(OTHER_COURIER_SUB, List.of("COURIER"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isFalse();
        }

        @Test
        @DisplayName("Previously assigned courier after reassignment is denied")
        void courierPreviouslyAssignedDenied() {
            when(assignmentRepository.existsByParcelIdAndCourierSubAndActiveTrue(PARCEL_ID, COURIER_SUB))
                    .thenReturn(false);

            Jwt jwt = buildJwt(COURIER_SUB, List.of("COURIER"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isFalse();
        }

        @Test
        @DisplayName("Dispatcher is allowed operational scope")
        void dispatcherAllowed() {
            Jwt jwt = buildJwt("dispatcher-user", List.of("DISPATCHER"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isTrue();
        }

        @Test
        @DisplayName("Admin is allowed operational scope")
        void adminAllowed() {
            Jwt jwt = buildJwt("admin-user", List.of("ADMIN"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isTrue();
        }

        @Test
        @DisplayName("Unrecognized or missing role fails closed")
        void invalidRoleFailsClosed() {
            Jwt jwt = buildJwt("guest-user", List.of("GUEST"), null);
            assertThat(authorizationService.isAuthorizedToRead(jwt, testParcel)).isFalse();
        }
    }

    @Nested
    @DisplayName("Parcel Detail Read Tests")
    class DetailReadTests {

        @Test
        @DisplayName("Authorized read returns contract-compliant ParcelDetailResponse")
        void authorizedReadSucceeds() {
            when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(testParcel));
            DeliveryAssignment activeAssignment = new DeliveryAssignment(
                    UUID.randomUUID(),
                    PARCEL_ID,
                    COURIER_SUB,
                    "dispatcher-sub",
                    Instant.now(),
                    null,
                    true
            );
            when(assignmentRepository.findByParcelIdAndActiveTrue(PARCEL_ID))
                    .thenReturn(Optional.of(activeAssignment));

            Jwt jwt = buildJwt(SENDER_SUB, List.of("CUSTOMER"), null);
            ParcelDetailResponse response = queryService.getParcelDetail(PARCEL_ID, jwt);

            assertThat(response.parcelId()).isEqualTo(PARCEL_ID);
            assertThat(response.trackingNumber()).isEqualTo("SWD-20261025-A1B2C3");
            assertThat(response.createdBySub()).isEqualTo(SENDER_SUB);
            assertThat(response.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(response.currentStatus()).isEqualTo(ParcelStatus.IN_TRANSIT);
            assertThat(response.version()).isEqualTo(3L);
            assertThat(response.amountMinorUnits()).isEqualTo(450000L);
            assertThat(response.currency()).isEqualTo("LKR");
            assertThat(response.rateVersion()).isEqualTo("RATE-2026-V1");
            assertThat(response.weightKg()).isEqualTo(2.750);
            assertThat(response.activeCourierSub()).isEqualTo(COURIER_SUB);
            assertThat(response.dimensions()).isNotBlank();
        }

        @Test
        @DisplayName("Unauthorized caller reading parcel throws ParcelAccessDeniedException")
        void unauthorizedReadThrows() {
            when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(testParcel));

            Jwt jwt = buildJwt(OTHER_SENDER_SUB, List.of("CUSTOMER"), null);
            assertThatThrownBy(() -> queryService.getParcelDetail(PARCEL_ID, jwt))
                    .isInstanceOf(ParcelAccessDeniedException.class);
        }

        @Test
        @DisplayName("Non-existent parcelId throws ParcelNotFoundException")
        void nonExistentParcelThrows() {
            UUID unknownId = UUID.randomUUID();
            when(parcelRepository.findById(unknownId)).thenReturn(Optional.empty());

            Jwt jwt = buildJwt(SENDER_SUB, List.of("CUSTOMER"), null);
            assertThatThrownBy(() -> queryService.getParcelDetail(unknownId, jwt))
                    .isInstanceOf(ParcelNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Parcel History Read Tests")
    class HistoryReadTests {

        @Test
        @DisplayName("Authorized read returns status history in version order")
        void authorizedHistorySucceeds() {
            when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(testParcel));

            Instant t0 = Instant.now().minusSeconds(100);
            Instant t1 = Instant.now().minusSeconds(50);

            StatusHistory h1 = new StatusHistory(
                    UUID.randomUUID(), PARCEL_ID, null, ParcelStatus.CREATED,
                    null, null, null, SENDER_SUB, 1L, t0
            );
            StatusHistory h2 = new StatusHistory(
                    UUID.randomUUID(), PARCEL_ID, ParcelStatus.CREATED, ParcelStatus.PICKED_UP,
                    null, "Collected package", null, COURIER_SUB, 2L, t1
            );

            Page<StatusHistory> page = new PageImpl<>(List.of(h1, h2), PageRequest.of(0, 20), 2);
            when(historyRepository.findByParcelIdOrderByResultingVersionAsc(eq(PARCEL_ID), any(Pageable.class)))
                    .thenReturn(page);

            Jwt jwt = buildJwt(SENDER_SUB, List.of("CUSTOMER"), null);
            PagedResponse<ParcelStatusHistoryResponse> response = queryService.getParcelHistory(
                    PARCEL_ID,
                    jwt,
                    PageRequest.of(0, 20)
            );

            assertThat(response.items()).hasSize(2);
            assertThat(response.items().get(0).newStatus()).isEqualTo(ParcelStatus.CREATED);
            assertThat(response.items().get(0).resultingVersion()).isEqualTo(1L);
            assertThat(response.items().get(1).newStatus()).isEqualTo(ParcelStatus.PICKED_UP);
            assertThat(response.items().get(1).resultingVersion()).isEqualTo(2L);
            assertThat(response.totalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("Unauthorized caller reading history throws ParcelAccessDeniedException")
        void unauthorizedHistoryThrows() {
            when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(testParcel));

            Jwt jwt = buildJwt(OTHER_SENDER_SUB, List.of("CUSTOMER"), null);
            assertThatThrownBy(() -> queryService.getParcelHistory(PARCEL_ID, jwt, PageRequest.of(0, 20)))
                    .isInstanceOf(ParcelAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Parcel Projection Query Contract Tests")
    class ProjectionTests {

        @Test
        @DisplayName("findParcelById resolves projection with active courier")
        void findParcelByIdResolvesProjection() {
            when(parcelRepository.findById(PARCEL_ID)).thenReturn(Optional.of(testParcel));
            when(assignmentRepository.findByParcelIdAndActiveTrue(PARCEL_ID))
                    .thenReturn(Optional.of(new DeliveryAssignment(
                            UUID.randomUUID(), PARCEL_ID, COURIER_SUB, "dispatcher", Instant.now(), null, true
                    )));

            Optional<ParcelProjection> projection = queryService.findParcelById(PARCEL_ID);

            assertThat(projection).isPresent();
            assertThat(projection.get().parcelId()).isEqualTo(PARCEL_ID);
            assertThat(projection.get().activeCourierSub()).isEqualTo(COURIER_SUB);
            assertThat(projection.get().currentStatus()).isEqualTo(ParcelStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("findParcelByTrackingNumber resolves projection")
        void findParcelByTrackingNumberResolves() {
            when(parcelRepository.findByTrackingNumber("SWD-20261025-A1B2C3")).thenReturn(Optional.of(testParcel));
            when(assignmentRepository.findByParcelIdAndActiveTrue(PARCEL_ID)).thenReturn(Optional.empty());

            Optional<ParcelProjection> projection = queryService.findParcelByTrackingNumber("SWD-20261025-A1B2C3");

            assertThat(projection).isPresent();
            assertThat(projection.get().trackingNumber()).isEqualTo("SWD-20261025-A1B2C3");
            assertThat(projection.get().activeCourierSub()).isNull();
        }
    }
}
