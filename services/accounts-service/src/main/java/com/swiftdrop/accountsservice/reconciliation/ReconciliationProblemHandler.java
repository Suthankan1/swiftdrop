package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.keycloak.IdentityNotFoundException;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakAdminUnavailableException;
import com.swiftdrop.accountsservice.identity.keycloak.UnapprovedRoleException;
import com.swiftdrop.accountsservice.merchant.MerchantNotFoundException;
import com.swiftdrop.accountsservice.staff.StaffProfileNotFoundException;
import com.swiftdrop.accountsservice.staff.StaffProvisioningException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps identity reconciliation and recovery exceptions to RFC 7807 ProblemDetail responses.
 */
@RestControllerAdvice(basePackageClasses = AdminReconciliationController.class)
public class ReconciliationProblemHandler {

    @ExceptionHandler(KeycloakAdminUnavailableException.class)
    ProblemDetail handleUnavailable(KeycloakAdminUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(IdentityNotFoundException.class)
    ProblemDetail handleIdentityNotFound(IdentityNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnapprovedRoleException.class)
    ProblemDetail handleUnapprovedRole(UnapprovedRoleException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(StaffProvisioningException.class)
    ProblemDetail handleStaffProvisioning(StaffProvisioningException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(StaffProfileNotFoundException.class)
    ProblemDetail handleStaffNotFound(StaffProfileNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    ProblemDetail handleMerchantNotFound(MerchantNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticConflict(ObjectOptimisticLockingFailureException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The staff profile changed concurrently; retry against the current state.");
    }
}
