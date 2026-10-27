package com.swiftdrop.deliveryservice.sse.controller;

import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException;
import com.swiftdrop.deliveryservice.sse.exception.SseConnectionLimitExceededException;
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
 * RFC 7807 / 9457 ProblemDetail handler for SSE streaming endpoints.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = ParcelSseController.class)
public class ParcelSseProblemHandler {

    @ExceptionHandler(SseConnectionLimitExceededException.class)
    public ProblemDetail handleLimitExceeded(SseConnectionLimitExceededException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setTitle("Too Many Requests");
        problem.setType(URI.create("https://swiftdrop.com/errors/SSE_LIMIT_EXCEEDED"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "SSE_LIMIT_EXCEEDED");
        problem.setProperty("limitType", e.getLimitType());
        problem.setProperty("maxAllowed", e.getMaxAllowed());
        return problem;
    }

    @ExceptionHandler(ParcelNotFoundException.class)
    public ProblemDetail handleNotFound(ParcelNotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Parcel Not Found");
        problem.setType(URI.create("https://swiftdrop.com/errors/PARCEL_NOT_FOUND"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "PARCEL_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(ParcelAccessDeniedException.class)
    public ProblemDetail handleAccessDenied(ParcelAccessDeniedException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setTitle("Access Denied");
        problem.setType(URI.create("https://swiftdrop.com/errors/PARCEL_ACCESS_DENIED"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "PARCEL_ACCESS_DENIED");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_REQUEST"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }
}
