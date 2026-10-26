package com.swiftdrop.deliveryservice.proof.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.proof.ProofService;
import com.swiftdrop.deliveryservice.proof.dto.ProofDetailResponse;
import com.swiftdrop.deliveryservice.proof.dto.ProofResponse;
import com.swiftdrop.deliveryservice.proof.dto.UploadProofRequest;
import com.swiftdrop.deliveryservice.proof.exception.IllegalLifecycleStageException;
import com.swiftdrop.deliveryservice.proof.exception.ProofAccessDeniedException;
import com.swiftdrop.deliveryservice.proof.exception.ProofNotFoundException;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import com.swiftdrop.deliveryservice.security.ReceiptSecurityConfiguration;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProofController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({ReceiptSecurityConfiguration.class, ProofProblemHandler.class})
class ProofControllerSecurityTest {

    private static MockWebServer authServer;
    private static RSAKey trustedRsaKey;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProofService proofService;

    @BeforeAll
    static void startAuthServer() throws Exception {
        trustedRsaKey = new RSAKeyGenerator(2048)
                .keyID("trusted-key-pod")
                .generate();

        authServer = new MockWebServer();
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/protocol/openid-connect/certs".equals(request.getPath())) {
                    JWKSet jwkSet = new JWKSet(trustedRsaKey.toPublicJWK());
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(jwkSet.toString());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        authServer.start();
    }

