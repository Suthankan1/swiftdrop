package com.swiftdrop.deliveryservice.booking.controller;

import com.swiftdrop.deliveryservice.booking.BookingFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Standardized RFC 7807 ProblemDetail handler for merchant order booking operations.
 * Maps domain and input validation failures strictly without exposing sensitive details.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = MerchantParcelBookingController.class)
public class MerchantBookingProblemHandler {

    @ExceptionHandler(BookingFailure.class)
    ProblemDetail handleBookingFailure(BookingFailure e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status()), e.getMessage());
        problem.setTitle(e.code());
        problem.setType(URI.create("https://swiftdrop.com/errors/" + e.code()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", e.code());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationErrors(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for merchant booking request.");
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://swiftdrop.com/errors/INVALID_REQUEST"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INVALID_REQUEST");
        problem.setProperty("timestamp", Instant.now().toString());

        List<BookingFailure.InvalidParameter> invalidParams = new ArrayList<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            invalidParams.add(new BookingFailure.InvalidParameter(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "must be valid"
            ));
        }
        invalidParams.sort(Comparator.comparing(BookingFailure.InvalidParameter::field));
        problem.setProperty("invalidParameters", invalidParams);

        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleMalformedJson(HttpMessageNotReadableException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON or unsupported parameter format in request body.");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://swiftdrop.com/errors/MALFORMED_REQUEST"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "MALFORMED_REQUEST");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
