package com.swiftdrop.accountsservice.grpc;

import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.merchant.credential.MerchantApiKeyValidator;
import com.swiftdrop.accountsservice.merchant.credential.MerchantKeyValidationResult;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffProfile;
import com.swiftdrop.accountsservice.staff.StaffProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.grpc.SwiftDropGrpcServerSecurityInterceptor;
import com.swiftdrop.security.service.JwtServiceTokenValidator;
import com.swiftdrop.security.service.ServiceTokenValidator;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountsInternalServiceGrpcTest {

    private final JwtTestHelper jwtHelper = new JwtTestHelper();

    private AccountProfileRepository accountProfileRepository;
    private MerchantRepository merchantRepository;
    private StaffProfileRepository staffProfileRepository;
    private MerchantApiKeyValidator apiKeyValidator;

    private Server server;
    private ManagedChannel channel;

    private final UUID customerProfileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String customerSub = "cust-alice-123";

    private final UUID merchantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final String merchantSub = "merch-bob-456";

    private final UUID courierProfileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String courierSub = "courier-kamal-789";

    private final String dispatcherSub = "dispatcher-nimal-001";
    private final String adminSub = "admin-john-999";

    @BeforeEach
    void setUp() throws Exception {
        accountProfileRepository = mock(AccountProfileRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        staffProfileRepository = mock(StaffProfileRepository.class);
        apiKeyValidator = mock(MerchantApiKeyValidator.class);

        AccountsInternalServiceImpl serviceImpl = new AccountsInternalServiceImpl(
                accountProfileRepository,
                merchantRepository,
                staffProfileRepository,
                apiKeyValidator
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

    private AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub createStub(
            String serviceName,
            String actorSub,
            String actorRole) {
        Metadata headers = new Metadata();
        if (serviceName != null) {
            headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, jwtHelper.createSignedServiceToken(serviceName));
        }
        if (actorSub != null) {
            headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
            if (actorRole != null) {
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, actorRole);
                headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, jwtHelper.createSignedUserToken(actorSub, List.of(actorRole)));
            }
        }
        return AccountsInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private AccountProfile mockCustomerProfile(UUID id, String sub, String email, String name, String phone) {
        AccountProfile ap = mock(AccountProfile.class);
        when(ap.getId()).thenReturn(id);
        when(ap.getKeycloakSub()).thenReturn(sub);
        when(ap.getEmail()).thenReturn(email);
        when(ap.getFullName()).thenReturn(name);
        when(ap.getPhoneNumber()).thenReturn(phone);
        when(ap.getCreatedAt()).thenReturn(Instant.parse("2026-10-01T10:00:00Z"));
        return ap;
    }

    // =========================================================================
    // 1. CheckAccountEligibility Tests (#109)
    // =========================================================================

    @Test
    @DisplayName("CheckAccountEligibility: active customer querying self returns ELIGIBLE")
    void testCheckAccountEligibility_CustomerEligible() {
        AccountProfile customer = mockCustomerProfile(customerProfileId, customerSub, "alice@example.com", "Alice Customer", "+94771111111");
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(customer));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(customerSub)
                        .setRequiredRole("CUSTOMER")
                        .build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getIsEligible()).isTrue();
        assertThat(response.getRejectionReason()).isEqualTo("ELIGIBLE");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getSubject()).isEqualTo(customerSub);
    }

    @Test
    @DisplayName("CheckAccountEligibility: unknown customer profile returns PROFILE_NOT_FOUND")
    void testCheckAccountEligibility_CustomerNotFound() {
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.empty());

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(customerSub)
                        .setRequiredRole("CUSTOMER")
                        .build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getIsEligible()).isFalse();
        assertThat(response.getRejectionReason()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(response.getStatus()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("CheckAccountEligibility: approved merchant returns ELIGIBLE")
    void testCheckAccountEligibility_MerchantApproved() {
        Merchant merchant = new Merchant(
                merchantId, customerProfileId, merchantSub,
                "Acme Logistics", "REG-12345", MerchantStatus.APPROVED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findByKeycloakSub(merchantSub)).thenReturn(Optional.of(merchant));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), merchantSub, "MERCHANT");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(merchantSub)
                        .setRequiredRole("MERCHANT")
                        .build()
        );

        assertThat(response.getIsEligible()).isTrue();
        assertThat(response.getRejectionReason()).isEqualTo("ELIGIBLE");
        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getMerchantId()).isEqualTo(merchantId.toString());
        assertThat(response.getBusinessName()).isEqualTo("Acme Logistics");
    }

    @Test
    @DisplayName("CheckAccountEligibility: pending merchant returns MERCHANT_NOT_APPROVED")
    void testCheckAccountEligibility_MerchantPending() {
        Merchant merchant = new Merchant(
                merchantId, customerProfileId, merchantSub,
                "Pending Store", "REG-555", MerchantStatus.PENDING,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findByKeycloakSub(merchantSub)).thenReturn(Optional.of(merchant));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), merchantSub, "MERCHANT");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(merchantSub)
                        .setRequiredRole("MERCHANT")
                        .build()
        );

        assertThat(response.getIsEligible()).isFalse();
        assertThat(response.getRejectionReason()).isEqualTo("MERCHANT_PENDING");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("CheckAccountEligibility: suspended merchant returns MERCHANT_SUSPENDED")
    void testCheckAccountEligibility_MerchantSuspended() {
        Merchant merchant = new Merchant(
                merchantId, customerProfileId, merchantSub,
                "Suspended Store", "REG-999", MerchantStatus.SUSPENDED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findByKeycloakSub(merchantSub)).thenReturn(Optional.of(merchant));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), merchantSub, "MERCHANT");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(merchantSub)
                        .setRequiredRole("MERCHANT")
                        .build()
        );

        assertThat(response.getIsEligible()).isFalse();
        assertThat(response.getRejectionReason()).isEqualTo("MERCHANT_SUSPENDED");
        assertThat(response.getStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("CheckAccountEligibility: active courier returns ELIGIBLE")
    void testCheckAccountEligibility_CourierActive() {
        StaffProfile courier = new StaffProfile(
                courierProfileId, customerProfileId, courierSub,
                "STF-001", StaffRole.COURIER, "LOGISTICS", "ZONE-COLOMBO",
                StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );
        when(staffProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courier));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), courierSub, "COURIER");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(courierSub)
                        .setRequiredRole("COURIER")
                        .build()
        );

        assertThat(response.getIsEligible()).isTrue();
        assertThat(response.getRejectionReason()).isEqualTo("ELIGIBLE");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("CheckAccountEligibility: inactive courier returns COURIER_INACTIVE")
    void testCheckAccountEligibility_CourierInactive() {
        StaffProfile courier = new StaffProfile(
                courierProfileId, customerProfileId, courierSub,
                "STF-001", StaffRole.COURIER, "LOGISTICS", "ZONE-COLOMBO",
                StaffStatus.INACTIVE, Instant.now(), Instant.now()
        );
        when(staffProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courier));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), courierSub, "COURIER");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(courierSub)
                        .setRequiredRole("COURIER")
                        .build()
        );

        assertThat(response.getIsEligible()).isFalse();
        assertThat(response.getRejectionReason()).isEqualTo("STAFF_INACTIVE");
        assertThat(response.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("CheckAccountEligibility: empty subject fails with INVALID_ARGUMENT")
    void testCheckAccountEligibility_EmptySubject_InvalidArgument() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject("   ")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("CheckAccountEligibility: customer querying another subject fails with PERMISSION_DENIED (anti-spoofing)")
    void testCheckAccountEligibility_CrossSubjectSpoofing_Denied() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject("another-user-sub")
                        .setRequiredRole("CUSTOMER")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("CheckAccountEligibility: admin querying another subject succeeds")
    void testCheckAccountEligibility_AdminQueryingSubject_Allowed() {
        AccountProfile customer = mockCustomerProfile(customerProfileId, customerSub, "alice@example.com", "Alice Customer", "+94771111111");
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(customer));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), adminSub, "ADMIN");

        AccountEligibilityResponse response = stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(customerSub)
                        .setRequiredRole("CUSTOMER")
                        .build()
        );

        assertThat(response.getIsEligible()).isTrue();
        assertThat(response.getSubject()).isEqualTo(customerSub);
    }

    // =========================================================================
    // 2. GetScopedProfileSummary Tests (#109)
    // =========================================================================

    @Test
    @DisplayName("GetScopedProfileSummary: customer querying self with MINIMAL scope receives minimal fields, no email")
    void testGetScopedProfileSummary_CustomerSelf_MinimalScope() {
        AccountProfile customer = mockCustomerProfile(customerProfileId, customerSub, "alice@example.com", "Alice Customer", "+94771111111");
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(customer));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        ScopedProfileSummaryResponse response = stub.getScopedProfileSummary(
                ScopedProfileSummaryRequest.newBuilder()
                        .setSubject(customerSub)
                        .setScope("MINIMAL")
                        .build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getSubject()).isEqualTo(customerSub);
        assertThat(response.getFullName()).isEqualTo("Alice Customer");
        assertThat(response.getRole()).isEqualTo("CUSTOMER");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        // MINIMAL scope MUST NOT expose email
        assertThat(response.getEmail()).isEmpty();
    }

    @Test
    @DisplayName("GetScopedProfileSummary: customer querying self with CUSTOMER scope receives email")
    void testGetScopedProfileSummary_CustomerSelf_CustomerScope() {
        AccountProfile customer = mockCustomerProfile(customerProfileId, customerSub, "alice@example.com", "Alice Customer", "+94771111111");
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(customer));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        ScopedProfileSummaryResponse response = stub.getScopedProfileSummary(
                ScopedProfileSummaryRequest.newBuilder()
                        .setSubject(customerSub)
                        .setScope("CUSTOMER")
                        .build()
        );

        assertThat(response.getEmail()).isEqualTo("alice@example.com");
        assertThat(response.getFullName()).isEqualTo("Alice Customer");
    }

    @Test
    @DisplayName("GetScopedProfileSummary: dispatcher querying courier with COURIER scope receives zone but no email")
    void testGetScopedProfileSummary_DispatcherQueryingCourier_CourierScope() {
        StaffProfile courier = new StaffProfile(
                courierProfileId, customerProfileId, courierSub,
                "STF-001", StaffRole.COURIER, "LOGISTICS", "ZONE-COLOMBO",
                StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );
        AccountProfile courierAcct = mockCustomerProfile(customerProfileId, courierSub, "kamal@swiftdrop.internal", "Kamal Perera", "+94772222222");

        when(staffProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courier));
        when(accountProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierAcct));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), dispatcherSub, "DISPATCHER");

        ScopedProfileSummaryResponse response = stub.getScopedProfileSummary(
                ScopedProfileSummaryRequest.newBuilder()
                        .setSubject(courierSub)
                        .setScope("COURIER")
                        .build()
        );

        assertThat(response.getSubject()).isEqualTo(courierSub);
        assertThat(response.getFullName()).isEqualTo("Kamal Perera");
        assertThat(response.getAssignedZone()).isEqualTo("ZONE-COLOMBO");
        assertThat(response.getStaffId()).isEqualTo("STF-001");
        // Dispatcher viewing courier must NOT receive internal email
        assertThat(response.getEmail()).isEmpty();
    }

    @Test
    @DisplayName("GetScopedProfileSummary: customer attempting cross-subject profile query fails with PERMISSION_DENIED")
    void testGetScopedProfileSummary_CrossSubjectQuery_Denied() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.getScopedProfileSummary(
                ScopedProfileSummaryRequest.newBuilder()
                        .setSubject("other-user-sub")
                        .setScope("MINIMAL")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("GetScopedProfileSummary: unsupported scope fails with INVALID_ARGUMENT")
    void testGetScopedProfileSummary_UnsupportedScope_InvalidArgument() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.getScopedProfileSummary(
                ScopedProfileSummaryRequest.newBuilder()
                        .setSubject(customerSub)
                        .setScope("INVALID_SCOPE_XYZ")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("GetScopedProfileSummary: non-existent profile fails with NOT_FOUND")
    void testGetScopedProfileSummary_NotFound() {
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.empty());
        when(staffProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.empty());
        when(merchantRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.empty());

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.getScopedProfileSummary(
                ScopedProfileSummaryRequest.newBuilder()
                        .setSubject(customerSub)
                        .setScope("MINIMAL")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.NOT_FOUND));
    }

    // =========================================================================
    // 3. GetBatchProfileSummaries Tests (#109)
    // =========================================================================

    @Test
    @DisplayName("GetBatchProfileSummaries: dispatcher requesting batch summaries receives mapped items")
    void testGetBatchProfileSummaries_Dispatcher_Success() {
        AccountProfile customer = mockCustomerProfile(customerProfileId, customerSub, "alice@example.com", "Alice Customer", "+94771111111");
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(customer));

        StaffProfile courier = new StaffProfile(
                courierProfileId, customerProfileId, courierSub,
                "STF-001", StaffRole.COURIER, "LOGISTICS", "ZONE-COLOMBO",
                StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );
        AccountProfile courierAcct = mockCustomerProfile(customerProfileId, courierSub, "kamal@swiftdrop.internal", "Kamal Perera", "+94772222222");
        when(staffProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courier));
        when(accountProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierAcct));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, "DISPATCHER");

        BatchProfileSummariesResponse response = stub.getBatchProfileSummaries(
                BatchProfileSummariesRequest.newBuilder()
                        .addSubjects(customerSub)
                        .addSubjects(courierSub)
                        .setScope("MINIMAL")
                        .build()
        );

        assertThat(response.getItemsList()).hasSize(2);
        assertThat(response.getItems(0).getSubject()).isEqualTo(customerSub);
        assertThat(response.getItems(1).getSubject()).isEqualTo(courierSub);
    }

    @Test
    @DisplayName("GetBatchProfileSummaries: batch size exceeding 50 is rejected with INVALID_ARGUMENT")
    void testGetBatchProfileSummaries_ExceedsCap_Rejected() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), dispatcherSub, "DISPATCHER");

        BatchProfileSummariesRequest.Builder requestBuilder = BatchProfileSummariesRequest.newBuilder()
                .setScope("MINIMAL");
        for (int i = 0; i < 51; i++) {
            requestBuilder.addSubjects("sub-" + i);
        }

        assertThatThrownBy(() -> stub.getBatchProfileSummaries(requestBuilder.build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("GetBatchProfileSummaries: customer role calling batch query fails with PERMISSION_DENIED")
    void testGetBatchProfileSummaries_CustomerCaller_Denied() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.getBatchProfileSummaries(
                BatchProfileSummariesRequest.newBuilder()
                        .addSubjects(customerSub)
                        .setScope("MINIMAL")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    // =========================================================================
    // 4. GetAvailableCouriersSummary Tests (#109, #135)
    // =========================================================================

    @Test
    @DisplayName("GetAvailableCouriersSummary: dispatcher queries available couriers bounded by page size")
    void testGetAvailableCouriersSummary_Dispatcher_Success() {
        StaffProfile courier1 = new StaffProfile(
                courierProfileId, customerProfileId, courierSub,
                "STF-001", StaffRole.COURIER, "LOGISTICS", "ZONE-COLOMBO",
                StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );
        AccountProfile courierAcct = mockCustomerProfile(customerProfileId, courierSub, "kamal@swiftdrop.internal", "Kamal Perera", "+94772222222");

        when(staffProfileRepository.findByRoleAndAssignedZone(StaffRole.COURIER, "ZONE-COLOMBO"))
                .thenReturn(List.of(courier1));
        when(accountProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierAcct));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), dispatcherSub, "DISPATCHER");

        AvailableCouriersSummaryResponse response = stub.getAvailableCouriersSummary(
                AvailableCouriersSummaryRequest.newBuilder()
                        .setZoneFilter("ZONE-COLOMBO")
                        .setPage(0)
                        .setPageSize(20)
                        .build()
        );

        assertThat(response).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getItemsList()).hasSize(1);
        CourierSummaryItem item = response.getItems(0);
        assertThat(item.getCourierSub()).isEqualTo(courierSub);
        assertThat(item.getFullName()).isEqualTo("Kamal Perera");
        assertThat(item.getAssignedZone()).isEqualTo("ZONE-COLOMBO");
        assertThat(item.getStaffId()).isEqualTo("STF-001");
        assertThat(item.getIsEligible()).isTrue();
    }

    @Test
    @DisplayName("GetAvailableCouriersSummary: customer caller is rejected with PERMISSION_DENIED")
    void testGetAvailableCouriersSummary_CustomerCaller_Denied() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.getAvailableCouriersSummary(
                AvailableCouriersSummaryRequest.newBuilder()
                        .setPage(0)
                        .setPageSize(10)
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("GetAvailableCouriersSummary: negative page is rejected with INVALID_ARGUMENT")
    void testGetAvailableCouriersSummary_NegativePage_InvalidArgument() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), dispatcherSub, "DISPATCHER");

        assertThatThrownBy(() -> stub.getAvailableCouriersSummary(
                AvailableCouriersSummaryRequest.newBuilder()
                        .setPage(-1)
                        .setPageSize(10)
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    // =========================================================================
    // 5. Security & Delegation Tests (#109)
    // =========================================================================

    @Test
    @DisplayName("Missing service token fails with UNAUTHENTICATED")
    void testMissingServiceToken_Unauthenticated() {
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel);

        assertThatThrownBy(() -> stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(customerSub)
                        .setRequiredRole("CUSTOMER")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("Unauthorized caller service fails with PERMISSION_DENIED")
    void testUnauthorizedCallerService_PermissionDenied() {
        // Only DELIVERY_SERVICE and GRAPHQL_QUERY_SERVICE are allowed for CheckAccountEligibility
        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        assertThatThrownBy(() -> stub.checkAccountEligibility(
                AccountEligibilityRequest.newBuilder()
                        .setSubject(customerSub)
                        .setRequiredRole("CUSTOMER")
                        .build()
        ))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    // =========================================================================
    // 6. Frozen Backwards-Compatible RPC Tests
    // =========================================================================

    @Test
    @DisplayName("Frozen RPC checkCourierEligibility functions correctly")
    void testCheckCourierEligibility_Frozen_Success() {
        StaffProfile courier = new StaffProfile(
                courierProfileId, customerProfileId, courierSub,
                "STF-001", StaffRole.COURIER, "LOGISTICS", "ZONE-COLOMBO",
                StaffStatus.ACTIVE, Instant.now(), Instant.now()
        );
        AccountProfile courierAcct = mockCustomerProfile(customerProfileId, courierSub, "kamal@swiftdrop.internal", "Kamal Perera", "+94772222222");
        when(staffProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courier));
        when(accountProfileRepository.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierAcct));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                createStub(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName(), courierSub, "COURIER");

        CourierEligibilityResponse response = stub.checkCourierEligibility(
                CourierEligibilityRequest.newBuilder().setCourierSub(courierSub).build()
        );

        assertThat(response.getIsEligible()).isTrue();
        assertThat(response.getFullName()).isEqualTo("Kamal Perera");
    }

    @Test
    @DisplayName("Frozen RPC resolveRecipientContact functions correctly for COMMUNICATIONS_SERVICE")
    void testResolveRecipientContact_Frozen_Success() {
        AccountProfile customer = mockCustomerProfile(customerProfileId, customerSub, "alice@example.com", "Alice Customer", "+94771111111");
        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(customer));

        // Communications service calls resolveRecipientContact without delegated human actor
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN,
                jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE.getServiceName()));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        RecipientContactResponse response = stub.resolveRecipientContact(
                RecipientContactRequest.newBuilder().setRecipientSub(customerSub).build()
        );

        assertThat(response.getRecipientSub()).isEqualTo(customerSub);
        assertThat(response.getEmail()).isEqualTo("alice@example.com");
        assertThat(response.getFullName()).isEqualTo("Alice Customer");
        assertThat(response.getPhoneNumber()).isEqualTo("+94771111111");
    }

    @Test
    @DisplayName("Frozen RPC validateMerchantKeyDigest functions correctly")
    void testValidateMerchantKeyDigest_Frozen_Success() {
        when(apiKeyValidator.validateKeyDigest(eq("test_prefix"), eq("hash_hex_123")))
                .thenReturn(MerchantKeyValidationResult.valid(merchantId, "Acme", MerchantStatus.APPROVED, List.of("parcels:create")));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN,
                jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName()));

        AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
                AccountsInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        MerchantKeyDigestResponse response = stub.validateMerchantKeyDigest(
                MerchantKeyDigestRequest.newBuilder()
                        .setKeyPrefix("test_prefix")
                        .setKeyDigest("hash_hex_123")
                        .build()
        );

        assertThat(response.getIsValid()).isTrue();
        assertThat(response.getMerchantId()).isEqualTo(merchantId.toString());
        assertThat(response.getBusinessName()).isEqualTo("Acme");
    }
}
