package com.swiftdrop.deliveryservice.proof.controller;

import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.proof.exception.IllegalLifecycleStageException;
import com.swiftdrop.deliveryservice.proof.exception.ProofAccessDeniedException;
import com.swiftdrop.deliveryservice.proof.exception.ProofNotFoundException;
import com.swiftdrop.deliveryservice.proof.exception.ProofValidationException;
import com.swiftdrop.deliveryservice.proof.storage.exception.InvalidProofObjectException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageAccessDeniedException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageTimeoutException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(basePackages = "com.swiftdrop.deliveryservice.proof.controller")
public class ProofProblemHandler {

    @ExceptionHandler(ParcelNotFoundException.class)
    public ProblemDetail handleParcelNotFound(ParcelNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Parcel Not Found");
        problem.setType(URI.create("urn:problem:parcel-not-found"));
        return problem;
    }

    @ExceptionHandler({ProofNotFoundException.class, ProofObjectNotFoundException.class})
    public ProblemDetail handleProofNotFound(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Proof Not Found");
        problem.setType(URI.create("urn:problem:proof-not-found"));
        return problem;
    }

    @ExceptionHandler(ProofAccessDeniedException.class)
    public ProblemDetail handleAccessDenied(ProofAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setType(URI.create("urn:problem:proof-access-denied"));
        return problem;
    }

    @ExceptionHandler({ProofValidationException.class, InvalidProofObjectException.class})
    public ProblemDetail handleValidation(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Proof Parameters");
        problem.setType(URI.create("urn:problem:invalid-proof-parameters"));
        return problem;
    }

    @ExceptionHandler(IllegalLifecycleStageException.class)
    public ProblemDetail handleIllegalStage(IllegalLifecycleStageException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Illegal Lifecycle Stage");
        problem.setType(URI.create("urn:problem:illegal-lifecycle-stage"));
        return problem;
    }

    @ExceptionHandler(ProofStorageTimeoutException.class)
    public ProblemDetail handleTimeout(ProofStorageTimeoutException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage());
        problem.setTitle("Storage Timeout");
        problem.setType(URI.create("urn:problem:storage-timeout"));
        return problem;
    }

    @ExceptionHandler({ProofStorageUnavailableException.class, ProofStorageAccessDeniedException.class})
    public ProblemDetail handleStorageFailure(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Private proof storage is unavailable");
        problem.setTitle("Storage Service Unavailable");
        problem.setType(URI.create("urn:problem:storage-unavailable"));
        return problem;
    }
}
