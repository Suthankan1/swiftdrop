package com.swiftdrop.accountsservice.staff;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps staff authorization, lifecycle, and lookup domain exceptions to RFC 7807 ProblemDetail responses.
 */
@RestControllerAdvice(basePackageClasses = StaffProfileController.class)
public class StaffProblemHandler {

    @ExceptionHandler(StaffAccessDeniedException.class)
    ProblemDetail handleStaffAccessDenied(StaffAccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(StaffRoleMismatchException.class)
    ProblemDetail handleStaffRoleMismatch(StaffRoleMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(StaffProfileInactiveException.class)
    ProblemDetail handleStaffProfileInactive(StaffProfileInactiveException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(StaffProfileNotFoundException.class)
    ProblemDetail handleStaffProfileNotFound(StaffProfileNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(StaffProvisioningException.class)
    ProblemDetail handleStaffProvisioning(StaffProvisioningException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid staff request parameters.");
    }
}
