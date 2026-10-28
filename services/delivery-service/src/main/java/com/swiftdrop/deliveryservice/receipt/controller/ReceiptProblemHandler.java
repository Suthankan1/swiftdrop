package com.swiftdrop.deliveryservice.receipt.controller;

import com.swiftdrop.deliveryservice.receipt.exception.ReceiptAccessDeniedException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotAvailableException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotFoundException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptRenderingException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptResourceLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;

/**
 * Standardized RFC 7807 ProblemDetail handler for receipt operations.
 */
@RestControllerAdvice(basePackageClasses = ReceiptController.class)
public class ReceiptProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ReceiptProblemHandler.class);

    @ExceptionHandler(ReceiptNotFoundException.class)
    ProblemDetail handleNotFound(ReceiptNotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Receipt not available or parcel not completed");
        problem.setTitle("Receipt Not Found");
        problem.setType(URI.create("https://swiftdrop.com/errors/RECEIPT_NOT_FOUND"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "RECEIPT_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(ReceiptNotAvailableException.class)
    ProblemDetail handleNotAvailable(ReceiptNotAvailableException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Receipt not available or parcel not completed");
        problem.setTitle("Receipt Unavailable");
        problem.setType(URI.create("https://swiftdrop.com/errors/RECEIPT_UNAVAILABLE"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "RECEIPT_UNAVAILABLE");
        return problem;
    }

    @ExceptionHandler(ReceiptAccessDeniedException.class)
    ProblemDetail handleAccessDenied(ReceiptAccessDeniedException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Forbidden - unauthorized to download this receipt");
        problem.setTitle("Receipt Access Denied");
        problem.setType(URI.create("https://swiftdrop.com/errors/RECEIPT_FORBIDDEN"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "RECEIPT_FORBIDDEN");
        return problem;
    }

    @ExceptionHandler(ReceiptResourceLimitException.class)
    ProblemDetail handleResourceLimit(ReceiptResourceLimitException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Receipt Resource Limit Exceeded");
        problem.setType(URI.create("https://swiftdrop.com/errors/RECEIPT_RESOURCE_LIMIT_EXCEEDED"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "RECEIPT_RESOURCE_LIMIT_EXCEEDED");
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid parcel identifier format");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_IDENTIFIER"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_IDENTIFIER");
        return problem;
    }

    @ExceptionHandler(ReceiptRenderingException.class)
    ProblemDetail handleRenderingError(ReceiptRenderingException e, HttpServletRequest request) {
        log.error("Failed to render PDF receipt for request {}: {}", request.getRequestURI(), e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An error occurred while generating the receipt document.");
        problem.setTitle("Receipt Generation Failed");
        problem.setType(URI.create("https://swiftdrop.com/errors/RECEIPT_GENERATION_FAILED"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "RECEIPT_GENERATION_FAILED");
        return problem;
    }
}
