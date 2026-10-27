package com.swiftdrop.graphql;

import com.google.protobuf.Timestamp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.delivery.v1.ListMerchantOrdersRequest;
import com.swiftdrop.delivery.v1.ListMerchantOrdersResponse;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Integration tests for Query.merchantDashboard exercising the full Spring/GraphQL/Security stack.
 *
 * <p>Two merchant fixtures (A and B) are established so cross-tenant isolation can be verified:
 * a request authenticated as Merchant A must never return Merchant B's data.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "debug=false",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@Import(MerchantDashboardEndpointIntegrationTest.IntegrationFixture.class)
class MerchantDashboardEndpointIntegrationTest {

    static final RSAKey TRUSTED_KEY = generateKey();
    static final RSAKey FORGED_KEY = generateKey();
    static final String ISSUER = "https://issuer.test";
    static final String AUDIENCE = "swiftdrop-api";

    // Merchant A fixtures.
    static final String MERCHANT_A_SUB = "sub-int-merchant-aaa";
    static final String MERCHANT_A_ID = "int-merchant-id-aaa";
    static final String MERCHANT_A_BUSINESS = "Alpha Integrated Corp";

    // Merchant B fixtures.
    static final String MERCHANT_B_SUB = "sub-int-merchant-bbb";
    static final String MERCHANT_B_ID = "int-merchant-id-bbb";
    static final String MERCHANT_B_BUSINESS = "Beta Integrated Corp";

    static final AtomicReference<AccountEligibilityResponse> accountsResponse = new AtomicReference<>();
    static final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();
    static final AtomicReference<ListMerchantOrdersResponse> deliveryResponse = new AtomicReference<>();
    static final AtomicReference<StatusRuntimeException> deliveryError = new AtomicReference<>();
    static final AtomicReference<ListMerchantOrdersRequest> lastDeliveryRequest = new AtomicReference<>();

    private static Server accountsServer;
    private static Server deliveryServer;

    @Autowired
    private MockMvc mvc;

