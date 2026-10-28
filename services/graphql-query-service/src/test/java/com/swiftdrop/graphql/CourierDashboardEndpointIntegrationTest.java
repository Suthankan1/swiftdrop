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
import com.swiftdrop.delivery.v1.CourierAssignmentSummary;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
import com.swiftdrop.delivery.v1.ListCourierAssignmentsRequest;
import com.swiftdrop.delivery.v1.ListCourierAssignmentsResponse;
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

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "debug=false",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@Import(CourierDashboardEndpointIntegrationTest.IntegrationFixture.class)
class CourierDashboardEndpointIntegrationTest {

    static final RSAKey TRUSTED_KEY = generateKey();
    static final RSAKey FORGED_KEY = generateKey();
    static final String ISSUER = "https://issuer.test";
    static final String AUDIENCE = "swiftdrop-api";
    static final String COURIER_SUB = "courier-sub-777";

    static final AtomicReference<AccountEligibilityResponse> accountsResponse = new AtomicReference<>();
    static final AtomicReference<StatusRuntimeException> accountsError = new AtomicReference<>();
    static final AtomicReference<ListCourierAssignmentsResponse> deliveryResponse = new AtomicReference<>();
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
                        public void listCourierAssignments(ListCourierAssignmentsRequest req, StreamObserver<ListCourierAssignmentsResponse> out) {
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
    void courierDashboardWithValidTokenReturnsAuthoritativeData() throws Exception {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .setRejectionReason("ELIGIBLE")
                .build());

        deliveryResponse.set(ListCourierAssignmentsResponse.newBuilder()
                .setTotalCount(1)
                .setPage(0)
                .setPageSize(20)
                .setActiveCount(1)
                .setTodayDeliveredCount(4)
                .setTodayFailedCount(0)
                .addAssignments(CourierAssignmentSummary.newBuilder()
                        .setAssignmentId("asgn-101")
                        .setParcelId("parcel-555")
                        .setTrackingNumber("SWD-2026-999001")
                        .setCurrentStatus("IN_TRANSIT")
                        .setPickupAddress("Warehouse 1")
                        .setDropoffAddress("Customer House 5")
                        .setRecipientName("Kasun Silva")
                        .setRecipientPhone("+94770001122")
                        .setAssignedAt(Timestamp.newBuilder().setSeconds(1761400000L).build())
                        .setVersion(2L)
                        .setIsActive(true)
                        .build())
                .build());

        String query = "{ courierDashboard { courierSub activeAssignedCount todayDeliveredCount todayFailedCount assignedParcels { parcelId } } }";
        MvcResult res = executeQuery(validToken(COURIER_SUB, "COURIER"), query);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("\"courierSub\":\"" + COURIER_SUB + "\"");
        assertThat(body).contains("\"activeAssignedCount\":1");
        assertThat(body).contains("\"todayDeliveredCount\":4");
        assertThat(body).contains("\"todayFailedCount\":0");
        assertThat(body).contains("\"parcelId\":\"parcel-555\"");
        assertThat(body).doesNotContain("errors");

        assertThat(res.getResponse().getHeader("x-swiftdrop-correlation-id")).isEqualTo("corr-test-123");
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        String query = "{ courierDashboard { courierSub } }";
        MvcResult res = executeQuery(null, query);
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void forgedTokenReturns401() throws Exception {
        String forged = createToken(FORGED_KEY, COURIER_SUB, "COURIER", ISSUER, AUDIENCE, 600);
        String query = "{ courierDashboard { courierSub } }";
        MvcResult res = executeQuery(forged, query);
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void nonCourierRolesAreDeniedByFieldPolicy() throws Exception {
        String query = "{ courierDashboard { courierSub } }";
        for (String role : List.of("CUSTOMER", "MERCHANT", "DISPATCHER", "ADMIN")) {
            MvcResult res = executeQuery(validToken("user-" + role, role), query);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            String body = res.getResponse().getContentAsString();
            assertThat(body).contains("FORBIDDEN").contains("\"data\":null");
        }
    }

    @Test
    void suspendedCourierReceivesForbiddenError() throws Exception {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(false)
                .setIsActive(false)
                .setStatus("SUSPENDED")
                .setRejectionReason("COURIER_SUSPENDED")
                .build());

        String query = "{ courierDashboard { courierSub } }";
        MvcResult res = executeQuery(validToken(COURIER_SUB, "COURIER"), query);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("FORBIDDEN").contains("\"data\":null");
    }

    @Test
    void deliveryProviderErrorIsSanitizedWithoutLeakingHostOrTrace() throws Exception {
        accountsResponse.set(AccountEligibilityResponse.newBuilder()
                .setSubject(COURIER_SUB)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .build());

        deliveryError.set(Status.UNAVAILABLE.withDescription("psql: connect to server at 10.0.0.5 failed: private credential leaked").asRuntimeException());

        String query = "{ courierDashboard { courierSub } }";
        MvcResult res = executeQuery(validToken(COURIER_SUB, "COURIER"), query);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("UNAVAILABLE").contains("\"data\":null");
        assertThat(body).doesNotContain("10.0.0.5", "psql", "credential", "private");
    }

    @Test
    void forgedIdentityHeadersAreRejectedByWebLayer() throws Exception {
        mvc.perform(post("/graphql")
                        .servletPath("/graphql")
                        .header("Authorization", "Bearer " + validToken(COURIER_SUB, "COURIER"))
                        .header("x-swiftdrop-actor-sub", "imposter-subject")
                        .contentType("application/json")
                        .content("{\"query\":\"{ courierDashboard { courierSub } }\"}"))
                .andExpect(status().isBadRequest());
    }
}
