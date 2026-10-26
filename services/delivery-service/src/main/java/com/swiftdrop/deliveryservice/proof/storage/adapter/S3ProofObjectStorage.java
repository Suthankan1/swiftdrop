package com.swiftdrop.deliveryservice.proof.storage.adapter;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectUpload;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import com.swiftdrop.deliveryservice.proof.storage.config.ProofStorageProperties;
import com.swiftdrop.deliveryservice.proof.storage.exception.InvalidProofObjectException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageAccessDeniedException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageTimeoutException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageUnavailableException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.EOFException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Private S3-compatible implementation backed by the one configured bucket. */
public final class S3ProofObjectStorage implements ProofObjectStorage {

    private final S3Client s3Client;
    private final String bucket;
    private final ProofObjectKeyGenerator keyGenerator;

    public S3ProofObjectStorage(S3Client s3Client, ProofStorageProperties properties) {
        this(s3Client, properties, new ProofObjectKeyGenerator());
    }

    S3ProofObjectStorage(
            S3Client s3Client,
            ProofStorageProperties properties,
            ProofObjectKeyGenerator keyGenerator
    ) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucket = Objects.requireNonNull(properties, "properties").requiredBucket();
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "keyGenerator");
    }

    @Override
    public StoredProofObject store(ProofObjectUpload upload) {
        if (upload == null) {
            throw new InvalidProofObjectException("Proof upload is required");
        }

        ProofObjectReference reference = keyGenerator.generate();
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(reference.value())
                .contentLength(upload.contentLength())
                .ifNoneMatch("*");
        if (upload.contentType() != null) {
            request.contentType(upload.contentType());
        }

        try {
            ExactLengthInputStream content = new ExactLengthInputStream(upload.content(), upload.contentLength());
            s3Client.putObject(request.build(), RequestBody.fromInputStream(content, upload.contentLength()));
            return new StoredProofObject(reference, upload.contentLength(), upload.contentType());
        } catch (RuntimeException exception) {
            throw mapFailure("store", exception);
        }
    }

    @Override
    public RetrievedProofObject retrieve(ProofObjectReference reference) {
        if (reference == null) {
            throw new InvalidProofObjectException("Proof object reference is required");
        }

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(reference.value())
                .build();
        try {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            GetObjectResponse metadata = response.response();
            long contentLength = metadata.contentLength() == null ? 0 : metadata.contentLength();
            return new RetrievedProofObject(response, contentLength, metadata.contentType());
        } catch (RuntimeException exception) {
            throw mapFailure("retrieve", exception);
        }
    }

    private ProofStorageException mapFailure(String operation, RuntimeException exception) {
        if (hasCause(exception, EOFException.class)) {
            return new InvalidProofObjectException("Proof content did not match its declared length", exception);
        }
        if (exception instanceof S3Exception s3Exception) {
            return switch (s3Exception.statusCode()) {
                case 403 -> new ProofStorageAccessDeniedException(
                        "Private proof storage denied the " + operation + " operation", exception);
                case 404 -> new ProofObjectNotFoundException("Private proof object was not found", exception);
                case 408, 504 -> new ProofStorageTimeoutException(
                        "Private proof storage timed out during " + operation, exception);
                default -> new ProofStorageUnavailableException(
                        "Private proof storage failed during " + operation, exception);
            };
        }
        if (hasCause(exception, SocketTimeoutException.class) || hasCause(exception, TimeoutException.class)) {
            return new ProofStorageTimeoutException(
                    "Private proof storage timed out during " + operation, exception);
        }
        if (exception instanceof SdkClientException) {
            return new ProofStorageUnavailableException(
                    "Private proof storage is unavailable during " + operation, exception);
        }
        return new ProofStorageUnavailableException(
                "Private proof storage failed during " + operation, exception);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