    private static RSAKey generateKey() {
        try { return new RSAKeyGenerator(2048).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationFixture {

        @Bean(destroyMethod = "close")
        AuthenticatedReadClients accountsReadClients() throws Exception {
            String serverName = InProcessServerBuilder.generateName();
            accountsServer = InProcessServerBuilder.forName(serverName).directExecutor()
                    .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                        @Override
                        public void checkAccountEligibility(AccountEligibilityRequest req,
                                StreamObserver<AccountEligibilityResponse> out) {
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
                        public void listMerchantOrders(ListMerchantOrdersRequest req,
                                StreamObserver<ListMerchantOrdersResponse> out) {
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
        if (accountsServer != null) accountsServer.shutdownNow();
        if (deliveryServer != null) deliveryServer.shutdownNow();
    }

    @BeforeEach
    void resetMocks() {
        accountsResponse.set(null);
        accountsError.set(null);
        deliveryResponse.set(null);
        deliveryError.set(null);
        lastDeliveryRequest.set(null);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private static String createToken(RSAKey key, String sub, String role, String issuer,
            String audience, long expirySeconds) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject(sub).issuer(issuer).audience(audience)
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
                .header("x-swiftdrop-correlation-id", "corr-merchant-int-test")
                .content("{\"query\":\"" + query + "\"}");
        if (token != null) req.header("Authorization", "Bearer " + token);
        var result = mvc.perform(req).andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }

    private Timestamp ts(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds).setNanos(0).build();
    }

    private AccountEligibilityResponse approvedResponse(String sub, String merchantId, String businessName) {
        return AccountEligibilityResponse.newBuilder()
                .setSubject(sub).setIsEligible(true).setIsActive(true).setStatus("APPROVED")
                .setMerchantId(merchantId).setBusinessName(businessName)
                .setRejectionReason("ELIGIBLE")
                .build();
    }

    // ── Happy path ─────────────────────────────────────────────────────────────────

    @Test
    void approvedMerchantReceivesAuthorizedDashboard() throws Exception {
        accountsResponse.set(approvedResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(7).setPage(0).setPageSize(20)
                .setActiveCount(3).setDeliveredCount(3).setFailedCount(1)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("parcel-int-001").setTrackingNumber("SWD-2026-INT001")
                        .setCurrentStatus("IN_TRANSIT").setWeightKg(1.5).setFeeMinorUnits(1200L)
                        .setCurrency("LKR").setCreatedAt(ts(1761400000L)).setLastStatusUpdate(ts(1761410000L))
                        .build())
                .build());

        String query = "{ merchantDashboard { merchantId businessName status "
                + "totalOrdersCount activeOrdersCount deliveredOrdersCount failedOrdersCount "
                + "recentOrders { parcelId trackingNumber currentStatus weightKg feeMinorUnits currency createdAt lastStatusUpdate } } }";
        MvcResult res = executeQuery(validToken(MERCHANT_A_SUB, "MERCHANT"), query);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();

        assertThat(body).contains("\"merchantId\":\"" + MERCHANT_A_ID + "\"");
        assertThat(body).contains("\"businessName\":\"" + MERCHANT_A_BUSINESS + "\"");
        assertThat(body).contains("\"status\":\"APPROVED\"");
        assertThat(body).contains("\"totalOrdersCount\":7");
        assertThat(body).contains("\"activeOrdersCount\":3");
        assertThat(body).contains("\"deliveredOrdersCount\":3");
        assertThat(body).contains("\"failedOrdersCount\":1");
        assertThat(body).contains("\"parcelId\":\"parcel-int-001\"");
        assertThat(body).contains("\"trackingNumber\":\"SWD-2026-INT001\"");
        assertThat(body).doesNotContain("errors");

        // Verify correlation-id echoed back.
        assertThat(res.getResponse().getHeader("x-swiftdrop-correlation-id")).isEqualTo("corr-merchant-int-test");

        // Verify Delivery was scoped to the correct merchant.
        assertThat(lastDeliveryRequest.get().getMerchantId()).isEqualTo(MERCHANT_A_ID);
    }

    // ── Authentication / authorization boundary tests ──────────────────────────────

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        String query = "{ merchantDashboard { merchantId } }";
        MvcResult res = executeQuery(null, query);
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void forgedTokenReturns401() throws Exception {
        String forged = createToken(FORGED_KEY, MERCHANT_A_SUB, "MERCHANT", ISSUER, AUDIENCE, 600);
        MvcResult res = executeQuery(forged, "{ merchantDashboard { merchantId } }");
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void nonMerchantRolesDeniedByFieldPolicy() throws Exception {
        String query = "{ merchantDashboard { merchantId } }";
        for (String role : List.of("CUSTOMER", "COURIER", "DISPATCHER", "ADMIN")) {
            MvcResult res = executeQuery(validToken("user-" + role, role), query);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            String body = res.getResponse().getContentAsString();
            assertThat(body).contains("FORBIDDEN").contains("\"data\":null");
        }
    }

    // ── Enrollment state tests ─────────────────────────────────────────────────────

    @Test
    void pendingMerchantReceivesForbiddenError() throws Exception {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(MERCHANT_A_SUB).setIsEligible(false).setIsActive(false)
                .setStatus("PENDING").setRejectionReason("MERCHANT_PENDING")
                .setMerchantId(MERCHANT_A_ID).setBusinessName(MERCHANT_A_BUSINESS)
                .build());

        MvcResult res = executeQuery(validToken(MERCHANT_A_SUB, "MERCHANT"), "{ merchantDashboard { merchantId } }");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("FORBIDDEN").contains("\"data\":null");
    }

    @Test
    void suspendedMerchantReceivesForbiddenError() throws Exception {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(MERCHANT_A_SUB).setIsEligible(false).setIsActive(false)
                .setStatus("SUSPENDED").setRejectionReason("MERCHANT_SUSPENDED")
                .setMerchantId(MERCHANT_A_ID).setBusinessName(MERCHANT_A_BUSINESS)
                .build());

        MvcResult res = executeQuery(validToken(MERCHANT_A_SUB, "MERCHANT"), "{ merchantDashboard { merchantId } }");
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("FORBIDDEN").contains("\"data\":null");
    }

    // ── Data isolation: Merchant B cannot access Merchant A's data ─────────────────

    @Test
    void merchantBSeesMerchantBDataNotMerchantAData() throws Exception {
        // Merchant B authenticates and is approved.
        accountsResponse.set(approvedResponse(MERCHANT_B_SUB, MERCHANT_B_ID, MERCHANT_B_BUSINESS));
        deliveryResponse.set(ListMerchantOrdersResponse.newBuilder()
                .setTotalCount(2).setPage(0).setPageSize(20)
                .setActiveCount(1).setDeliveredCount(1).setFailedCount(0)
                .addOrders(com.swiftdrop.delivery.v1.MerchantOrderSummary.newBuilder()
                        .setParcelId("parcel-bbb-int-001").setTrackingNumber("SWD-2026-B001")
                        .setCurrentStatus("DELIVERED").setWeightKg(3.0).setFeeMinorUnits(900L)
                        .setCurrency("LKR").setCreatedAt(ts(1761500000L))
                        .build())
                .build());

        MvcResult res = executeQuery(validToken(MERCHANT_B_SUB, "MERCHANT"),
                "{ merchantDashboard { merchantId businessName recentOrders { parcelId } } }");

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();

        assertThat(body).contains("\"merchantId\":\"" + MERCHANT_B_ID + "\"");
        assertThat(body).contains("\"businessName\":\"" + MERCHANT_B_BUSINESS + "\"");
        assertThat(body).contains("\"parcelId\":\"parcel-bbb-int-001\"");
        assertThat(body).doesNotContain(MERCHANT_A_ID, MERCHANT_A_BUSINESS, "parcel-int-001");
        assertThat(body).doesNotContain("errors");

        // Delivery was called with Merchant B's ID — never with Merchant A's ID.
        assertThat(lastDeliveryRequest.get().getMerchantId()).isEqualTo(MERCHANT_B_ID);
        assertThat(lastDeliveryRequest.get().getMerchantId()).isNotEqualTo(MERCHANT_A_ID);
    }

    // ── Provider error sanitization ────────────────────────────────────────────────

    @Test
    void deliveryProviderErrorSanitizedWithoutLeakingInternals() throws Exception {
        accountsResponse.set(approvedResponse(MERCHANT_A_SUB, MERCHANT_A_ID, MERCHANT_A_BUSINESS));
        deliveryError.set(Status.UNAVAILABLE.withDescription(
                "psql: connect to 10.0.0.9 failed: private delivery-host credential-leaked").asRuntimeException());

        MvcResult res = executeQuery(validToken(MERCHANT_A_SUB, "MERCHANT"), "{ merchantDashboard { merchantId } }");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("UNAVAILABLE").contains("\"data\":null");
        assertThat(body).doesNotContain("10.0.0.9", "psql", "credential", "private");
    }

    @Test
    void forgedActorHeadersAreRejectedByWebLayer() throws Exception {
        mvc.perform(post("/graphql")
                        .servletPath("/graphql")
                        .header("Authorization", "Bearer " + validToken(MERCHANT_A_SUB, "MERCHANT"))
                        .header("x-swiftdrop-actor-sub", "imposter-merchant")
                        .contentType("application/json")
                        .content("{\"query\":\"{ merchantDashboard { merchantId } }\"}"))
                .andExpect(status().isBadRequest());
    }
}
