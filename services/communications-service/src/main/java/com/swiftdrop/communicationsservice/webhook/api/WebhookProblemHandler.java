package com.swiftdrop.communicationsservice.webhook.api;

import com.swiftdrop.communicationsservice.client.AccountsContactException;
import com.swiftdrop.communicationsservice.client.AccountsSecurityException;
import com.swiftdrop.communicationsservice.client.AccountsServiceUnavailableException;
import com.swiftdrop.communicationsservice.webhook.exception.CrossTenantAccessException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidEventFilterException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookReplayException;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotApprovedException;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotFoundException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookDeliveryNotFoundException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookSecretEncryptionException;
import com.swiftdrop.communicationsservice.webhook.exception.WebhookSubscriptionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * Controller advice translating webhook domain exceptions into RFC 7807 ProblemDetail responses.
 * Strictly avoids leaking internal stack traces, DB details, or plaintext secret references.
 */
@RestControllerAdvice(basePackageClasses = WebhookSubscriptionController.class)
public class WebhookProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookProblemHandler.class);
    private static final String PROBLEM_BASE_TYPE = "https://swiftdrop.com/errors/";

    @ExceptionHandler(MerchantNotApprovedException.class)
    ProblemDetail handleMerchantNotApproved(MerchantNotApprovedException ex) {
        log.warn("Merchant approval check failed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Merchant Not Approved");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "MERCHANT_NOT_APPROVED"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(CrossTenantAccessException.class)
    ProblemDetail handleCrossTenantAccess(CrossTenantAccessException ex) {
        log.warn("Cross-tenant access rejected: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "TENANT_ACCESS_DENIED"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(WebhookSubscriptionNotFoundException.class)
    ProblemDetail handleSubscriptionNotFound(WebhookSubscriptionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Subscription Not Found");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "SUBSCRIPTION_NOT_FOUND"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    ProblemDetail handleMerchantNotFound(MerchantNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Merchant Not Found");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "MERCHANT_NOT_FOUND"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(WebhookDeliveryNotFoundException.class)
    ProblemDetail handleDeliveryNotFound(WebhookDeliveryNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Delivery Not Found");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "DELIVERY_NOT_FOUND"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(InvalidWebhookReplayException.class)
    ProblemDetail handleInvalidReplay(InvalidWebhookReplayException ex) {
        log.warn("Invalid webhook replay rejected: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "INVALID_REPLAY_STATE"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(InvalidWebhookDestinationException.class)
    ProblemDetail handleInvalidDestination(InvalidWebhookDestinationException ex) {
        log.warn("Invalid webhook destination rejected: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Webhook Destination");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "INVALID_WEBHOOK_URL"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(InvalidEventFilterException.class)
    ProblemDetail handleInvalidEventFilter(InvalidEventFilterException ex) {
        log.warn("Invalid event filter rejected: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Event Filter");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "INVALID_EVENT_FILTER"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed for webhook subscription request.");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "VALIDATION_ERROR"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body.");
        problem.setTitle("Bad Request");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "MALFORMED_REQUEST"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "BAD_REQUEST"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(AccountsServiceUnavailableException.class)
    ProblemDetail handleAccountsUnavailable(AccountsServiceUnavailableException ex) {
        log.error("Accounts service unavailable during webhook operation", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Accounts service is currently unavailable. Please retry shortly.");
        problem.setTitle("Service Unavailable");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "ACCOUNTS_UNAVAILABLE"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(AccountsSecurityException.class)
    ProblemDetail handleAccountsSecurity(AccountsSecurityException ex) {
        log.error("Machine authorization rejected by Accounts during webhook operation", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Service authorization failure communicating with Accounts.");
        problem.setTitle("Internal Security Error");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "INTERNAL_SECURITY_ERROR"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(WebhookSecretEncryptionException.class)
    ProblemDetail handleEncryptionFailure(WebhookSecretEncryptionException ex) {
        log.error("Cryptographic operation failed for webhook secret", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Failed to securely protect webhook signing credentials.");
        problem.setTitle("Cryptographic Error");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "CRYPTO_ERROR"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(AccountsContactException.class)
    ProblemDetail handleAccountsGeneralException(AccountsContactException ex) {
        log.error("General error from Accounts service", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Error communicating with Accounts service.");
        problem.setTitle("Bad Gateway");
        problem.setType(URI.create(PROBLEM_BASE_TYPE + "ACCOUNTS_ERROR"));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
