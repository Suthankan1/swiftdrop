package com.swiftdrop.communicationsservice.webhook.api;

import com.swiftdrop.communicationsservice.webhook.dto.CreateWebhookSubscriptionRequest;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionCreatedResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionResponse;
import com.swiftdrop.communicationsservice.webhook.security.CommunicationsSecurityConfig;
import com.swiftdrop.communicationsservice.webhook.service.WebhookSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {WebhookSubscriptionController.class},
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import({CommunicationsSecurityConfig.class, WebhookProblemHandler.class})
class WebhookSubscriptionHttpSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookSubscriptionService subscriptionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String MERCHANT_SUB = "merchant-subject-uuid-111";

    @Test
    @DisplayName("Unauthenticated request to webhook routes returns 401 Unauthorized")
    void unauthenticatedRequest_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/webhooks"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/merchants/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"httpsUrl\":\"https://api.com\",\"allowedEventTypes\":[\"parcel.status.changed.v1\"]}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/merchants/webhooks/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Caller with CUSTOMER role (not MERCHANT) is denied with 403 Forbidden")
    void callerWithCustomerRole_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/webhooks")
                        .with(jwt().jwt(j -> j.subject("customer-sub")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Caller with COURIER role is denied with 403 Forbidden")
    void callerWithCourierRole_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/webhooks")
                        .with(jwt().jwt(j -> j.subject("courier-sub")).authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Authenticated MERCHANT can access GET /api/v1/merchants/webhooks")
    void authenticatedMerchant_CanListSubscriptions() throws Exception {
        when(subscriptionService.listSubscriptions(MERCHANT_SUB))
                .thenReturn(new WebhookSubscriptionListResponse(List.of()));

        mockMvc.perform(get("/api/v1/merchants/webhooks")
                        .with(jwt().jwt(j -> j.subject(MERCHANT_SUB)).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").isArray());
    }

    @Test
    @DisplayName("Authenticated MERCHANT can access POST /api/v1/merchants/webhooks")
    void authenticatedMerchant_CanCreateSubscription() throws Exception {
        UUID subId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        WebhookSubscriptionCreatedResponse createdResponse = new WebhookSubscriptionCreatedResponse(
                subId, merchantId, "https://api.merchant.com/callbacks",
                List.of("parcel.status.changed.v1"), "swd_whsec_secret", true, Instant.now()
        );

        when(subscriptionService.createSubscription(eq(MERCHANT_SUB), any()))
                .thenReturn(createdResponse);

        mockMvc.perform(post("/api/v1/merchants/webhooks")
                        .with(jwt().jwt(j -> j.subject(MERCHANT_SUB)).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"httpsUrl\":\"https://api.merchant.com/callbacks\",\"allowedEventTypes\":[\"parcel.status.changed.v1\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscriptionId").value(subId.toString()));
    }

    @Test
    @DisplayName("Authenticated MERCHANT can access DELETE /api/v1/merchants/webhooks/{id}")
    void authenticatedMerchant_CanDeleteSubscription() throws Exception {
        UUID subId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/merchants/webhooks/" + subId)
                        .with(jwt().jwt(j -> j.subject(MERCHANT_SUB)).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
                .andExpect(status().isNoContent());
    }
}
