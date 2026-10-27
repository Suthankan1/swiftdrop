package com.swiftdrop.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates real routing of approved SwiftDrop business routes to downstream microservices:
 *   - Accounts & Partners Service (/api/v1/users/me, /api/v1/merchants/**, /api/v1/admin/**)
 *   - Pricing & Zones Service (/api/v1/quotes/**, /api/v1/zones/**)
 *   - Delivery Operations Service (/api/v1/tracking/**, /api/v1/merchant/parcels, /api/v1/assignments/**, /api/v1/parcels/**)
 *   - Communications Service (/api/v1/merchants/webhooks/**)
 *   - GraphQL Query Service (/graphql)
 * Also verifies header sanitization regression, unbuffered SSE forwarding, and role gating.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class GatewayBusinessRoutesIntegrationTest {

    private static MockWebServer authServer;
    private static MockWebServer accountsServer;
    private static MockWebServer pricingServer;
    private static MockWebServer deliveryServer;
    private static MockWebServer communicationsServer;
    private static MockWebServer graphqlServer;

    private static RSAKey trustedRsaKey;
    private static String issuerUrl;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUpClient() throws Exception {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        drainMockServer(accountsServer);
        drainMockServer(pricingServer);
        drainMockServer(deliveryServer);
        drainMockServer(communicationsServer);
        drainMockServer(graphqlServer);
    }

    private void drainMockServer(MockWebServer server) throws Exception {
        while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // drain leftover requests from previous tests
        }
    }

    @BeforeAll
    static void setUpServers() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("swiftdrop-routes-key")
                .generate();

        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/realms/swiftdrop/protocol/openid-connect/certs".equals(request.getPath())) {
                    JWKSet jwkSet = new JWKSet(trustedRsaKey.toPublicJWK());
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(jwkSet.toString());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        authServer.start();
        issuerUrl = authServer.url("/realms/swiftdrop").toString().replaceAll("/$", "");

        accountsServer = new MockWebServer();
        accountsServer.start();

        pricingServer = new MockWebServer();
        pricingServer.start();

        deliveryServer = new MockWebServer();
        deliveryServer.start();

        communicationsServer = new MockWebServer();
        communicationsServer.start();

        graphqlServer = new MockWebServer();
        graphqlServer.start();
    }

    @AfterAll
    static void tearDownServers() throws IOException {
        if (authServer != null) authServer.shutdown();
        if (accountsServer != null) accountsServer.shutdown();
        if (pricingServer != null) pricingServer.shutdown();
        if (deliveryServer != null) deliveryServer.shutdown();
        if (communicationsServer != null) communicationsServer.shutdown();
        if (graphqlServer != null) graphqlServer.shutdown();
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("swiftdrop.jwt.jwk-set-uri", () -> authServer.url("/realms/swiftdrop/protocol/openid-connect/certs").toString());
        registry.add("swiftdrop.jwt.issuer-uri", () -> issuerUrl);
        registry.add("swiftdrop.jwt.audience", () -> "swiftdrop-api");
        registry.add("ACCOUNTS_SERVICE_URL", () -> accountsServer.url("").toString().replaceAll("/$", ""));
        registry.add("PRICING_SERVICE_URL", () -> pricingServer.url("").toString().replaceAll("/$", ""));
        registry.add("DELIVERY_SERVICE_URL", () -> deliveryServer.url("").toString().replaceAll("/$", ""));
        registry.add("COMMUNICATIONS_SERVICE_URL", () -> communicationsServer.url("").toString().replaceAll("/$", ""));
        registry.add("GRAPHQL_SERVICE_URL", () -> graphqlServer.url("").toString().replaceAll("/$", ""));
    }

    private String createToken(String subject, List<String> roles) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(issuerUrl)
                .subject(subject)
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(now.plus(10, ChronoUnit.MINUTES)))
                .audience(List.of("swiftdrop-api"))
                .claim("roles", roles)
                .claim("preferred_username", subject)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedRsaKey.getKeyID()).build(),
                claimsSet
        );
        signedJWT.sign(new RSASSASigner(trustedRsaKey.toRSAPrivateKey()));
        return signedJWT.serialize();
    }

    // =========================================================================
    // 1. ACCOUNTS & PARTNERS ROUTES
    // =========================================================================

    @Test
    @DisplayName("Routes GET /api/v1/users/me to Accounts & Partners Service with Bearer token")
    void routesUsersMeToAccountsService() throws Exception {
        String token = createToken("user-123", List.of("CUSTOMER"));
        accountsServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"user-123\",\"email\":\"user@example.com\"}"));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo("user-123");

        RecordedRequest req = accountsServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/v1/users/me");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    @DisplayName("Routes POST /api/v1/merchants to Accounts & Partners Service for enrollment")
    void routesMerchantEnrollmentToAccountsService() throws Exception {
        String token = createToken("merchant-user", List.of("CUSTOMER"));
        accountsServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"merchantId\":\"m-100\",\"status\":\"PENDING\"}"));

        webTestClient.post()
                .uri("/api/v1/merchants")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"businessName\":\"Acme Corp\",\"taxId\":\"TAX-123\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.merchantId").isEqualTo("m-100");

        RecordedRequest req = accountsServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/merchants");
    }

    @Test
    @DisplayName("Routes GET /api/v1/merchants/me with CUSTOMER role to Accounts & Partners")
    void routesGetCurrentMerchantToAccountsService() throws Exception {
        String token = createToken("merchant-user", List.of("CUSTOMER"));
        accountsServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"merchantId\":\"m-100\",\"status\":\"PENDING\"}"));

        webTestClient.get()
                .uri("/api/v1/merchants/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.merchantId").isEqualTo("m-100");

        RecordedRequest req = accountsServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/v1/merchants/me");
    }

    @Test
    @DisplayName("Routes POST /api/v1/merchants/{id}/approve to Accounts & Partners for admin approval")
    void routesMerchantApprovalToAccountsService() throws Exception {
        String token = createToken("admin-user", List.of("ADMIN"));
        accountsServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"merchantId\":\"m-100\",\"status\":\"APPROVED\"}"));

        webTestClient.post()
                .uri("/api/v1/merchants/m-100/approve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");

        RecordedRequest req = accountsServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/merchants/m-100/approve");
    }

    @Test
    @DisplayName("Routes POST /api/v1/admin/reconciliation/staff/provision to Accounts & Partners")
    void routesAdminReconciliationToAccountsService() throws Exception {
        String token = createToken("admin-user", List.of("ADMIN"));
        accountsServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"staffId\":\"s-200\",\"role\":\"DISPATCHER\"}"));

        webTestClient.post()
                .uri("/api/v1/admin/reconciliation/staff/provision")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"dispatcher@swiftdrop.com\",\"role\":\"DISPATCHER\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.staffId").isEqualTo("s-200");

        RecordedRequest req = accountsServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/admin/reconciliation/staff/provision");
    }

    // =========================================================================
    // 2. PRICING & ZONES ROUTES
    // =========================================================================

    @Test
    @DisplayName("Routes POST /api/v1/quotes publicly to Pricing & Zones Service")
    void routesPublicQuoteCalculationToPricingService() throws Exception {
        pricingServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"quoteId\":\"q-300\",\"estimatedAmount\":1500}"));

        webTestClient.post()
                .uri("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"weightKg\":2.5,\"pickupZone\":\"ZONE_COLOMBO\",\"dropoffZone\":\"ZONE_GAMPAHA\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.quoteId").isEqualTo("q-300");

        RecordedRequest req = pricingServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/quotes");
    }

    @Test
    @DisplayName("Routes GET /api/v1/quotes/{id} publicly to Pricing & Zones Service")
    void routesPublicQuoteRetrievalToPricingService() throws Exception {
        pricingServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"quoteId\":\"q-300\",\"estimatedAmount\":1500}"));

        webTestClient.get()
                .uri("/api/v1/quotes/q-300")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.quoteId").isEqualTo("q-300");

        RecordedRequest req = pricingServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/v1/quotes/q-300");
    }

    @Test
    @DisplayName("Routes GET /api/v1/zones to Pricing & Zones Service for authenticated callers")
    void routesZonesListToPricingService() throws Exception {
        String token = createToken("customer-user", List.of("CUSTOMER"));
        pricingServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rateVersion\":\"v1.0\",\"zones\":[{\"zoneCode\":\"ZONE_COLOMBO\"}]}"));

        webTestClient.get()
                .uri("/api/v1/zones")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.zones[0].zoneCode").isEqualTo("ZONE_COLOMBO");

        RecordedRequest req = pricingServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/v1/zones");
    }

    // =========================================================================
    // 3. DELIVERY OPERATIONS ROUTES
    // =========================================================================

    @Test
    @DisplayName("Routes GET /api/v1/tracking/{trackingNumber} publicly to Delivery Service")
    void routesPublicTrackingToDeliveryService() throws Exception {
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"trackingNumber\":\"SWD-20261023-A1B2C3\",\"status\":\"IN_TRANSIT\"}"));

        webTestClient.get()
                .uri("/api/v1/tracking/SWD-20261023-A1B2C3")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.trackingNumber").isEqualTo("SWD-20261023-A1B2C3");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/v1/tracking/SWD-20261023-A1B2C3");
    }

    @Test
    @DisplayName("Routes POST /api/v1/merchant/parcels to Delivery Service with merchant role")
    void routesMerchantParcelBookingToDeliveryService() throws Exception {
        String token = createToken("merchant-sub", List.of("MERCHANT"));
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"parcelId\":\"p-400\",\"trackingNumber\":\"SWD-20261023-M001\"}"));

        webTestClient.post()
                .uri("/api/v1/merchant/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", "77777777-7777-7777-7777-777777777777")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"pickupAddress\":\"Warehouse A\",\"dropoffAddress\":\"Customer B\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.parcelId").isEqualTo("p-400");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/merchant/parcels");
        assertThat(req.getHeader("Idempotency-Key")).isEqualTo("77777777-7777-7777-7777-777777777777");
    }

    @Test
    @DisplayName("Routes POST /api/v1/assignments to Delivery Service with DISPATCHER role")
    void routesDispatcherAssignmentToDeliveryService() throws Exception {
        String token = createToken("dispatcher-user", List.of("DISPATCHER"));
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"assignmentId\":\"as-500\",\"status\":\"ASSIGNED\"}"));

        webTestClient.post()
                .uri("/api/v1/assignments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parcelId\":\"p-400\",\"courierSub\":\"courier-1\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ASSIGNED");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/assignments");
    }

    @Test
    @DisplayName("Routes POST /api/v1/parcels to Delivery Service with CUSTOMER role")
    void routesCustomerParcelBookingToDeliveryService() throws Exception {
        String token = createToken("customer-user", List.of("CUSTOMER"));
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"parcelId\":\"p-600\",\"status\":\"CREATED\"}"));

        webTestClient.post()
                .uri("/api/v1/parcels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quoteId\":\"q-300\",\"recipientName\":\"Nimal\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.parcelId").isEqualTo("p-600");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/parcels");
    }

    @Test
    @DisplayName("Routes PATCH /api/v1/parcels/{id}/status to Delivery Service with COURIER role")
    void routesCourierStatusUpdateToDeliveryService() throws Exception {
        String token = createToken("courier-user", List.of("COURIER"));
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"parcelId\":\"p-600\",\"newStatus\":\"PICKED_UP\"}"));

        webTestClient.patch()
                .uri("/api/v1/parcels/p-600/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"PICKED_UP\",\"expectedVersion\":1}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.newStatus").isEqualTo("PICKED_UP");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("PATCH");
        assertThat(req.getPath()).isEqualTo("/api/v1/parcels/p-600/status");
    }

    @Test
    @DisplayName("Routes POST /api/v1/parcels/{id}/proof to Delivery Service with COURIER role")
    void routesCourierProofUploadToDeliveryService() throws Exception {
        String token = createToken("courier-user", List.of("COURIER"));
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"proofId\":\"pr-700\",\"parcelId\":\"p-600\"}"));

        webTestClient.post()
                .uri("/api/v1/parcels/p-600/proof")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"recipientName\":\"Sunil\",\"deliveryNote\":\"Left at door\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.proofId").isEqualTo("pr-700");

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/parcels/p-600/proof");
    }

    // =========================================================================
    // 4. COMMUNICATIONS SERVICE ROUTES (SPECIFIC ROUTE PRECEDENCE)
    // =========================================================================

    @Test
    @DisplayName("Routes POST /api/v1/merchants/webhooks to Communications Service over generic merchants route")
    void routesWebhooksToCommunicationsService() throws Exception {
        String token = createToken("merchant-user", List.of("MERCHANT"));
        communicationsServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"subscriptionId\":\"wh-800\",\"active\":true}"));

        webTestClient.post()
                .uri("/api/v1/merchants/webhooks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"httpsUrl\":\"https://merchant.example.com/callback\",\"allowedEventTypes\":[\"parcel.status.changed.v1\"]}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.subscriptionId").isEqualTo("wh-800");

        RecordedRequest req = communicationsServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/v1/merchants/webhooks");

        // Verify Accounts server did NOT receive this webhook call
        assertThat(accountsServer.getRequestCount()).isZero();
    }

    // =========================================================================
    // 5. GRAPHQL QUERY SERVICE
    // =========================================================================

    @Test
    @DisplayName("Routes POST /graphql to GraphQL Query Service with Bearer token")
    void routesGraphqlQueryToGraphqlService() throws Exception {
        String token = createToken("user-123", List.of("CUSTOMER"));
        graphqlServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"myParcels\":[]}}"));

        webTestClient.post()
                .uri("/graphql")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"query { myParcels { id } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.myParcels").isArray();

        RecordedRequest req = graphqlServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/graphql");
    }

    // =========================================================================
    // 6. SSE STREAMING (UNBUFFERED FORWARDING)
    // =========================================================================

    @Test
    @DisplayName("Forwards SSE tracking event stream from Delivery Service without response buffering")
    void forwardsSseStreamUnbuffered() throws Exception {
        deliveryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("Cache-Control", "no-cache")
                .setHeader("Connection", "keep-alive")
                .setBody("data: {\"status\":\"IN_TRANSIT\"}\n\n"));

        FluxExchangeResult<String> result = webTestClient.get()
                .uri("/api/v1/tracking/SWD-20261023-A1B2C3/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class);

        StepVerifier.create(result.getResponseBody())
                .expectNextMatches(chunk -> chunk.contains("IN_TRANSIT"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        RecordedRequest req = deliveryServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/v1/tracking/SWD-20261023-A1B2C3/events");
    }

    // =========================================================================
    // 7. HEADER SANITIZATION & REGRESSION
    // =========================================================================

    @Test
    @DisplayName("Strips client-supplied spoofed identity and gRPC internal headers before forwarding")
    void stripsClientSpoofedHeadersOnRoutedRequest() throws Exception {
        String token = createToken("customer-1", List.of("CUSTOMER"));
        accountsServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"customer-1\"}"));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "spoofed-user")
                .header("X-Roles", "ADMIN")
                .header("X-Tenant-Id", "spoofed-tenant")
                .header("X-Keycloak-Sub", "spoofed-sub")
                .header("x-swiftdrop-service-token", "forged-token")
                .header("Idempotency-Key", "abc-123")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = accountsServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("X-User-Id")).isNull();
        assertThat(req.getHeader("X-Roles")).isNull();
        assertThat(req.getHeader("X-Tenant-Id")).isNull();
        assertThat(req.getHeader("X-Keycloak-Sub")).isNull();
        assertThat(req.getHeader("x-swiftdrop-service-token")).isNull();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer " + token);
        assertThat(req.getHeader("Idempotency-Key")).isEqualTo("abc-123");
    }

    // =========================================================================
    // 8. NEGATIVE AUTH & ROLE BOUNDARY CHECKS
    // =========================================================================

    @Test
    @DisplayName("Unauthenticated request to protected route returns 401 Problem Detail without reaching upstream")
    void unauthenticatedRequest_Returns401_DoesNotReachUpstream() throws Exception {
        webTestClient.post()
                .uri("/api/v1/parcels")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quoteId\":\"q-1\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized");

        RecordedRequest req = deliveryServer.takeRequest(300, TimeUnit.MILLISECONDS);
        assertThat(req).isNull();
    }

    @Test
    @DisplayName("Unauthorized role calling dispatcher assignments returns 403 Problem Detail without reaching upstream")
    void unauthorizedRole_Returns403_DoesNotReachUpstream() throws Exception {
        String customerToken = createToken("customer-1", List.of("CUSTOMER"));

        webTestClient.post()
                .uri("/api/v1/assignments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parcelId\":\"p-1\",\"courierSub\":\"c-1\"}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.valueOf("application/problem+json"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Forbidden");

        RecordedRequest req = deliveryServer.takeRequest(300, TimeUnit.MILLISECONDS);
        assertThat(req).isNull();
    }
}
