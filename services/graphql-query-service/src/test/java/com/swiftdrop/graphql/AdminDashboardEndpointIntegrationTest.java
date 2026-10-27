package com.swiftdrop.graphql;

import com.google.protobuf.Timestamp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.PendingMerchantItem;
import com.swiftdrop.accounts.v1.PendingMerchantsSummaryRequest;
import com.swiftdrop.accounts.v1.PendingMerchantsSummaryResponse;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.delivery.v1.GetDeliveryStatsRequest;
import com.swiftdrop.delivery.v1.GetDeliveryStatsResponse;
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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "debug=false",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@Import(AdminDashboardEndpointIntegrationTest.IntegrationFixture.class)
class AdminDashboardEndpointIntegrationTest {

    static final RSAKey TRUSTED_KEY = generateKey();
    static final RSAKey FORGED_KEY = generateKey();
    static final String ISSUER = "https://issuer.test";
    static final String AUDIENCE = "swiftdrop-api";
    static final String ADMIN_SUB = "admin-sub-999";

    static final AtomicReference<PendingMerchantsSummaryResponse> accountsResponse = new AtomicReference<>();
    static final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();
    static final AtomicReference<GetDeliveryStatsResponse> deliveryResponse = new AtomicReference<>();
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
                        public void getPendingMerchantsSummary(PendingMerchantsSummaryRequest req, StreamObserver<PendingMerchantsSummaryResponse> out) {
                            if (accountsError.get() != null) {
                                out.onError(accountsError.get());
                            } else if (accountsResponse.get() != null) {
                                out.onNext(accountsResponse.get());
                                out.onCompleted();
                            } else {
                                out.onError(Status.UNAVAILABLE.withDescription("Accounts not configured").asRuntimeException());
                            }
                        }
                    }).build().start();

            ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
            return TestClients.inProcess(channel, () -> "service-test-token");
        }

        @Bean(destroyMethod = "close")
        AuthenticatedReadClients deliveryReadClients() throws Exception {
            String serverName = InProcessServerBuilder.generateName();
            deliveryServer = InProcessServerBuilder.forName(serverName).directExecutor()
                    .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                        @Override
                        public void getDeliveryStatsInternal(GetDeliveryStatsRequest req, StreamObserver<GetDeliveryStatsResponse> out) {
                            if (deliveryError.get() != null) {
                                out.onError(deliveryError.get());
                            } else if (deliveryResponse.get() != null) {
                                out.onNext(deliveryResponse.get());
                                out.onCompleted();
                            } else {
                                out.onError(Status.UNAVAILABLE.withDescription("Delivery not configured").asRuntimeException());
                            }
                        }
                    }).build().start();

            ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
            return TestClients.inProcess(channel, () -> "service-test-token");
        }

        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(TRUSTED_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER),
                    new AudienceValidator(AUDIENCE),
                    new SubjectValidator()
            ));
            return decoder;
        }
    }

    @AfterAll
    static void shutdownServers() {
        if (accountsServer != null) accountsServer.shutdownNow();
        if (deliveryServer != null) deliveryServer.shutdownNow();
    }

    @BeforeEach
    void resetMocks() {
        accountsResponse.set(null);
        accountsError.set(null);
        deliveryResponse.set(null);
        deliveryError.set(null);
    }

    private static String createToken(RSAKey key, String sub, String role, String issuer, String audience, long expirySeconds) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(120)))
                .expirationTime(Date.from(Instant.now().plusSeconds(expirySeconds)))
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private String validToken(String sub, String role) throws Exception {
        return createToken(TRUSTED_KEY, sub, role, ISSUER, AUDIENCE, 600);
    }

    private MvcResult executeQuery(String token, String query) throws Exception {
        var req = post("/graphql")
                .contentType("application/json")
                .header("x-swiftdrop-correlation-id", "corr-test-123")
                .content("{\"query\":\"" + query + "\"}");
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        var result = mvc.perform(req).andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }

    @Test
    @DisplayName("Admin dashboard query with valid ADMIN token returns authoritative data")
    void testAdminDashboard_Success() throws Exception {
        String mId = UUID.randomUUID().toString();
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(12)
                .setPage(0)
                .setPageSize(20)
                .addItems(PendingMerchantItem.newBuilder()
                        .setMerchantId(mId)
                        .setBusinessName("Ceylon Spices")
                        .setRegistrationNumber("PV-12345")
                        .setStatus("PENDING")
                        .setSubmittedAt(Timestamp.newBuilder().setSeconds(1761800000L).build())
                        .build())
                .build());

        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder()
                .setTotalCount(200)
                .setDeliveredCount(90)
                .setFailedCount(10)
                .setInTransitCount(75)
                .setCreatedCount(25)
                .build());

        String token = validToken(ADMIN_SUB, "ADMIN");
        String query = "{adminDashboard{pendingMerchantsCount totalParcelsCount activeParcelsCount terminalSuccessRate recentPendingMerchants{merchantId businessName registrationNumber submittedAt}}}";

        MvcResult result = executeQuery(token, query);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"pendingMerchantsCount\":12");
        assertThat(body).contains("\"totalParcelsCount\":200");
        assertThat(body).contains("\"activeParcelsCount\":75");
        assertThat(body).contains("\"terminalSuccessRate\":0.9");
        assertThat(body).contains("\"merchantId\":\"" + mId + "\"");
        assertThat(body).contains("\"businessName\":\"Ceylon Spices\"");
        assertThat(body).contains("\"registrationNumber\":\"PV-12345\"");
        assertThat(body).doesNotContain("errors");
    }

    @Test
    @DisplayName("Anonymous query without token is rejected with 401")
    void testAnonymous_Rejected() throws Exception {
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(null, query);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("CUSTOMER role is rejected with FORBIDDEN")
    void testCustomer_Rejected() throws Exception {
        String token = validToken("cust-1", "CUSTOMER");
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(token, query);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("MERCHANT role is rejected with FORBIDDEN")
    void testMerchant_Rejected() throws Exception {
        String token = validToken("merch-1", "MERCHANT");
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(token, query);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("COURIER role is rejected with FORBIDDEN")
    void testCourier_Rejected() throws Exception {
        String token = validToken("courier-1", "COURIER");
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(token, query);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("DISPATCHER role is rejected with FORBIDDEN")
    void testDispatcher_Rejected() throws Exception {
        String token = validToken("disp-1", "DISPATCHER");
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(token, query);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"FORBIDDEN\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("Forged token signed with wrong key is rejected with 401")
    void testForgedToken_Rejected() throws Exception {
        String forged = createToken(FORGED_KEY, ADMIN_SUB, "ADMIN", ISSUER, AUDIENCE, 600);
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(forged, query);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Expired token is rejected with 401")
    void testExpiredToken_Rejected() throws Exception {
        String expired = createToken(TRUSTED_KEY, ADMIN_SUB, "ADMIN", ISSUER, AUDIENCE, -60);
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(expired, query);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Accounts provider DEADLINE_EXCEEDED maps safely to GraphQL error")
    void testAccountsDeadlineExceeded() throws Exception {
        accountsError.set(Status.DEADLINE_EXCEEDED.withDescription("Accounts timed out").asRuntimeException());
        deliveryResponse.set(GetDeliveryStatsResponse.newBuilder().build());

        String token = validToken(ADMIN_SUB, "ADMIN");
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(token, query);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"DEADLINE_EXCEEDED\"");
        assertThat(body).doesNotContain("Accounts timed out"); // Sanitized
    }

    @Test
    @DisplayName("Delivery provider UNAVAILABLE maps safely to GraphQL error")
    void testDeliveryUnavailable() throws Exception {
        accountsResponse.set(PendingMerchantsSummaryResponse.newBuilder().build());
        deliveryError.set(Status.UNAVAILABLE.withDescription("Delivery down").asRuntimeException());

        String token = validToken(ADMIN_SUB, "ADMIN");
        String query = "{adminDashboard{totalParcelsCount}}";
        MvcResult result = executeQuery(token, query);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":\"UNAVAILABLE\"");
        assertThat(body).doesNotContain("Delivery down"); // Sanitized
    }
}
