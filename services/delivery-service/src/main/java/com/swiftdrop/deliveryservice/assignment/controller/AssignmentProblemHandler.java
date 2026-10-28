package com.swiftdrop.deliveryservice.assignment.controller;

import com.swiftdrop.deliveryservice.assignment.exception.AssignmentAccessDeniedException;
import com.swiftdrop.deliveryservice.assignment.exception.AssignmentConflictException;
import com.swiftdrop.deliveryservice.assignment.exception.IneligibleCourierException;
import com.swiftdrop.deliveryservice.assignment.exception.InvalidParcelStateException;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 7807/9457 ProblemDetail handler for courier assignment operations.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = AssignmentController.class)
public class AssignmentProblemHandler {

    @ExceptionHandler(AssignmentAccessDeniedException.class)
    ProblemDetail handleAccessDenied(AssignmentAccessDeniedException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setTitle("Access Denied");
        problem.setType(URI.create("https://swiftdrop.com/errors/ASSIGNMENT_FORBIDDEN"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "ASSIGNMENT_FORBIDDEN");
        return problem;
    }

    @ExceptionHandler(AssignmentConflictException.class)
    ProblemDetail handleConflict(AssignmentConflictException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Assignment Conflict");
        problem.setType(URI.create("https://swiftdrop.com/errors/ASSIGNMENT_CONFLICT"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "ASSIGNMENT_CONFLICT");
        return problem;
    }

    @ExceptionHandler({JpaOptimisticLockingFailureException.class, OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail handleOptimisticLockFailure(Exception e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Concurrent assignment conflict detected: parcel aggregate version changed. Please retry with latest version."
        );
        problem.setTitle("Assignment Conflict");
        problem.setType(URI.create("https://swiftdrop.com/errors/ASSIGNMENT_CONFLICT"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "ASSIGNMENT_CONFLICT");
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Concurrent assignment conflict detected: parcel already has an active assignment."
        );
        problem.setTitle("Assignment Conflict");
        problem.setType(URI.create("https://swiftdrop.com/errors/ASSIGNMENT_CONFLICT"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "ASSIGNMENT_CONFLICT");
        return problem;
    }

    @ExceptionHandler(IneligibleCourierException.class)
    ProblemDetail handleIneligibleCourier(IneligibleCourierException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Ineligible Courier");
        problem.setType(URI.create("https://swiftdrop.com/errors/INELIGIBLE_COURIER"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INELIGIBLE_COURIER");
        return problem;
    }

    @ExceptionHandler(InvalidParcelStateException.class)
    ProblemDetail handleInvalidParcelState(InvalidParcelStateException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid Parcel State");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_PARCEL_STATE"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_PARCEL_STATE");
        return problem;
    }

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationErrors(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for assignment request payload.");
        problem.setTitle("Validation Failed");
        problem.setType(URI.create("https://swiftdrop.com/errors/VALIDATION_FAILED"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "VALIDATION_FAILED");

        List<Map<String, String>> invalidParams = new ArrayList<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", fieldError.getField());
            item.put("message", fieldError.getDefaultMessage());
            invalidParams.add(item);
        }
        problem.setProperty("invalidParameters", invalidParams);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request body.");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/MALFORMED_REQUEST_BODY"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "MALFORMED_REQUEST_BODY");
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid identifier format.");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_IDENTIFIER"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_IDENTIFIER");
        return problem;
    }
}
