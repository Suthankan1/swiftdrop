package com.swiftdrop.deliveryservice.proof.storage.adapter;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectUpload;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import com.swiftdrop.deliveryservice.proof.storage.config.ProofStorageProperties;
import com.swiftdrop.deliveryservice.proof.storage.exception.InvalidProofObjectException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageAccessDeniedException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageTimeoutException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofStorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ProofObjectStorageTest {

    private static final String BUCKET = "private-proof-bucket";

    private S3Client s3Client;
    private S3ProofObjectStorage storage;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        ProofStorageProperties properties = new ProofStorageProperties();
        properties.setBucket(BUCKET);
        storage = new S3ProofObjectStorage(s3Client, properties);
    }

    @Test
    void storesExactBytesUnderGeneratedNonOverwritingOpaqueReference() throws IOException {
        byte[] expected = new byte[]{1, 2, 3, 4, 5};
        byte[][] stored = new byte[1][];
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    stored[0] = body.contentStreamProvider().newStream().readAllBytes();
                    return PutObjectResponse.builder().eTag("synthetic-etag").build();
                });

        StoredProofObject result = storage.store(new ProofObjectUpload(
                new ByteArrayInputStream(expected), expected.length, "image/example"));

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();

        assertThat(stored[0]).containsExactly(expected);
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).matches(
                "proofs/v1/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(request.ifNoneMatch()).isEqualTo("*");
        assertThat(request.contentLength()).isEqualTo(expected.length);
        assertThat(request.contentType()).isEqualTo("image/example");
        assertThat(result.reference().value()).isEqualTo(request.key());
        assertThat(result.reference().value())
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain(BUCKET);
    }

    @Test
    void keyGenerationIsCollisionResistantAndContainsNoPersonalOrFilenameData() {
        Set<String> keys = new HashSet<>();
        ProofObjectKeyGenerator generator = new ProofObjectKeyGenerator();

        for (int index = 0; index < 2_000; index++) {
            keys.add(generator.generate().value());
        }

        assertThat(keys).hasSize(2_000);
        assertThat(keys).allSatisfy(key -> assertThat(key)
                .startsWith("proofs/v1/")
                .doesNotContain("../", "\\", "customer", "recipient", ".png", ".jpg"));
    }

    @Test
    void retrievesExactPrivateBytesAndMetadataUsingValidatedReference() throws IOException {
        byte[] expected = new byte[]{9, 8, 7};
        ProofObjectReference reference = new ProofObjectReference(
                "proofs/v1/123e4567-e89b-42d3-a456-426614174000");
        GetObjectResponse metadata = GetObjectResponse.builder()
                .contentLength((long) expected.length)
                .contentType("image/example")
                .build();
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                metadata, AbortableInputStream.create(new ByteArrayInputStream(expected)));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        try (RetrievedProofObject result = storage.retrieve(reference)) {
            assertThat(result.content().readAllBytes()).containsExactly(expected);
            assertThat(result.contentLength()).isEqualTo(expected.length);
            assertThat(result.contentType()).contains("image/example");
        }

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo(reference.value());
    }

    @Test
    void rejectsTruncatedContentAndNeverReturnsFalseSuccess() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    try {
                        body.contentStreamProvider().newStream().readAllBytes();
                    } catch (IOException exception) {
                        throw SdkClientException.builder().message("synthetic failure").cause(exception).build();
                    }
                    return PutObjectResponse.builder().build();
                });

        assertThatThrownBy(() -> storage.store(new ProofObjectUpload(
                new ByteArrayInputStream(new byte[]{1, 2}), 3, "image/example")))
                .isInstanceOf(InvalidProofObjectException.class)
                .hasMessage("Proof content did not match its declared length");
    }

    @Test
    void transmitsOnlyDeclaredBytesAndLeavesTheCallerOwnedStreamOpen() throws IOException {
        AtomicBoolean callerStreamClosed = new AtomicBoolean();
        InputStream callerStream = new FilterInputStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3})) {
            @Override
            public void close() throws IOException {
                callerStreamClosed.set(true);
                super.close();
            }
        };
        byte[][] stored = new byte[1][];
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    try (InputStream requestStream = body.contentStreamProvider().newStream()) {
                        stored[0] = requestStream.readAllBytes();
                    }
                    return PutObjectResponse.builder().build();
                });

        storage.store(new ProofObjectUpload(callerStream, 2, "application/octet-stream"));

        assertThat(stored[0]).containsExactly(1, 2);
        assertThat(callerStreamClosed).isFalse();
        assertThat(callerStream.read()).isEqualTo(3);
        callerStream.close();
        assertThat(callerStreamClosed).isTrue();
    }

    @Test
    void mapsNotFoundAccessDeniedTimeoutAndUnavailableWithoutSensitiveDetails() {
        ProofObjectReference reference = new ProofObjectReference(
                "proofs/v1/123e4567-e89b-42d3-a456-426614174000");
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(s3Failure(404), s3Failure(403), s3Failure(504),
                        SdkClientException.builder().message("credential=private-value bucket=" + BUCKET).build());

        assertSafeFailure(() -> storage.retrieve(reference), ProofObjectNotFoundException.class);
        assertSafeFailure(() -> storage.retrieve(reference), ProofStorageAccessDeniedException.class);
        assertSafeFailure(() -> storage.retrieve(reference), ProofStorageTimeoutException.class);
        assertSafeFailure(() -> storage.retrieve(reference), ProofStorageUnavailableException.class);
    }

    @Test
    void mapsClientSocketTimeoutToTypedTimeout() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(
                SdkClientException.builder()
                        .message("synthetic client timeout")
                        .cause(new SocketTimeoutException("synthetic"))
                        .build());

        ProofObjectReference reference = new ProofObjectReference(
                "proofs/v1/123e4567-e89b-42d3-a456-426614174000");
        assertThatThrownBy(() -> storage.retrieve(reference))
                .isInstanceOf(ProofStorageTimeoutException.class)
                .hasMessage("Private proof storage timed out during retrieve");
    }

    @Test
    void failedOrAmbiguousWriteIsNotReportedAsSuccessOrBlindlyRetried() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Failure(412));

        assertSafeFailure(() -> storage.store(new ProofObjectUpload(
                new ByteArrayInputStream(new byte[]{1}), 1, "image/example")),
                ProofStorageUnavailableException.class);

        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void deleteInvokesS3DeleteObjectWithCorrectBucketAndKey() {
        ProofObjectReference reference = new ProofObjectReference(
                "proofs/v1/123e4567-e89b-42d3-a456-426614174000");

        storage.delete(reference);

        org.mockito.ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> captor =
                org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().key()).isEqualTo(reference.value());
    }

    @Test
    void deleteMapsS3ExceptionsSafely() {
        when(s3Client.deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class)))
                .thenThrow(s3Failure(500));

        ProofObjectReference reference = new ProofObjectReference(
                "proofs/v1/123e4567-e89b-42d3-a456-426614174000");

        assertSafeFailure(() -> storage.delete(reference), ProofStorageUnavailableException.class);
    }

    private void assertSafeFailure(Runnable operation, Class<? extends RuntimeException> expectedType) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(expectedType)
                .hasMessageNotContaining(BUCKET)
                .hasMessageNotContaining("private-value")
                .hasMessageNotContaining("123e4567");
    }

    private S3Exception s3Failure(int statusCode) {
        return (S3Exception) S3Exception.builder()
                .statusCode(statusCode)
                .message("provider detail bucket=" + BUCKET + " credential=private-value")
                .build();
    }
}
