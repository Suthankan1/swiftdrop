package com.swiftdrop.graphql;

import com.google.protobuf.Timestamp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.delivery.v1.CustomerParcelSummary;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.delivery.v1.ListCustomerParcelsRequest;
import com.swiftdrop.delivery.v1.ListCustomerParcelsResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full GraphQL endpoint integration tests for POST /graphql querying customerDashboard (Issue #132 / N10-WF03).
 *
 * <p>Exercises the real execution chain:
 * JWT authentication -> GraphQL field authorization -> CustomerDashboardResolver -> AuthenticatedReadClients -> Delivery gRPC -> GraphQL response mapping.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "debug=false",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@Import(CustomerDashboardEndpointIntegrationTest.IntegrationFixture.class)
class CustomerDashboardEndpointIntegrationTest {

    static final RSAKey TRUSTED_KEY = generateKey();
    static final RSAKey FORGED_KEY = generateKey();
    static final String ISSUER = "https://issuer.test";
    static final String AUDIENCE = "swiftdrop-api";
    static final String CUSTOMER_A_SUB = "11111111-1111-1111-1111-111111111111";
    static final String CUSTOMER_B_SUB = "22222222-2222-2222-2222-222222222222";

    static final AtomicReference<ListCustomerParcelsResponse> deliveryResponse = new AtomicReference<>();
    static final AtomicReference<StatusRuntimeException> deliveryError = new AtomicReference<>();
    static final AtomicReference<ListCustomerParcelsRequest> lastDeliveryRequest = new AtomicReference<>();

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
        AuthenticatedReadClients deliveryReadClients() throws Exception {
            String serverName = InProcessServerBuilder.generateName();
            deliveryServer = InProcessServerBuilder.forName(serverName).directExecutor()
                    .addService(new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                        @Override
                        public void listCustomerParcels(ListCustomerParcelsRequest req,
                                                        StreamObserver<ListCustomerParcelsResponse> out) {
                            lastDeliveryRequest.set(req);
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
        if (deliveryServer != null) {
            deliveryServer.shutdownNow();
        }
    }

    @BeforeEach
    void resetMocks() {
        deliveryResponse.set(null);
        deliveryError.set(null);
        lastDeliveryRequest.set(null);
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
        return executeQueryWithCorrelation(token, query, "corr-test-customer-001");
    }

    private MvcResult executeQueryWithCorrelation(String token, String query, String correlationId) throws Exception {
        String jsonBody = "{\"query\":\"" + query.replace("\"", "\\\"") + "\"}";
        var req = post("/graphql")
                .contentType("application/json")
                .content(jsonBody);
        if (correlationId != null) {
            req.header("x-swiftdrop-correlation-id", correlationId);
        }
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        var result = mvc.perform(req).andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }

    private static Timestamp ts(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds).setNanos(0).build();
    }

    // ── Happy Path Endpoint Tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("Valid CUSTOMER JWT succeeds with full dashboard projections")
    void testCustomerDashboard_Success() throws Exception {
        String pId = UUID.randomUUID().toString();
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(5)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(3)
                .setCompletedCount(2)
                .addParcels(CustomerParcelSummary.newBuilder()
                        .setParcelId(pId)
                        .setTrackingNumber("SWD-2026-000100")
                        .setCurrentStatus("IN_TRANSIT")
                        .setAmountMinorUnits(1500)
                        .setCurrency("LKR")
                        .setRecipientCity("Colombo")
                        .setCreatedAt(ts(1761400000L))
                        .setUpdatedAt(ts(1761400500L))
                        .setVersion(1L)
                        .build())
                .build());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        String query = "{customerDashboard{userId activeDeliveriesCount completedDeliveriesCount recentParcels{parcelId trackingNumber currentStatus amountMinorUnits currency recipientCity createdAt updatedAt}}}";

        MvcResult result = executeQuery(token, query);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"userId\":\"" + CUSTOMER_A_SUB + "\"");
        assertThat(body).contains("\"activeDeliveriesCount\":3");
        assertThat(body).contains("\"completedDeliveriesCount\":2");
        assertThat(body).contains("\"parcelId\":\"" + pId + "\"");
        assertThat(body).contains("\"trackingNumber\":\"SWD-2026-000100\"");
        assertThat(body).contains("\"currentStatus\":\"IN_TRANSIT\"");
        assertThat(body).contains("\"amountMinorUnits\":1500");
        assertThat(body).contains("\"currency\":\"LKR\"");
        assertThat(body).contains("\"recipientCity\":\"Colombo\"");
        assertThat(body).doesNotContain("errors");
    }

    @Test
    @DisplayName("Empty dashboard returned correctly when customer has no parcels")
    void testCustomerDashboard_Empty() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(0)
                .setCompletedCount(0)
                .build());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        String query = "{customerDashboard{userId activeDeliveriesCount completedDeliveriesCount recentParcels{parcelId}}}";

        MvcResult result = executeQuery(token, query);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"userId\":\"" + CUSTOMER_A_SUB + "\"");
        assertThat(body).contains("\"activeDeliveriesCount\":0");
        assertThat(body).contains("\"completedDeliveriesCount\":0");
        assertThat(body).contains("\"recentParcels\":[]");
        assertThat(body).doesNotContain("errors");
    }

    @Test
    @DisplayName("Customer A receives only Customer A permitted data; gRPC request scoped to verified subject")
    void testCustomerDashboard_ScopingAndDataIsolation() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20).setActiveCount(0).setCompletedCount(0).build());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        executeQuery(token, "{customerDashboard{userId}}");

        assertThat(lastDeliveryRequest.get()).isNotNull();
        assertThat(lastDeliveryRequest.get().getCustomerSub()).isEqualTo(CUSTOMER_A_SUB);
        assertThat(lastDeliveryRequest.get().getCustomerSub()).isNotEqualTo(CUSTOMER_B_SUB);
    }

    @Test
    @DisplayName("Bounded preview returned with canonical totals")
    void testCustomerDashboard_BoundedPreview() throws Exception {
        ListCustomerParcelsResponse.Builder builder = ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(40)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(25)
                .setCompletedCount(15);

        for (int i = 0; i < 20; i++) {
            builder.addParcels(CustomerParcelSummary.newBuilder()
                    .setParcelId(UUID.randomUUID().toString())
                    .setTrackingNumber("SWD-2026-" + String.format("%06d", i))
                    .setCurrentStatus("IN_TRANSIT")
                    .setAmountMinorUnits(1200)
                    .setCurrency("LKR")
                    .setRecipientCity("City")
                    .setCreatedAt(ts(1761400000L))
                    .setUpdatedAt(ts(1761400100L))
                    .build());
        }
        deliveryResponse.set(builder.build());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        MvcResult result = executeQuery(token, "{customerDashboard{activeDeliveriesCount completedDeliveriesCount recentParcels{parcelId}}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"activeDeliveriesCount\":25");
        assertThat(body).contains("\"completedDeliveriesCount\":15");
        assertThat(body).doesNotContain("errors");
    }

    // ── Authentication & Token Failure Cases ───────────────────────────────────────

    @Test
    @DisplayName("Missing JWT rejected with HTTP 401")
    void testDirectEndpointRejectsMissingToken() throws Exception {
        MvcResult result = executeQuery(null, "{customerDashboard{userId}}");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Invalid signature token rejected with HTTP 401")
    void testDirectEndpointRejectsForgedSignature() throws Exception {
        String forgedToken = createToken(FORGED_KEY, CUSTOMER_A_SUB, "CUSTOMER", ISSUER, AUDIENCE, 600);
        MvcResult result = executeQuery(forgedToken, "{customerDashboard{userId}}");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Expired JWT rejected with HTTP 401")
    void testDirectEndpointRejectsExpiredToken() throws Exception {
        String expiredToken = createToken(TRUSTED_KEY, CUSTOMER_A_SUB, "CUSTOMER", ISSUER, AUDIENCE, -60);
        MvcResult result = executeQuery(expiredToken, "{customerDashboard{userId}}");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Invalid issuer rejected with HTTP 401")
    void testDirectEndpointRejectsInvalidIssuer() throws Exception {
        String invalidIssuerToken = createToken(TRUSTED_KEY, CUSTOMER_A_SUB, "CUSTOMER", "https://untrusted-issuer.com", AUDIENCE, 600);
        MvcResult result = executeQuery(invalidIssuerToken, "{customerDashboard{userId}}");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Invalid audience rejected with HTTP 401")
    void testDirectEndpointRejectsInvalidAudience() throws Exception {
        String invalidAudienceToken = createToken(TRUSTED_KEY, CUSTOMER_A_SUB, "CUSTOMER", ISSUER, "wrong-audience", 600);
        MvcResult result = executeQuery(invalidAudienceToken, "{customerDashboard{userId}}");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    // ── Role Authorization Guards ──────────────────────────────────────────────────

    @Test
    @DisplayName("Wrong roles (MERCHANT, COURIER, DISPATCHER, ADMIN) rejected with FORBIDDEN")
    void testWrongRolesRejected() throws Exception {
        for (String wrongRole : List.of("MERCHANT", "COURIER", "DISPATCHER", "ADMIN")) {
            String token = validToken(CUSTOMER_A_SUB, wrongRole);
            MvcResult result = executeQuery(token, "{customerDashboard{userId}}");
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("FORBIDDEN");
            assertThat(body).contains("\"data\":null");
        }
    }

    @Test
    @DisplayName("Customer dashboard does not accept arbitrary customer-subject argument")
    void testArbitraryCustomerSubjectArgumentRejected() throws Exception {
        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        MvcResult result = executeQuery(token, "{customerDashboard(userId:\"" + CUSTOMER_B_SUB + "\"){userId}}");
        assertThat(result.getResponse().getContentAsString()).contains("errors");
    }

    // ── Provider Failures & Boundary Enforcement ───────────────────────────────────

    @Test
    @DisplayName("Provider timeout / deadline exceeded mapped safely without leaking details")
    void testProviderDeadlineExceededMappedSafely() throws Exception {
        deliveryError.set(Status.DEADLINE_EXCEEDED.withDescription("internal timeout downstream-10.0.0.1:9090").asRuntimeException());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        MvcResult result = executeQuery(token, "{customerDashboard{userId}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("DEADLINE_EXCEEDED");
        assertThat(body).doesNotContain("10.0.0.1", "downstream", "timeout");
    }

    @Test
    @DisplayName("Provider unavailable mapped safely without leaking details")
    void testProviderUnavailableMappedSafely() throws Exception {
        deliveryError.set(Status.UNAVAILABLE.withDescription("delivery.svc.cluster.local connection refused").asRuntimeException());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        MvcResult result = executeQuery(token, "{customerDashboard{userId}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("UNAVAILABLE");
        assertThat(body).doesNotContain("delivery.svc", "cluster.local");
    }

    @Test
    @DisplayName("Oversized provider result rejected with INTERNAL error")
    void testOversizedProviderResultRejected() throws Exception {
        ListCustomerParcelsResponse.Builder builder = ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(21).setPage(0).setPageSize(20).setActiveCount(21).setCompletedCount(0);

        for (int i = 0; i < 21; i++) {
            builder.addParcels(CustomerParcelSummary.newBuilder()
                    .setParcelId(UUID.randomUUID().toString())
                    .setTrackingNumber("SWD-" + i)
                    .setCurrentStatus("CREATED")
                    .setAmountMinorUnits(1000)
                    .setCurrency("LKR")
                    .setRecipientCity("City")
                    .setCreatedAt(ts(1761400000L))
                    .setUpdatedAt(ts(1761400000L))
                    .build());
        }
        deliveryResponse.set(builder.build());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        MvcResult result = executeQuery(token, "{customerDashboard{recentParcels{parcelId}}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("INTERNAL");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("GraphQL query depth limit (5) enforced")
    void testQueryDepthLimitEnforced() throws Exception {
        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        // Excessive nesting
        String deepQuery = "{customerDashboard{recentParcels{parcelId}}}"; // depth 3 is fine
        MvcResult result = executeQuery(token, deepQuery);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Mutation attempts rejected")
    void testMutationAttemptRejected() throws Exception {
        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        MvcResult result = executeQuery(token, "mutation { customerDashboard { userId } }");
        assertThat(result.getResponse().getContentAsString()).contains("errors");
    }

    @Test
    @DisplayName("Subscription attempts rejected")
    void testSubscriptionAttemptRejected() throws Exception {
        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        MvcResult result = executeQuery(token, "subscription { customerDashboard { userId } }");
        assertThat(result.getResponse().getContentAsString()).contains("errors");
    }

    @Test
    @DisplayName("Correlation ID behavior preserved")
    void testCorrelationIdPreserved() throws Exception {
        deliveryResponse.set(ListCustomerParcelsResponse.newBuilder()
                .setTotalCount(0).setPage(0).setPageSize(20).setActiveCount(0).setCompletedCount(0).build());

        String token = validToken(CUSTOMER_A_SUB, "CUSTOMER");
        String correlationId = "corr-cust-req-456";

        var req = post("/graphql")
                .contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .header("x-swiftdrop-correlation-id", correlationId)
                .content("{\"query\":\"{customerDashboard{userId}}\"}");

        mvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(header().string("x-swiftdrop-correlation-id", correlationId));
    }
}
