package com.swiftdrop.accountsservice.profile;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Domain and authentication failures remain separate; persistence/programming errors propagate. */
@RestControllerAdvice
public class ProfileProblemHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidIdentity(IllegalArgumentException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Profile identity attributes are invalid.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail missingIdentity(AuthenticationException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Verified identity is required.");
    }
}
