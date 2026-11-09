package com.swiftdrop.communicationsservice.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Last-resort handler so every error is an RFC 7807 problem (audit 101). Controller-scoped handlers run
 * first (they are ordered ahead of this one); this only sees errors none of them claimed.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);

    /** Anything unexpected: log it, never echo internal details to the caller. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) throws Exception {
        // Security decisions belong to the security chain (401/403 with its own problem format); never turn them into 500s.
        if (e instanceof org.springframework.security.access.AccessDeniedException
                || e instanceof org.springframework.security.core.AuthenticationException) {
            throw e;
        }
        // Spring's own errors (404 no route, 405, 415, 400 binding, ResponseStatusException) keep their status.
        if (e instanceof ErrorResponse error) {
            ProblemDetail problem = error.getBody();
            problem.setInstance(URI.create(request.getRequestURI()));
            return ResponseEntity.status(error.getStatusCode()).body(problem);
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.");
        problem.setTitle("Internal Server Error");
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
