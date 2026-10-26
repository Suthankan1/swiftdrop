package com.swiftdrop.pricing.api;

import com.swiftdrop.pricing.error.AmbiguousRateRuleException;
import com.swiftdrop.pricing.error.CurrencyMismatchException;
import com.swiftdrop.pricing.error.InactiveZoneException;
import com.swiftdrop.pricing.error.InvalidMeasurementsException;
import com.swiftdrop.pricing.error.PricingDomainException;
import com.swiftdrop.pricing.error.QuoteNotFoundException;
import com.swiftdrop.pricing.error.RateRuleNotFoundException;
import com.swiftdrop.pricing.error.RateVersionImmutableException;
import com.swiftdrop.pricing.error.RateVersionNotFoundException;
import com.swiftdrop.pricing.error.ZoneNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * Standardized RFC 7807 ProblemDetail handler for Pricing & Zones REST endpoints.
 * Aligns with contracts/rest/problem-detail.yaml and openapi-pricing.yaml.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = PricingController.class)
public class PricingProblemHandler {

    private static final String ERROR_BASE_URI = "https://swiftdrop.com/errors/";

    @ExceptionHandler(QuoteNotFoundException.class)
    ProblemDetail handleQuoteNotFound(QuoteNotFoundException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.NOT_FOUND, "Quote Not Found", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(ZoneNotFoundException.class)
    ProblemDetail handleZoneNotFound(ZoneNotFoundException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, "Zone Not Found", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(InactiveZoneException.class)
    ProblemDetail handleInactiveZone(InactiveZoneException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, "Inactive Zone", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(RateRuleNotFoundException.class)
    ProblemDetail handleRateRuleNotFound(RateRuleNotFoundException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, "Rate Rule Not Found", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(AmbiguousRateRuleException.class)
    ProblemDetail handleAmbiguousRateRule(AmbiguousRateRuleException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, "Ambiguous Rate Rule", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(RateVersionNotFoundException.class)
    ProblemDetail handleRateVersionNotFound(RateVersionNotFoundException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, "Rate Version Not Found", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(RateVersionImmutableException.class)
    ProblemDetail handleRateVersionImmutable(RateVersionImmutableException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.CONFLICT, "Rate Version Immutable", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail handleCurrencyMismatch(CurrencyMismatchException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Currency Mismatch", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidMeasurementsException.class)
    ProblemDetail handleInvalidMeasurements(InvalidMeasurementsException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Invalid Measurements", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(PricingDomainException.class)
    ProblemDetail handlePricingDomainException(PricingDomainException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Pricing Domain Error", e.getMessage(),
                e.getErrorCode(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationErrors(MethodArgumentNotValidException e, HttpServletRequest request) {
        ProblemDetail problem = buildProblem(HttpStatus.BAD_REQUEST, "Validation Failed",
                "Validation failed for quote request payload.", "VALIDATION_FAILED", request.getRequestURI());

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
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request",
                "Malformed JSON request body or unparseable field values.", "MALFORMED_REQUEST_BODY", request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Invalid Identifier",
                "Invalid identifier format in request path.", "INVALID_IDENTIFIER", request.getRequestURI());
    }

    @ExceptionHandler(ArithmeticException.class)
    ProblemDetail handleArithmeticOverflow(ArithmeticException e, HttpServletRequest request) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, "Pricing Arithmetic Overflow",
                "Monetary calculation exceeded numeric capacity: " + e.getMessage(),
                "ARITHMETIC_OVERFLOW", request.getRequestURI());
    }

    private ProblemDetail buildProblem(HttpStatus status, String title, String detail, String code, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(ERROR_BASE_URI + code));
        problem.setInstance(URI.create(instance));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
