package com.swiftdrop.deliveryservice.proof.storage.adapter;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectReference;
import com.swiftdrop.deliveryservice.proof.storage.ProofObjectUpload;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import com.swiftdrop.deliveryservice.proof.storage.StoredProofObject;
import com.swiftdrop.deliveryservice.proof.storage.config.ProofStorageProperties;
import com.swiftdrop.deliveryservice.proof.storage.exception.InvalidProofObjectException;
import com.swiftdrop.deliveryservice.proof.storage.exception.ProofObjectNotFoundException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in verification against the Platform-owned loopback MinIO provider. */
class S3ProofObjectStorageMinioIT {

    private static final String ENABLE_ENV = "SWIFTDROP_MINIO_IT_ENABLED";
    private static final String APPROVED_BUCKET = "swiftdrop-pod";

    @Test
    void realProviderRoundTripIsPrivateNonOverwritingAndSafelyCleanedUp() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv(ENABLE_ENV)),
                () -> "Real MinIO integration disabled; set " + ENABLE_ENV + "=true with approved credentials");

        URI endpoint = URI.create(requiredEnvironment("S3_ENDPOINT"));
        String bucket = requiredEnvironment("SWIFTDROP_STORAGE_BUCKET");
        String region = requiredEnvironment("SWIFTDROP_STORAGE_REGION");
        String accessKey = requiredEnvironment("SWIFTDROP_STORAGE_ACCESS_KEY");
        String secretKey = requiredEnvironment("SWIFTDROP_STORAGE_SECRET_KEY");

        assertThat(endpoint.getScheme()).isEqualTo("http");
        assertThat(endpoint.getHost()).isIn("127.0.0.1", "localhost");
        assertThat(bucket).isEqualTo(APPROVED_BUCKET);

        ProofStorageProperties properties = new ProofStorageProperties();
        properties.setBucket(bucket);
        byte[] expected = ("swiftdrop-issue-28-minio-it-" + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
        StoredProofObject stored = null;

        try (S3Client client = realClient(endpoint, region, accessKey, secretKey)) {
            client.headBucket(request -> request.bucket(bucket));
            S3ProofObjectStorage storage = new S3ProofObjectStorage(client, properties);

            assertAnonymousRequestDenied(endpoint.resolve("/" + bucket + "?list-type=2"));

            try {
                stored = storage.store(new ProofObjectUpload(
                        new ByteArrayInputStream(expected), expected.length, "application/octet-stream"));

                assertThat(stored.reference().value())
                        .matches("proofs/v1/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                        .doesNotContain("http://", "https://", bucket);

                try (RetrievedProofObject retrieved = storage.retrieve(stored.reference())) {
                    assertThat(retrieved.content().readAllBytes()).containsExactly(expected);
                    assertThat(retrieved.contentLength()).isEqualTo(expected.length);
                    assertThat(retrieved.contentType()).contains("application/octet-stream");
                }

                var providerMetadata = client.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(stored.reference().value())
                        .build());
                assertThat(providerMetadata.contentLength()).isEqualTo(expected.length);
                assertThat(providerMetadata.contentType()).isEqualTo("application/octet-stream");

                assertAnonymousRequestDenied(endpoint.resolve("/" + bucket + "/" + stored.reference().value()));

                PutObjectRequest collisionRequest = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(stored.reference().value())
                        .contentLength(1L)
                        .contentType("application/octet-stream")
                        .ifNoneMatch("*")
                        .build();
                assertThatThrownBy(() -> client.putObject(collisionRequest, RequestBody.fromBytes(new byte[]{99})))
                        .isInstanceOfSatisfying(S3Exception.class,
                                exception -> assertThat(exception.statusCode()).isEqualTo(412));

                try (RetrievedProofObject retrieved = storage.retrieve(stored.reference())) {
                    assertThat(retrieved.content().readAllBytes()).containsExactly(expected);
                }

                ProofObjectReference missing = new ProofObjectReference("proofs/v1/" + UUID.randomUUID());
                assertThatThrownBy(() -> storage.retrieve(missing))
                        .isInstanceOf(ProofObjectNotFoundException.class)
                        .hasMessage("Private proof object was not found")
                        .hasMessageNotContaining(bucket)
                        .hasMessageNotContaining(missing.value());
            } finally {
                if (stored != null) {
                    String createdKey = stored.reference().value();
                    new ProofObjectReference(createdKey);
                    client.deleteObject(request -> request.bucket(bucket).key(createdKey));
                    assertThatThrownBy(() -> client.headObject(HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(createdKey)
                            .build()))
                            .isInstanceOfSatisfying(S3Exception.class,
                                    exception -> assertThat(exception.statusCode()).isEqualTo(404));
                }
            }
        }
    }

    @Test
    void unsafeReferencesAreRejectedBeforeAnyProviderRequestCanBeBuilt() {
        List<String> unsafeReferences = List.of(
                "../proof",
                "proofs/v1/../../secret",
                "proofs\\v1\\123e4567-e89b-42d3-a456-426614174000",
                "https://127.0.0.1:9000/swiftdrop-pod/proof",
                "proofs/v1/not-a-uuid");

        assertThat(unsafeReferences).allSatisfy(value ->
                assertThatThrownBy(() -> new ProofObjectReference(value))
                        .isInstanceOf(InvalidProofObjectException.class)
                        .hasMessage("Invalid proof object reference")
                        .hasMessageNotContaining(value));
    }

    private static S3Client realClient(URI endpoint, String region, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static void assertAnonymousRequestDenied(URI uri) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required real MinIO integration setting: " + name);
        }
        return value;
    }
}
