package com.swiftdrop.accountsservice.merchant.credential;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.merchant.MerchantProblemHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MerchantApiKeyControllerTest {

    private CurrentIdentityProvider identityProvider;
    private MerchantApiKeyService apiKeyService;
    private MockMvc mvc;

    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID keyId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final TrustedIdentity merchantIdentity = new TrustedIdentity(
            "keycloak-sub-merchant", "merchant@swiftdrop.test", "Merchant Owner", List.of("MERCHANT")
    );

    @BeforeEach
    void setUp() {
        identityProvider = mock(CurrentIdentityProvider.class);
        apiKeyService = mock(MerchantApiKeyService.class);

        when(identityProvider.currentIdentity()).thenReturn(merchantIdentity);

        var controller = new MerchantApiKeyController(identityProvider, apiKeyService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MerchantProblemHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/api-keys generates key and returns 201 Created with no-store")
    void generateApiKey_returnsCreated() throws Exception {
        Instant now = Instant.now();
        var response = new ApiKeyCreatedResponse(
                keyId, "Automation Key", "swd_live_7a8b", "swd_live_7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d",
                List.of("parcels:create"), now, now.plus(90, ChronoUnit.DAYS)
        );

        when(apiKeyService.generateApiKey(eq(merchantIdentity), eq(merchantId), any())).thenReturn(response);

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Automation Key\",\"scopes\":[\"parcels:create\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("keyId").value(keyId.toString()))
                .andExpect(jsonPath("name").value("Automation Key"))
                .andExpect(jsonPath("plaintextKey").value("swd_live_7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d"));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/{id}/api-keys lists keys and returns 200 OK with no-store")
    void listApiKeys_returnsOk() throws Exception {
        Instant now = Instant.now();
        var item = new ApiKeySummaryItem(
                keyId, "Automation Key", "swd_live_7a8b", List.of("parcels:create"),
                now.minus(1, ChronoUnit.DAYS), now.plus(89, ChronoUnit.DAYS), false
        );

        when(apiKeyService.listApiKeys(eq(merchantIdentity), eq(merchantId)))
                .thenReturn(new ApiKeyListResponse(List.of(item)));

        mvc.perform(get("/api/v1/merchants/{merchantId}/api-keys", merchantId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("keys[0].keyId").value(keyId.toString()))
                .andExpect(jsonPath("keys[0].name").value("Automation Key"))
                .andExpect(jsonPath("keys[0].plaintextKey").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/v1/merchants/{id}/api-keys/{keyId} revokes key and returns 204 No Content with no-store")
    void revokeApiKey_returnsNoContent() throws Exception {
        doNothing().when(apiKeyService).revokeApiKey(merchantIdentity, merchantId, keyId);

        mvc.perform(delete("/api/v1/merchants/{merchantId}/api-keys/{keyId}", merchantId, keyId))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(apiKeyService).revokeApiKey(merchantIdentity, merchantId, keyId);
    }

    @Test
    @DisplayName("POST /api/v1/merchants/{id}/api-keys/{keyId}/revoke alias returns 204 No Content")
    void revokeApiKeyPost_returnsNoContent() throws Exception {
        doNothing().when(apiKeyService).revokeApiKey(merchantIdentity, merchantId, keyId);

        mvc.perform(post("/api/v1/merchants/{merchantId}/api-keys/{keyId}/revoke", merchantId, keyId))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(apiKeyService).revokeApiKey(merchantIdentity, merchantId, keyId);
    }
}
