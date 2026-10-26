package com.swiftdrop.communicationsservice.webhook.api;

import com.swiftdrop.communicationsservice.webhook.dto.CreateWebhookSubscriptionRequest;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookDeliveryLogListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionCreatedResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionListResponse;
import com.swiftdrop.communicationsservice.webhook.dto.WebhookSubscriptionResponse;
import com.swiftdrop.communicationsservice.webhook.service.WebhookSubscriptionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative REST controller implementing webhook subscription configuration for merchant tenants.
 * Aligns strictly with contracts/rest/openapi-communications.yaml (v1.0.0, IF-REST-11, IF-REST-12).
 */
@RestController
@RequestMapping("/api/v1/merchants/webhooks")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService subscriptionService;

    public WebhookSubscriptionController(WebhookSubscriptionService subscriptionService) {
        this.subscriptionService = Objects.requireNonNull(subscriptionService, "subscriptionService is required");
    }

    /**
     * POST /api/v1/merchants/webhooks
     * Registers a new HTTPS webhook destination and returns the HMAC signing secret once.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookSubscriptionCreatedResponse createSubscription(
            @Valid @RequestBody CreateWebhookSubscriptionRequest request,
            Authentication authentication,
            HttpServletResponse response) {
        String callerSub = extractSubject(authentication);
        response.setHeader("Cache-Control", "no-store");
        return subscriptionService.createSubscription(callerSub, request);
    }

    /**
     * GET /api/v1/merchants/webhooks
     * Lists registered webhook endpoints for the authenticated merchant tenant.
     */
    @GetMapping
    public WebhookSubscriptionListResponse listSubscriptions(Authentication authentication) {
        String callerSub = extractSubject(authentication);
        return subscriptionService.listSubscriptions(callerSub);
    }

    /**
     * GET /api/v1/merchants/webhooks/{subscriptionId}
     * Retrieves details and configuration of a specific webhook subscription.
     */
    @GetMapping("/{subscriptionId}")
    public WebhookSubscriptionResponse getSubscriptionById(
            @PathVariable UUID subscriptionId,
            Authentication authentication) {
        String callerSub = extractSubject(authentication);
        return subscriptionService.getSubscription(callerSub, subscriptionId);
    }

    /**
     * DELETE /api/v1/merchants/webhooks/{subscriptionId}
     * Soft-deactivates a webhook subscription.
     */
    @DeleteMapping("/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubscription(
            @PathVariable UUID subscriptionId,
            Authentication authentication) {
        String callerSub = extractSubject(authentication);
        subscriptionService.deactivateSubscription(callerSub, subscriptionId);
    }

    /**
     * GET /api/v1/merchants/webhooks/{subscriptionId}/deliveries
     * Inspects historical delivery execution logs and retry outcomes.
     */
    @GetMapping("/{subscriptionId}/deliveries")
    public WebhookDeliveryLogListResponse listDeliveries(
            @PathVariable UUID subscriptionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        String callerSub = extractSubject(authentication);
        return subscriptionService.listDeliveries(callerSub, subscriptionId, page, size);
    }

    private String extractSubject(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return authentication.getName();
    }
}
