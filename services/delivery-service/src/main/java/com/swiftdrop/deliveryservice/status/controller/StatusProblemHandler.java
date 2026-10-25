package com.swiftdrop.deliveryservice.status.controller;

import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.status.IllegalTransitionException;
import com.swiftdrop.deliveryservice.status.InvalidTransitionParametersException;
import com.swiftdrop.deliveryservice.status.StatusAccessDeniedException;
import com.swiftdrop.deliveryservice.status.TerminalStateMutationException;
import com.swiftdrop.deliveryservice.status.VersionConflictException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * Standardized RFC 7807 ProblemDetail handler for parcel status operations.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = StatusController.class)
public class StatusProblemHandler {

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

    @ExceptionHandler(StatusAccessDeniedException.class)
    ProblemDetail handleAccessDenied(StatusAccessDeniedException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        problem.setTitle("Access Denied");
        problem.setType(URI.create("https://swiftdrop.com/errors/STATUS_TRANSITION_FORBIDDEN"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "STATUS_TRANSITION_FORBIDDEN");
        return problem;
    }

    @ExceptionHandler(VersionConflictException.class)
    ProblemDetail handleVersionConflict(VersionConflictException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Optimistic Lock Failure");
        problem.setType(URI.create("https://swiftdrop.com/errors/VERSION_CONFLICT"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "VERSION_CONFLICT");
        problem.setProperty("expectedVersion", e.getExpectedVersion());
        problem.setProperty("actualVersion", e.getActualVersion());
        return problem;
    }

    @ExceptionHandler({JpaOptimisticLockingFailureException.class, OptimisticLockException.class})
    ProblemDetail handleJpaOptimisticLock(Exception e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Concurrent modification detected. The parcel was modified by another transaction. Please retry with the latest version."
        );
        problem.setTitle("Optimistic Lock Failure");
        problem.setType(URI.create("https://swiftdrop.com/errors/CONCURRENT_MODIFICATION_CONFLICT"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "CONCURRENT_MODIFICATION_CONFLICT");
        return problem;
    }

    @ExceptionHandler(TerminalStateMutationException.class)
    ProblemDetail handleTerminalStateMutation(TerminalStateMutationException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Terminal State Immutability Violation");
        problem.setType(URI.create("https://swiftdrop.com/errors/TERMINAL_STATE_IMMUTABLE"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "TERMINAL_STATE_IMMUTABLE");
        return problem;
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalTransitionException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Illegal Status Transition");
        problem.setType(URI.create("https://swiftdrop.com/errors/ILLEGAL_STATUS_TRANSITION"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "ILLEGAL_STATUS_TRANSITION");
        return problem;
    }

    @ExceptionHandler(InvalidTransitionParametersException.class)
    ProblemDetail handleInvalidTransitionParameters(InvalidTransitionParametersException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid Transition Parameters");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_TRANSITION_PARAMETERS"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_TRANSITION_PARAMETERS");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationErrors(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for request payload.");
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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request body or invalid enum value.");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/MALFORMED_REQUEST_BODY"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "MALFORMED_REQUEST_BODY");
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid parcel identifier format.");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_IDENTIFIER"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("code", "INVALID_IDENTIFIER");
        return problem;
    }
}
