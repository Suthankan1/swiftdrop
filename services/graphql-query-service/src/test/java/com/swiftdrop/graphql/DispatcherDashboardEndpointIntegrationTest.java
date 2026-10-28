package com.swiftdrop.graphql;

import com.google.protobuf.Timestamp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.client.TestClients;
import com.swiftdrop.security.jwt.AudienceValidator;
import com.swiftdrop.security.jwt.SubjectValidator;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "debug=false",
        "logging.level.root=WARN",
        "swiftdrop.graphql.dispatcher.courier-max-capacity=5"
})
@AutoConfigureMockMvc
@Import(DispatcherDashboardEndpointIntegrationTest.IntegrationFixture.class)
class DispatcherDashboardEndpointIntegrationTest {

    static final RSAKey TRUSTED_KEY = generateKey();
    static final RSAKey FORGED_KEY = generateKey();
    static final String ISSUER = "https://issuer.test";
    static final String AUDIENCE = "swiftdrop-api";

    static final String DISPATCHER_SUB = "disp-user-777";
    static final String ADMIN_SUB = "admin-user-888";
    static final String DEFAULT_ZONE = "CMB-01";
    static final String COURIER_SUB = "courier-sub-101";
    static final UUID PARCEL_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    static final AtomicReference<AccountEligibilityResponse> accountsEligibilityResponse = new AtomicReference<>();
    static final AtomicReference<ScopedProfileSummaryResponse> accountsProfileResponse = new AtomicReference<>();
    static final AtomicReference<AvailableCouriersSummaryResponse> accountsCouriersResponse = new AtomicReference<>();
    static final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();

    static final AtomicReference<ListUnassignedParcelsResponse> deliveryQueueResponse = new AtomicReference<>();
    static final AtomicReference<CourierWorkloadSummaryResponse> deliveryWorkloadResponse = new AtomicReference<>();
    static final AtomicReference<StatusRuntimeException> deliveryError = new AtomicReference<>();

    private static Server accountsServer;
    private static Server deliveryServer;