    @AfterAll
    static void stopAuthServer() throws IOException {
        if (authServer != null) {
            authServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String rawUrl = authServer.url("").toString();
        final String baseUrl = rawUrl.endsWith("/") ? rawUrl.substring(0, rawUrl.length() - 1) : rawUrl;
        registry.add("KEYCLOAK_JWKS_URI", () -> baseUrl + "/protocol/openid-connect/certs");
        registry.add("KEYCLOAK_ISSUER_URI", () -> baseUrl);
        registry.add("JWT_AUDIENCE", () -> "swiftdrop-api");
    }

    private String createJwt(String subject, List<String> realmRoles, boolean expired) throws Exception {
        Instant now = Instant.now();
        Instant exp = expired ? now.minus(1, ChronoUnit.HOURS) : now.plus(1, ChronoUnit.HOURS);

        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(authServer.url("").toString().replaceAll("/$", ""))
                .subject(subject)
                .audience("swiftdrop-api")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("realm_access", Map.of("roles", realmRoles));

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedRsaKey.getKeyID()).build(),
                builder.build()
        );
        signedJwt.sign(new RSASSASigner(trustedRsaKey));
        return signedJwt.serialize();
    }

    @Test
    @DisplayName("Unauthenticated upload request returns 401 Unauthorized Problem Detail")
    void uploadProof_unauthenticated_returns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoObjectKey\":\"proofs/v1/12345678-1234-4234-8234-123456789012\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(proofService);
    }

    @Test
    @DisplayName("Expired JWT returns 401 Unauthorized Problem Detail")
    void uploadProof_expiredJwt_returns401() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String expiredToken = createJwt("courier-1", List.of("COURIER"), true);

        mockMvc.perform(post("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoObjectKey\":\"proofs/v1/12345678-1234-4234-8234-123456789012\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(proofService);
    }

    @Test
    @DisplayName("Authorized courier upload returns 201 Created with ProofResponse")
    void uploadProof_authorizedCourier_success201() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("courier-123", List.of("COURIER"), false);
        String ref = "proofs/v1/" + UUID.randomUUID();

        UUID proofId = UUID.randomUUID();
        when(proofService.recordProofMetadata(eq(parcelId), any(UploadProofRequest.class), eq("courier-123"), any()))
                .thenReturn(new ProofResponse(proofId, parcelId, "courier-123", Instant.now()));

        mockMvc.perform(post("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"K. Silva\",\"photoObjectKey\":\"" + ref + "\",\"deliveryNote\":\"Handed\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/parcels/" + parcelId + "/proof"))
                .andExpect(jsonPath("$.proofId").value(proofId.toString()))
                .andExpect(jsonPath("$.parcelId").value(parcelId.toString()))
                .andExpect(jsonPath("$.uploadedBySub").value("courier-123"));
    }

    @Test
    @DisplayName("Unassigned courier returns 403 Forbidden Problem Detail")
    void uploadProof_unassignedCourier_returns403() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("other-courier", List.of("COURIER"), false);
        String ref = "proofs/v1/" + UUID.randomUUID();

        when(proofService.recordProofMetadata(eq(parcelId), any(UploadProofRequest.class), eq("other-courier"), any()))
                .thenThrow(new ProofAccessDeniedException("Forbidden: Courier is not actively assigned"));

        mockMvc.perform(post("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoObjectKey\":\"" + ref + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Forbidden: Courier is not actively assigned"));
    }

    @Test
    @DisplayName("Illegal lifecycle stage returns 422 Unprocessable Entity Problem Detail")
    void uploadProof_illegalStage_returns422() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("courier-123", List.of("COURIER"), false);
        String ref = "proofs/v1/" + UUID.randomUUID();

        when(proofService.recordProofMetadata(eq(parcelId), any(UploadProofRequest.class), eq("courier-123"), any()))
                .thenThrow(new IllegalLifecycleStageException("Cannot upload proof for terminal parcel"));

        mockMvc.perform(post("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoObjectKey\":\"" + ref + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("Authorized metadata retrieval returns 200 OK")
    void getProofMetadata_authorized_returns200() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("customer-sender", List.of("CUSTOMER"), false);
        UUID proofId = UUID.randomUUID();
        String ref = "proofs/v1/" + UUID.randomUUID();

        when(proofService.getProofMetadata(eq(parcelId), eq("customer-sender"), any()))
                .thenReturn(new ProofDetailResponse(proofId, parcelId, ref, "PHOTO", "image/jpeg", 2048L, "Nimal", "Note", "courier-1", Instant.now()));

        mockMvc.perform(get("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proofId").value(proofId.toString()))
                .andExpect(jsonPath("$.objectReference").value(ref))
                .andExpect(jsonPath("$.recipientName").value("Nimal"));
    }

    @Test
    @DisplayName("Unauthorized proof retrieval returns 403 Forbidden")
    void getProofMetadata_unauthorized_returns403() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("unrelated-user", List.of("CUSTOMER"), false);

        when(proofService.getProofMetadata(eq(parcelId), eq("unrelated-user"), any()))
                .thenThrow(new ProofAccessDeniedException("Forbidden: Caller is not authorized"));

        mockMvc.perform(get("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Missing proof returns 404 Not Found")
    void getProofMetadata_notFound_returns404() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("customer-sender", List.of("CUSTOMER"), false);

        when(proofService.getProofMetadata(eq(parcelId), eq("customer-sender"), any()))
                .thenThrow(new ProofNotFoundException(parcelId));

        mockMvc.perform(get("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("Authorized binary streaming returns 200 OK with no public URL leak")
    void getProofContent_streaming_returns200() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("customer-sender", List.of("CUSTOMER"), false);

        byte[] imageBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        RetrievedProofObject retrieved = new RetrievedProofObject(new ByteArrayInputStream(imageBytes), 4L, "image/jpeg");
        when(proofService.getProofContent(eq(parcelId), eq("customer-sender"), any()))
                .thenReturn(retrieved);

        mockMvc.perform(get("/api/v1/parcels/{parcelId}/proof/content", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().bytes(imageBytes));
    }

    @Test
    @DisplayName("Purged proof metadata retrieval returns 404 Not Found without leaking storage key or recipient name")
    void getProofMetadata_purgedProof_returns404ZeroLeak() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("customer-sender", List.of("CUSTOMER"), false);

        when(proofService.getProofMetadata(eq(parcelId), eq("customer-sender"), any()))
                .thenThrow(new ProofNotFoundException(parcelId));

        mockMvc.perform(get("/api/v1/parcels/{parcelId}/proof", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Proof Not Found"))
                .andExpect(jsonPath("$.objectReference").doesNotExist())
                .andExpect(jsonPath("$.recipientName").doesNotExist());
    }

    @Test
    @DisplayName("Purged proof binary content retrieval returns 404 Not Found without leaking storage key")
    void getProofContent_purgedProof_returns404ZeroLeak() throws Exception {
        UUID parcelId = UUID.randomUUID();
        String token = createJwt("customer-sender", List.of("CUSTOMER"), false);

        when(proofService.getProofContent(eq(parcelId), eq("customer-sender"), any()))
                .thenThrow(new ProofNotFoundException(parcelId));

        mockMvc.perform(get("/api/v1/parcels/{parcelId}/proof/content", parcelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Proof Not Found"));
    }
}
