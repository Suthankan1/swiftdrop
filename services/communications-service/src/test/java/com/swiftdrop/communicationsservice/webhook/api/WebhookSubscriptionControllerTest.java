package com.swiftdrop.communicationsservice.webhook.api;

import com.swiftdrop.communicationsservice.client.AccountsServiceUnavailableException;
import com.swiftdrop.communicationsservice.webhook.dto.CreateWebhookSubscriptionRequest;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryItem;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryLogListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionCreatedResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionResponse;
import com.swiftdrop.communicationsservice.webhook.exception.CrossTenantAccessException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidEventFilterException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotApprovedException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookSubscriptionNotFoundException;
import com.swiftdrop.communicationsservice.webhook.service.WebhookSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookSubscriptionControllerTest {

    private MockMvc mockMvc;
    private WebhookSubscriptionService subscriptionService;

    private final String callerSub = "test-merchant-sub";
    private TestingAuthenticationToken merchantAuth;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(WebhookSubscriptionService.class);

        WebhookSubscriptionController controller = new WebhookSubscriptionController(subscriptionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebhookProblemHandler())
                .build();

        merchantAuth = new TestingAuthenticationToken(
                callerSub, "credentials", List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        merchantAuth.setAuthenticated(true);
    }

    @Test
    @DisplayName("POST /api/v1/merchants/webhooks returns 201 Created with secret and Cache-Control: no-store")
    void createSubscription_Success() throws Exception {
        UUID subId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        CreateWebhookSubscriptionRequest request = new CreateWebhookSubscriptionRequest(
                "https://api.example.com/webhooks",
                List.of("parcel.status.changed.v1")
        );

        WebhookSubscriptionCreatedResponse createdResponse = new WebhookSubscriptionCreatedResponse(
                subId,
                merchantId,
                "https://api.example.com/webhooks",
                List.of("parcel.status.changed.v1"),
                "swd_whsec_mock_signing_secret_for_controller_testing_only",
                true,
                Instant.now()
        );

        when(subscriptionService.createSubscription(eq(callerSub), any(CreateWebhookSubscriptionRequest.class)))
                .thenReturn(createdResponse);

        mockMvc.perform(post("/api/v1/merchants/webhooks")
                        .principal(merchantAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"httpsUrl\":\"https://api.example.com/webhooks\",\"allowedEventTypes\":[\"parcel.status.changed.v1\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.subscriptionId").value(subId.toString()))
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.httpsUrl").value("https://api.example.com/webhooks"))
                .andExpect(jsonPath("$.allowedEventTypes[0]").value("parcel.status.changed.v1"))
                .andExpect(jsonPath("$.hmacSigningSecret").value("swd_whsec_mock_signing_secret_for_controller_testing_only"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/merchants/webhooks returns 200 OK and omits signing secrets")
    void listSubscriptions_Success_OmitsSecrets() throws Exception {
        UUID subId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        WebhookSubscriptionResponse responseItem = new WebhookSubscriptionResponse(
                subId, merchantId, "https://api.example.com/wh", List.of("parcel.status.changed.v1"), true, Instant.now());
        WebhookSubscriptionListResponse listResponse = new WebhookSubscriptionListResponse(List.of(responseItem));

        when(subscriptionService.listSubscriptions(callerSub)).thenReturn(listResponse);

        mockMvc.perform(get("/api/v1/merchants/webhooks")
                        .principal(merchantAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions", hasSize(1)))
                .andExpect(jsonPath("$.subscriptions[0].subscriptionId").value(subId.toString()))
                .andExpect(jsonPath("$.subscriptions[0].hmacSigningSecret").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/merchants/webhooks/{id} returns 200 OK and omits signing secret")
    void getSubscriptionById_Success() throws Exception {
        UUID subId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        WebhookSubscriptionResponse response = new WebhookSubscriptionResponse(
                subId, merchantId, "https://api.example.com/wh", List.of("parcel.status.changed.v1"), true, Instant.now());

        when(subscriptionService.getSubscription(callerSub, subId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/merchants/webhooks/{subscriptionId}", subId)
                        .principal(merchantAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(subId.toString()))
                .andExpect(jsonPath("$.hmacSigningSecret").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/v1/merchants/webhooks/{id} returns 204 No Content")
    void deleteSubscription_Success() throws Exception {
        UUID subId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/merchants/webhooks/{subscriptionId}", subId)
                        .principal(merchantAuth))
                .andExpect(status().isNoContent());

        verify(subscriptionService).deactivateSubscription(callerSub, subId);
    }

    @Test
    @DisplayName("GET /api/v1/merchants/webhooks/{id}/deliveries returns 200 OK with history")
    void listDeliveries_Success() throws Exception {
        UUID subId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        WebhookDeliveryItem item = new WebhookDeliveryItem(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), "SUCCESS", 1, Instant.now(), 200, 120, null);
        WebhookDeliveryLogListResponse response = new WebhookDeliveryLogListResponse(1, List.of(item));

        when(subscriptionService.listDeliveries(eq(callerSub), eq(subId), anyInt(), anyInt()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/merchants/webhooks/{subscriptionId}/deliveries", subId)
                        .principal(merchantAuth)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.deliveries", hasSize(1)))
                .andExpect(jsonPath("$.deliveries[0].deliveryId").value(deliveryId.toString()))
                .andExpect(jsonPath("$.deliveries[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.deliveries[0].lastHttpStatusCode").value(200));
    }

    @Test
    @DisplayName("Unapproved merchant returns 403 Forbidden ProblemDetail")
    void unapprovedMerchant_Returns403ProblemDetail() throws Exception {
        when(subscriptionService.listSubscriptions(callerSub))
                .thenThrow(new MerchantNotApprovedException("Merchant account is not authorized or approval is pending."));

        mockMvc.perform(get("/api/v1/merchants/webhooks")
                        .principal(merchantAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Merchant Not Approved"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(containsString("approval is pending")));
    }

    @Test
    @DisplayName("Cross-tenant access returns 403 Forbidden ProblemDetail")
    void crossTenantAccess_Returns403ProblemDetail() throws Exception {
        UUID subId = UUID.randomUUID();
        when(subscriptionService.getSubscription(callerSub, subId))
                .thenThrow(new CrossTenantAccessException("Caller does not own this subscription."));

        mockMvc.perform(get("/api/v1/merchants/webhooks/{id}", subId)
                        .principal(merchantAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Caller does not own this subscription."));
    }

    @Test
    @DisplayName("Non-existent subscription returns 404 Not Found ProblemDetail")
    void notFoundSubscription_Returns404ProblemDetail() throws Exception {
        UUID subId = UUID.randomUUID();
        when(subscriptionService.getSubscription(callerSub, subId))
                .thenThrow(new WebhookSubscriptionNotFoundException("Webhook subscription not found."));

        mockMvc.perform(get("/api/v1/merchants/webhooks/{id}", subId)
                        .principal(merchantAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Subscription Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("Invalid webhook destination returns 400 Bad Request ProblemDetail")
    void invalidDestination_Returns400ProblemDetail() throws Exception {
        CreateWebhookSubscriptionRequest request = new CreateWebhookSubscriptionRequest(
                "http://insecure.example.com",
                List.of("parcel.status.changed.v1")
        );

        when(subscriptionService.createSubscription(eq(callerSub), any()))
                .thenThrow(new InvalidWebhookDestinationException("Only secure HTTPS destinations are permitted"));

        mockMvc.perform(post("/api/v1/merchants/webhooks")
                        .principal(merchantAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"httpsUrl\":\"http://insecure.example.com\",\"allowedEventTypes\":[\"parcel.status.changed.v1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Webhook Destination"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(containsString("Only secure HTTPS destinations are permitted")));
    }

    @Test
    @DisplayName("Accounts service outage returns 503 Service Unavailable ProblemDetail")
    void accountsUnavailable_Returns503ProblemDetail() throws Exception {
        when(subscriptionService.listSubscriptions(callerSub))
                .thenThrow(new AccountsServiceUnavailableException("Accounts service deadline exceeded"));

        mockMvc.perform(get("/api/v1/merchants/webhooks")
                        .principal(merchantAuth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503));
    }
}
