package com.swiftdrop.accountsservice.merchant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid merchant request parameters.");
    }
}
