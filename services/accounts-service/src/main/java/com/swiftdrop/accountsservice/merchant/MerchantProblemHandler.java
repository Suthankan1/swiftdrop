package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.merchant.credential.ApiKeyLimitExceededException;
import com.swiftdrop.accountsservice.merchant.credential.ApiKeyNotFoundException;
import com.swiftdrop.accountsservice.merchant.credential.InvalidApiKeyRequestException;
import com.swiftdrop.accountsservice.merchant.credential.InvalidApiKeyScopeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps merchant authorization and lifecycle domain exceptions to RFC 7807 ProblemDetail responses.
 */
@RestControllerAdvice(basePackageClasses = MerchantController.class)
public class MerchantProblemHandler {

    @ExceptionHandler(TenantAccessDeniedException.class)
    ProblemDetail handleTenantAccessDenied(TenantAccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(MerchantNotApprovedException.class)
    ProblemDetail handleMerchantNotApproved(MerchantNotApprovedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    ProblemDetail handleMerchantNotFound(MerchantNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    ProblemDetail handleApiKeyNotFound(ApiKeyNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ApiKeyLimitExceededException.class)
    ProblemDetail handleApiKeyLimitExceeded(ApiKeyLimitExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidApiKeyScopeException.class)
    ProblemDetail handleInvalidApiKeyScope(InvalidApiKeyScopeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(InvalidApiKeyRequestException.class)
    ProblemDetail handleInvalidApiKeyRequest(InvalidApiKeyRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MerchantConflictException.class)
    ProblemDetail handleMerchantConflict(MerchantConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed for merchant enrollment request.");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed or unauthorized request body.");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        String msg = ((ex.getMessage() != null ? ex.getMessage() : "") + " "
                + (ex.getRootCause() != null && ex.getRootCause().getMessage() != null ? ex.getRootCause().getMessage() : "")).toLowerCase();
        if (msg.contains("uq_merchants_keycloak_sub") || msg.contains("keycloak_sub")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Authenticated user already owns a merchant tenant");
        }
        if (msg.contains("uq_merchants_registration_number") || msg.contains("registration_number")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Merchant registration number already exists");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Merchant unique constraint violated");
    }

    @ExceptionHandler(MerchantIllegalStateTransitionException.class)
    ProblemDetail handleIllegalStateTransition(MerchantIllegalStateTransitionException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MerchantConcurrencyConflictException.class)
    ProblemDetail handleConcurrencyConflict(MerchantConcurrencyConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLockingFailure(org.springframework.dao.OptimisticLockingFailureException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Merchant record changed concurrently; retry against current state.");
    }

    @ExceptionHandler(MerchantDecisionValidationException.class)
    ProblemDetail handleDecisionValidation(MerchantDecisionValidationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(com.swiftdrop.accountsservice.staff.StaffAccessDeniedException.class)
    ProblemDetail handleStaffAccessDenied(com.swiftdrop.accountsservice.staff.StaffAccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        String detail = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : "Invalid merchant request parameters.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