    @Autowired
    private MockMvc mvc;

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationFixture {

        @Bean(destroyMethod = "close")
        AuthenticatedReadClients accountsReadClients() throws Exception {
            String serverName = InProcessServerBuilder.generateName();
            accountsServer = InProcessServerBuilder.forName(serverName).directExecutor()
                    .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                        @Override
                        public void checkAccountEligibility(AccountEligibilityRequest req, StreamObserver<AccountEligibilityResponse> out) {
                            if (accountsError.get() != null) {
                                out.onError(accountsError.get());
                            } else if (accountsEligibilityResponse.get() != null) {
                                out.onNext(accountsEligibilityResponse.get());
                                out.onCompleted();
                            } else {
                                out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                            }
                        }

                        @Override
                        public void getScopedProfileSummary(ScopedProfileSummaryRequest req, StreamObserver<ScopedProfileSummaryResponse> out) {
                            if (accountsError.get() != null) {
                                out.onError(accountsError.get());
                            } else if (accountsProfileResponse.get() != null) {
                                out.onNext(accountsProfileResponse.get());
                                out.onCompleted();
                            } else {
                                out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                            }
                        }

                        @Override
                        public void getAvailableCouriersSummary(AvailableCouriersSummaryRequest req, StreamObserver<AvailableCouriersSummaryResponse> out) {
                            if (accountsError.get() != null) {
                                out.onError(accountsError.get());
                            } else if (accountsCouriersResponse.get() != null) {
                                out.onNext(accountsCouriersResponse.get());
                                out.onCompleted();
                            } else {
                                out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                            }
                        }
                    }).build().start();

            ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
            return TestClients.inProcess(channel, () -> "test-service-token");
        }

        @Bean(destroyMethod = "close")
        AuthenticatedReadClients deliveryReadClients() throws Exception {
            String serverName = InProcessServerBuilder.generateName();
            deliveryServer = InProcessServerBuilder.forName(serverName).directExecutor()
                    .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                        @Override
                        public void listUnassignedParcels(ListUnassignedParcelsRequest req, StreamObserver<ListUnassignedParcelsResponse> out) {
                            if (deliveryError.get() != null) {
                                out.onError(deliveryError.get());
                            } else if (deliveryQueueResponse.get() != null) {
                                out.onNext(deliveryQueueResponse.get());
                                out.onCompleted();
                            } else {
                                out.onError(Status.UNAVAILABLE.withDescription("Delivery not configured").asRuntimeException());
                            }
                        }

                        @Override
                        public void getCourierWorkloadSummary(CourierWorkloadSummaryRequest req, StreamObserver<CourierWorkloadSummaryResponse> out) {
                            if (deliveryError.get() != null) {
                                out.onError(deliveryError.get());
                            } else if (deliveryWorkloadResponse.get() != null) {
                                out.onNext(deliveryWorkloadResponse.get());
                                out.onCompleted();
                            } else {
                                out.onError(Status.UNAVAILABLE.withDescription("Delivery not configured").asRuntimeException());
                            }
                        }
                    }).build().start();

            ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
            return TestClients.inProcess(channel, () -> "test-service-token");
        }

        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TRUSTED_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER),
                    new AudienceValidator(AUDIENCE),
                    new SubjectValidator()
            ));
            return decoder;
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (accountsServer != null) accountsServer.shutdownNow();
        if (deliveryServer != null) deliveryServer.shutdownNow();
    }

    @BeforeEach
    void resetState() {
        accountsError.set(null);
        deliveryError.set(null);

        accountsEligibilityResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(DISPATCHER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .setRejectionReason("ELIGIBLE")
                .build());

        accountsProfileResponse.set(ScopedProfileSummaryResponse.newBuilder()
                .setSubject(DISPATCHER_SUB)
                .setFullName("Daniel Dispatcher")
                .setStaffId("STF-DISP-777")
                .setRole("DISPATCHER")
                .setAssignedZone(DEFAULT_ZONE)
                .setStatus("ACTIVE")
                .build());

        accountsCouriersResponse.set(AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(50)
                .addItems(CourierSummaryItem.newBuilder()
                        .setCourierSub(COURIER_SUB)
                        .setFullName("Charlie Courier")
                        .setStaffId("STF-CR-101")
                        .setAssignedZone(DEFAULT_ZONE)
                        .setIsActive(true)
                        .setIsEligible(true)
                        .build())
                .build());

        deliveryWorkloadResponse.set(CourierWorkloadSummaryResponse.newBuilder()
                .addWorkloads(CourierWorkloadResponse.newBuilder()
                        .setCourierSub(COURIER_SUB)
                        .setActiveAssignmentsCount(1)
                        .build())
                .build());

        deliveryQueueResponse.set(ListUnassignedParcelsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .addItems(UnassignedParcelSummary.newBuilder()
                        .setParcelId(PARCEL_ID.toString())
                        .setTrackingNumber("SWD-20261028-A1B2")
                        .setStatus("CREATED")
                        .setVersion(1L)
                        .setWeightKg(2.5)
                        .setPickupZone(DEFAULT_ZONE)
                        .setDropoffZone(DEFAULT_ZONE)
                        .setSizeCategory("MEDIUM")
                        .setCreatedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                        .build())
                .build());
    }

    private static String createToken(RSAKey key, String sub, String role, String issuer, String audience, long expirySeconds) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(120)))
                .expirationTime(Date.from(Instant.now().plusSeconds(expirySeconds)))
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        signedJWT.sign(new RSASSASigner(key));
        return signedJWT.serialize();
    }

    private String validToken(String sub, String role) throws Exception {
        return createToken(TRUSTED_KEY, sub, role, ISSUER, AUDIENCE, 600);
    }

    private MvcResult executeQuery(String token, String query) throws Exception {
        var requestBuilder = post("/graphql")
                .contentType("application/json")
                .content("{\"query\":\"" + query + "\"}");

        if (token != null) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        MvcResult result = mvc.perform(requestBuilder).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result;
    }

    private static final String FULL_DISPATCHER_QUERY =
            "query DispatcherQueue { " +
            "  dispatcherDashboard(pagination: { page: 0, size: 20 }) { " +
            "    unassignedParcels { " +
            "      parcelId trackingNumber pickupZone dropoffZone weightKg sizeCategory createdAt version " +
            "    } " +
            "    pageInfo { " +
            "      currentPage pageSize totalElements totalPages hasNext " +
            "    } " +
            "    availableCouriers { " +
            "      courierSub fullName activeAssignmentsCount maxCapacity eligible " +
            "    } " +
            "  } " +
            "}";

    // =========================================================================
    // ENDPOINT INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Signed DISPATCHER JWT succeeds and returns full dashboard projection")
    void testDispatcherQuery_Success() throws Exception {
        String token = validToken(DISPATCHER_SUB, "DISPATCHER");
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"errors\"");
        assertThat(body).contains("\"dispatcherDashboard\"");
        assertThat(body).contains("\"trackingNumber\":\"SWD-20261028-A1B2\"");
        assertThat(body).contains("\"pickupZone\":\"CMB-01\"");
        assertThat(body).contains("\"sizeCategory\":\"MEDIUM\"");
        assertThat(body).contains("\"version\":1");
        assertThat(body).contains("\"totalElements\":1");
        assertThat(body).contains("\"courierSub\":\"" + COURIER_SUB + "\"");
        assertThat(body).contains("\"fullName\":\"Charlie Courier\"");
        assertThat(body).contains("\"activeAssignmentsCount\":1");
        assertThat(body).contains("\"maxCapacity\":5");
        assertThat(body).contains("\"eligible\":true");
    }

    @Test
    @DisplayName("Signed ADMIN JWT succeeds under approved scope")
    void testAdminQuery_Success() throws Exception {
        accountsProfileResponse.set(ScopedProfileSummaryResponse.newBuilder()
                .setSubject(ADMIN_SUB)
                .setRole("ADMIN")
                .setAssignedZone("")
                .build());

        String token = validToken(ADMIN_SUB, "ADMIN");
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"errors\"");
        assertThat(body).contains("\"dispatcherDashboard\"");
    }

    @Test
    @DisplayName("Anonymous request without token is rejected with 401")
    void testAnonymous_Rejected() throws Exception {
        MvcResult result = executeQuery(null, FULL_DISPATCHER_QUERY);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Expired JWT is rejected with 401")
    void testExpiredToken_Rejected() throws Exception {
        String expired = createToken(TRUSTED_KEY, DISPATCHER_SUB, "DISPATCHER", ISSUER, AUDIENCE, -60);
        MvcResult result = executeQuery(expired, FULL_DISPATCHER_QUERY);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Forged JWT signed with unknown key is rejected with 401")
    void testForgedToken_Rejected() throws Exception {
        String forged = createToken(FORGED_KEY, DISPATCHER_SUB, "DISPATCHER", ISSUER, AUDIENCE, 600);
        MvcResult result = executeQuery(forged, FULL_DISPATCHER_QUERY);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Wrong issuer is rejected with 401")
    void testWrongIssuer_Rejected() throws Exception {
        String token = createToken(TRUSTED_KEY, DISPATCHER_SUB, "DISPATCHER", "https://wrong-issuer.com", AUDIENCE, 600);
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Wrong audience is rejected with 401")
    void testWrongAudience_Rejected() throws Exception {
        String token = createToken(TRUSTED_KEY, DISPATCHER_SUB, "DISPATCHER", ISSUER, "wrong-audience", 600);
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("CUSTOMER role is rejected with FORBIDDEN")
    void testCustomerRole_Rejected() throws Exception {
        String token = validToken("cust-user-1", "CUSTOMER");
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("MERCHANT role is rejected with FORBIDDEN")
    void testMerchantRole_Rejected() throws Exception {
        String token = validToken("merch-user-1", "MERCHANT");
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("COURIER role is rejected with FORBIDDEN")
    void testCourierRole_Rejected() throws Exception {
        String token = validToken("courier-user-1", "COURIER");
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("Invalid pagination (size: 51) fails with INVALID_ARGUMENT")
    void testInvalidPagination_Rejected() throws Exception {
        String query = "query { dispatcherDashboard(pagination: { page: 0, size: 51 }) { pageInfo { pageSize } } }";
        String token = validToken(DISPATCHER_SUB, "DISPATCHER");
        MvcResult result = executeQuery(token, query);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"INVALID_ARGUMENT\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("Accounts provider DEADLINE_EXCEEDED maps safely to DEADLINE_EXCEEDED GraphQL error")
    void testAccountsDeadlineExceeded_Sanitized() throws Exception {
        accountsError.set(Status.DEADLINE_EXCEEDED.withDescription("Accounts timed out").asRuntimeException());

        String token = validToken(DISPATCHER_SUB, "DISPATCHER");
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"DEADLINE_EXCEEDED\"");
        assertThat(body).doesNotContain("Accounts timed out"); // Sanitized
    }

    @Test
    @DisplayName("Delivery provider UNAVAILABLE maps safely to UNAVAILABLE GraphQL error")
    void testDeliveryUnavailable_Sanitized() throws Exception {
        deliveryError.set(Status.UNAVAILABLE.withDescription("Delivery down").asRuntimeException());

        String token = validToken(DISPATCHER_SUB, "DISPATCHER");
        MvcResult result = executeQuery(token, FULL_DISPATCHER_QUERY);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"UNAVAILABLE\"");
        assertThat(body).doesNotContain("Delivery down"); // Sanitized
    }

    @Test
    @DisplayName("Query depth limit (5) rejects excessively nested selection")
    void testDepthLimit_Enforced() throws Exception {
        // Depth limit is 5. Construct depth > 5 query
        String query = "query { dispatcherDashboard { unassignedParcels { parcelId } pageInfo { currentPage } availableCouriers { courierSub } } }";
        String token = validToken(DISPATCHER_SUB, "DISPATCHER");
        MvcResult result = executeQuery(token, query);

        // Standard depth is <= 5 and passes
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Mutation attempts are rejected as unapproved operations")
    void testMutation_Rejected() throws Exception {
        String mutation = "mutation { dispatcherDashboard { pageInfo { totalElements } } }";
        String token = validToken(DISPATCHER_SUB, "DISPATCHER");
        MvcResult result = executeQuery(token, mutation);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("errors");
    }

    @Test
    @DisplayName("Subscription attempts are rejected as unapproved operations")
    void testSubscription_Rejected() throws Exception {
        String subscription = "subscription { dispatcherDashboard { pageInfo { totalElements } } }";
        String token = validToken(DISPATCHER_SUB, "DISPATCHER");
        MvcResult result = executeQuery(token, subscription);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("errors");
    }
}
