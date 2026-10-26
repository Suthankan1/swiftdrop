package com.swiftdrop.deliveryservice.query.controller;

import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;

/**
 * RFC 7807 / 9457 ProblemDetail handler for parcel query REST operations.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = ParcelQueryController.class)
public class ParcelQueryProblemHandler {

    @ExceptionHandler(ParcelNotFoundException.class)
    ProblemDetail handleNotFound(ParcelNotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Parcel Not Found");
        problem.setType(URI.create("https://swiftdrop.com/errors/PARCEL_NOT_FOUND"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "PARCEL_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(ParcelAccessDeniedException.class)
    ProblemDetail handleAccessDenied(ParcelAccessDeniedException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setTitle("Access Denied");
        problem.setType(URI.create("https://swiftdrop.com/errors/PARCEL_ACCESS_DENIED"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "PARCEL_ACCESS_DENIED");
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        String detail = "Invalid parameter '" + e.getName() + "': value '" + e.getValue() + "' is not of expected type.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid Request Parameter");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_PARAMETER"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_PARAMETER");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_REQUEST"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }
}
