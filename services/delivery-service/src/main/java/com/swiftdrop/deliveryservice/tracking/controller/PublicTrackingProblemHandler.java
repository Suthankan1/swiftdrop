package com.swiftdrop.deliveryservice.tracking.controller;

import com.swiftdrop.deliveryservice.tracking.exception.InvalidTrackingReferenceException;
import com.swiftdrop.deliveryservice.tracking.exception.TrackingNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * RFC 7807 / 9457 ProblemDetail handler for public tracking REST operations.
 * Produces consistent, privacy-preserving errors without leaking internal state.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = PublicTrackingController.class)
public class PublicTrackingProblemHandler {

    @ExceptionHandler(TrackingNotFoundException.class)
    ProblemDetail handleNotFound(TrackingNotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Tracking Reference Not Found");
        problem.setType(URI.create("https://swiftdrop.com/errors/TRACKING_NOT_FOUND"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "TRACKING_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(InvalidTrackingReferenceException.class)
    ProblemDetail handleInvalidReference(InvalidTrackingReferenceException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid Tracking Reference");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_TRACKING_REFERENCE"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_TRACKING_REFERENCE");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/BAD_REQUEST"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "BAD_REQUEST");
        return problem;
    }
}
