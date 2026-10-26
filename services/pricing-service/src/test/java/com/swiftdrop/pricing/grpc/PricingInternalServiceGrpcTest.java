package com.swiftdrop.pricing.grpc;

import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.pricing.domain.QuoteSnapshot;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.persistence.*;
import com.swiftdrop.pricing.v1.*;
import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.grpc.SwiftDropGrpcServerSecurityInterceptor;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import com.swiftdrop.security.service.JwtServiceTokenValidator;
import com.swiftdrop.security.service.ServiceTokenValidator;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PricingInternalServiceGrpcTest {

    private final JwtTestHelper jwtHelper = new JwtTestHelper();

    private QuoteRepository quoteRepository;
    private ZoneRepository zoneRepository;
    private RateVersionRepository rateVersionRepository;
    private RateRuleRepository rateRuleRepository;

    private Server server;
    private ManagedChannel channel;

    private final Instant baseTime = Instant.parse("2026-10-28T10:00:00Z");
    private Clock fixedClock;

    private final UUID quoteId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID rateVersionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final String customerSub = "cust-alice-123";
    private final String merchantTenant = "tenant-xyz";

    @BeforeEach
    void setUp() throws Exception {
        quoteRepository = mock(QuoteRepository.class);
        zoneRepository = mock(ZoneRepository.class);
        rateVersionRepository = mock(RateVersionRepository.class);
        rateRuleRepository = mock(RateRuleRepository.class);
        fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);

        PricingInternalServiceImpl serviceImpl = new PricingInternalServiceImpl(
                quoteRepository,
                zoneRepository,
                rateVersionRepository,
                rateRuleRepository,
                fixedClock
        );

        ServiceTokenValidator serviceValidator = new JwtServiceTokenValidator(
                jwtHelper.createServiceJwtDecoder(),
                JwtTestHelper.DEFAULT_SERVICE_AUDIENCE,
                JwtTestHelper.DEFAULT_ISSUER
        );

        ActorDelegationValidator actorValidator = new ActorDelegationValidator(
                jwtHelper.createUserJwtDecoder()
        );

        SwiftDropGrpcServerSecurityInterceptor securityInterceptor =
                new SwiftDropGrpcServerSecurityInterceptor(serviceValidator, actorValidator, new GrpcSecurityPolicy());

        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(securityInterceptor)
                .addService(serviceImpl)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    private PricingInternalServiceGrpc.PricingInternalServiceBlockingStub createStubWithServiceToken(String serviceName) {
        String token = jwtHelper.createSignedServiceToken(serviceName);
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, token);
        return PricingInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private PricingInternalServiceGrpc.PricingInternalServiceBlockingStub createDeliveryStubWithActorDelegation(
            String actorSub, String actorRole, String tenantId) {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        String actorToken = jwtHelper.createSignedUserToken(actorSub, List.of(actorRole), tenantId);
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, actorRole);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, actorToken);
        return PricingInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private PricingInternalServiceGrpc.PricingInternalServiceBlockingStub createGraphQLStubWithActorDelegation(
            String actorSub, String actorRole) {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName());
        String actorToken = jwtHelper.createSignedUserToken(actorSub, List.of(actorRole));
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, actorRole);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, actorToken);
        return PricingInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private QuoteSnapshot createSampleQuote(UUID qId, String sub, String tenant, boolean anonymous, Instant expiresAt) {
        return new QuoteSnapshot(
                qId,
                "RATE-2026-V1",
                rateVersionId,
                null,
                "ZONE_1",
                "ZONE_2",
                30,
                20,
                15,
                new BigDecimal("2.50"),
                new BigDecimal("1.80"),
                new BigDecimal("2.50"),
                SizeCategory.MEDIUM,
                35000L,
                15000L,
                50000L,
                "LKR",
                sub,
                tenant,
                anonymous,
                baseTime.minusSeconds(300),
                expiresAt
        );
    }

    // =========================================================================
    // Machine Authentication & Service Identity Tests
    // =========================================================================

    @Test
    @DisplayName("Valid Delivery service token with Customer delegation succeeds")
    void validDeliveryServiceTokenWithCustomerDelegation_succeeds() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, customerSub, null, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        ValidateQuoteResponse response = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .setExpectedRateVersion("RATE-2026-V1")
                .setExpectedAmountMinorUnits(50000L)
                .setExpectedCurrency("LKR")
                .build());

        assertThat(response.getIsValid()).isTrue();
        assertThat(response.getValidationCode()).isEqualTo("VALID");
        assertThat(response.getQuoteId()).isEqualTo(quoteId.toString());
        assertThat(response.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(response.getVerifiedAmountMinorUnits()).isEqualTo(50000L);
        assertThat(response.getCurrency()).isEqualTo("LKR");
        assertThat(response.getPickupZone()).isEqualTo("ZONE_1");
        assertThat(response.getDropoffZone()).isEqualTo("ZONE_2");
        assertThat(response.getSubjectSub()).isEqualTo(customerSub);
        assertThat(response.getIsAnonymous()).isFalse();
        assertThat(response.getBaseFeeMinorUnits()).isEqualTo(35000L);
        assertThat(response.getWeightSurchargeMinorUnits()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("Missing service token is rejected with UNAUTHENTICATED")
    void missingServiceToken_rejectedWithUnauthenticated() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("Expired service token is rejected with UNAUTHENTICATED")
    void expiredServiceToken_rejectedWithUnauthenticated() {
        String expiredServiceToken = jwtHelper.createExpiredServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, expiredServiceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, customerSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, SwiftDropRoles.ROLE_CUSTOMER);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, jwtHelper.createSignedUserToken(customerSub, List.of(SwiftDropRoles.ROLE_CUSTOMER)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("Untrusted service token signature is rejected with UNAUTHENTICATED")
    void untrustedServiceToken_rejectedWithUnauthenticated() {
        String untrustedToken = jwtHelper.createUntrustedSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, untrustedToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, customerSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, SwiftDropRoles.ROLE_CUSTOMER);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, jwtHelper.createSignedUserToken(customerSub, List.of(SwiftDropRoles.ROLE_CUSTOMER)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("Unauthorized calling service is rejected with PERMISSION_DENIED")
    void unauthorizedCallingService_rejectedWithPermissionDenied() {
        // COMMUNICATIONS_SERVICE is not in the allowed services list for ValidateQuote
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE.getServiceName());
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, customerSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, SwiftDropRoles.ROLE_CUSTOMER);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, jwtHelper.createSignedUserToken(customerSub, List.of(SwiftDropRoles.ROLE_CUSTOMER)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    // =========================================================================
    // Delegated Actor Authentication & Anti-Spoofing Tests
    // =========================================================================

    @Test
    @DisplayName("Missing actor delegation header is rejected")
    void missingActorDelegation_rejected() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createStubWithServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isIn(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Expired actor token is rejected with UNAUTHENTICATED")
    void expiredActorToken_rejected() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        String expiredActorToken = jwtHelper.createExpiredUserToken(customerSub, List.of(SwiftDropRoles.ROLE_CUSTOMER));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, customerSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, SwiftDropRoles.ROLE_CUSTOMER);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, expiredActorToken);

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isIn(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Forged actor role is rejected (anti-elevation)")
    void forgedActorRole_rejected() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        // Token has CUSTOMER, but header claims ADMIN
        String realActorToken = jwtHelper.createSignedUserToken(customerSub, List.of(SwiftDropRoles.ROLE_CUSTOMER));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, customerSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, SwiftDropRoles.ROLE_ADMIN);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, realActorToken);

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isIn(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Forged actor subject is rejected (anti-spoofing)")
    void forgedActorSub_rejected() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());
        // Token belongs to attacker-sub, but header claims customerSub
        String attackerToken = jwtHelper.createSignedUserToken("attacker-sub", List.of(SwiftDropRoles.ROLE_CUSTOMER));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, customerSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, SwiftDropRoles.ROLE_CUSTOMER);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, attackerToken);

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                PricingInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isIn(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Disallowed actor role (COURIER) is rejected with PERMISSION_DENIED")
    void wrongActorRole_rejected() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation("courier-123", SwiftDropRoles.ROLE_COURIER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    // =========================================================================
    // Quote Ownership & Resource Isolation Tests
    // =========================================================================

    @Test
    @DisplayName("Customer attempting to use another customer's quote is denied with PERMISSION_DENIED")
    void customerAccessingAnotherCustomerQuote_rejected() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, "other-customer-999", null, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Merchant attempting to use another tenant's quote is denied with PERMISSION_DENIED")
    void merchantAccessingAnotherTenantQuote_rejected() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, "merchant-sub", "tenant-other", false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation("merchant-sub", SwiftDropRoles.ROLE_MERCHANT, merchantTenant);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Merchant accessing quote matching their own tenant succeeds")
    void merchantAccessingOwnTenantQuote_succeeds() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, "merchant-sub", merchantTenant, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation("merchant-sub", SwiftDropRoles.ROLE_MERCHANT, merchantTenant);

        ValidateQuoteResponse response = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build());

        assertThat(response.getIsValid()).isTrue();
        assertThat(response.getTenantId()).isEqualTo(merchantTenant);
    }

    @Test
    @DisplayName("Admin attempting to book an unrelated customer's quote is denied")
    void adminAccessingUnrelatedCustomerQuote_rejected() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, customerSub, null, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation("admin-staff-sub", SwiftDropRoles.ROLE_ADMIN, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Anonymous quote cannot be used for authenticated booking and is rejected")
    void anonymousQuoteBookingAttempt_rejectedWithPermissionDenied() {
        QuoteSnapshot anonymousSnapshot = createSampleQuote(quoteId, null, null, true, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(anonymousSnapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException sre = (StatusRuntimeException) ex;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(sre.getMessage()).contains("Anonymous quote cannot be used for authenticated parcel booking");
                });
    }

    // =========================================================================
    // Protocol, Input Validation & Error Behavior Tests
    // =========================================================================

    @Test
    @DisplayName("Blank quote_id is rejected with INVALID_ARGUMENT")
    void blankQuoteId_rejectedWithInvalidArgument() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId("   ")
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("Malformed UUID quote_id is rejected with INVALID_ARGUMENT")
    void malformedUuidQuoteId_rejectedWithInvalidArgument() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId("not-a-valid-uuid")
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("Negative expected amount is rejected with INVALID_ARGUMENT")
    void negativeExpectedAmount_rejectedWithInvalidArgument() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .setExpectedAmountMinorUnits(-100L)
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("Malformed currency code is rejected with INVALID_ARGUMENT")
    void malformedCurrency_rejectedWithInvalidArgument() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .setExpectedCurrency("TOOLONG")
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("Non-existent quote returns is_valid=false and QUOTE_NOT_FOUND")
    void nonExistentQuote_returnsNotFound() {
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.empty());

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        ValidateQuoteResponse response = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build());

        assertThat(response.getIsValid()).isFalse();
        assertThat(response.getValidationCode()).isEqualTo("QUOTE_NOT_FOUND");
        assertThat(response.getQuoteId()).isEqualTo(quoteId.toString());
    }

    @Test
    @DisplayName("Expired quote returns is_valid=false and QUOTE_EXPIRED with preserved snapshot")
    void expiredQuote_returnsQuoteExpired() {
        // Expiry at baseTime (clock is at baseTime -> expired)
        QuoteSnapshot expiredSnapshot = createSampleQuote(quoteId, customerSub, null, false, baseTime);
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(expiredSnapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        ValidateQuoteResponse response = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build());

        assertThat(response.getIsValid()).isFalse();
        assertThat(response.getValidationCode()).isEqualTo("QUOTE_EXPIRED");
        assertThat(response.getQuoteId()).isEqualTo(quoteId.toString());
        assertThat(response.getRateVersion()).isEqualTo("RATE-2026-V1");
        assertThat(response.getVerifiedAmountMinorUnits()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("Rate version mismatch returns is_valid=false and RATE_SUPERSEDED")
    void rateVersionMismatch_returnsRateSuperseded() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, customerSub, null, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        ValidateQuoteResponse response = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .setExpectedRateVersion("RATE-2027-V2") // Mismatch
                .build());

        assertThat(response.getIsValid()).isFalse();
        assertThat(response.getValidationCode()).isEqualTo("RATE_SUPERSEDED");
    }

    @Test
    @DisplayName("Amount mismatch returns is_valid=false and AMOUNT_MISMATCH")
    void amountMismatch_returnsAmountMismatch() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, customerSub, null, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        ValidateQuoteResponse response = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .setExpectedAmountMinorUnits(99999L) // Mismatch
                .build());

        assertThat(response.getIsValid()).isFalse();
        assertThat(response.getValidationCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("Currency mismatch returns is_valid=false and AMOUNT_MISMATCH")
    void currencyMismatch_returnsAmountMismatch() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, customerSub, null, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        ValidateQuoteResponse response = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .setExpectedCurrency("USD") // Mismatch (stored is LKR)
                .build());

        assertThat(response.getIsValid()).isFalse();
        assertThat(response.getValidationCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("Repeated quote validation before expiry returns identical values")
    void repeatedValidation_returnsIdenticalValues() {
        QuoteSnapshot snapshot = createSampleQuote(quoteId, customerSub, null, false, baseTime.plusSeconds(600));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(QuoteEntity.fromDomain(snapshot)));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        ValidateQuoteResponse first = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build());

        ValidateQuoteResponse second = stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build());

        assertThat(first).isEqualTo(second);
        verify(quoteRepository, times(2)).findById(quoteId);
        verifyNoMoreInteractions(quoteRepository);
    }

    @Test
    @DisplayName("Database unavailable error is sanitized to UNAVAILABLE")
    void databaseUnavailable_sanitizedToUnavailable() {
        when(quoteRepository.findById(quoteId)).thenThrow(new DataAccessResourceFailureException("DB connection refused"));

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createDeliveryStubWithActorDelegation(customerSub, SwiftDropRoles.ROLE_CUSTOMER, null);

        assertThatThrownBy(() -> stub.validateQuote(ValidateQuoteRequest.newBuilder()
                .setQuoteId(quoteId.toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
    }

    // =========================================================================
    // Admin Read Projection Tests: GetPricingAdminSummary
    // =========================================================================

    @Test
    @DisplayName("Admin query for pricing summary succeeds with bounded results")
    void adminQueryPricingSummary_withAdminRole_succeeds() {
        ZoneEntity z1 = new ZoneEntity(UUID.randomUUID(), "ZONE_1", "Colombo", "Western", 24, true);
        ZoneEntity z2 = new ZoneEntity(UUID.randomUUID(), "ZONE_2", "Kandy", "Central", 48, true);
        when(zoneRepository.findAll()).thenReturn(List.of(z1, z2));

        RateVersionEntity rv = new RateVersionEntity(rateVersionId, "RATE-2026-V1", "LKR", RateVersionStatus.ACTIVE, baseTime, "Active rate");
        when(rateVersionRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(rv));
        when(rateRuleRepository.findByRateVersionId(rateVersionId)).thenReturn(Collections.emptyList());

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createGraphQLStubWithActorDelegation("admin-123", SwiftDropRoles.ROLE_ADMIN);

        PricingAdminSummaryResponse response = stub.getPricingAdminSummary(PricingAdminSummaryRequest.newBuilder()
                .setPage(0)
                .setPageSize(20)
                .build());

        assertThat(response.getTotalZones()).isEqualTo(2);
        assertThat(response.getZonesList()).hasSize(2);
        assertThat(response.getZones(0).getZoneCode()).isEqualTo("ZONE_1");
        assertThat(response.getZones(0).getIsActive()).isTrue();
        assertThat(response.getTotalRateVersions()).isEqualTo(1);
        assertThat(response.getRateVersions(0).getVersionCode()).isEqualTo("RATE-2026-V1");
        assertThat(response.getRateVersions(0).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Admin summary request with CUSTOMER role is denied with PERMISSION_DENIED")
    void adminSummary_withCustomerRole_denied() {
        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createGraphQLStubWithActorDelegation("customer-123", SwiftDropRoles.ROLE_CUSTOMER);

        assertThatThrownBy(() -> stub.getPricingAdminSummary(PricingAdminSummaryRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("Admin summary page size is bounded to max 50")
    void adminSummary_pageSizeBoundedTo50() {
        List<ZoneEntity> manyZones = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            manyZones.add(new ZoneEntity(UUID.randomUUID(), "ZONE_" + i, "Name " + i, "Desc", 24, true));
        }
        when(zoneRepository.findAll()).thenReturn(manyZones);
        when(rateVersionRepository.findAllByOrderByCreatedAtDesc()).thenReturn(Collections.emptyList());

        PricingInternalServiceGrpc.PricingInternalServiceBlockingStub stub =
                createGraphQLStubWithActorDelegation("admin-123", SwiftDropRoles.ROLE_ADMIN);

        PricingAdminSummaryResponse response = stub.getPricingAdminSummary(PricingAdminSummaryRequest.newBuilder()
                .setPage(0)
                .setPageSize(100) // Requesting 100
                .build());

        assertThat(response.getTotalZones()).isEqualTo(60);
        assertThat(response.getZonesList()).hasSize(50); // Clamped to 50
    }
}
