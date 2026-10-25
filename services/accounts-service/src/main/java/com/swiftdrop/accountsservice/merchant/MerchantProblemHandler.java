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

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        String detail = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : "Invalid merchant request parameters.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
