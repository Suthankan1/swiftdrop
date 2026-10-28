package com.swiftdrop.deliveryservice.proof.storage;

import com.swiftdrop.deliveryservice.proof.storage.config.ProofStorageProperties;
import com.swiftdrop.deliveryservice.proof.storage.exception.InvalidProofObjectException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProofStorageSecurityTest {

    @Test
    void acceptsOnlyCanonicalApplicationOwnedReferences() {
        ProofObjectReference reference = new ProofObjectReference(
                "proofs/v1/123e4567-e89b-42d3-a456-426614174000");

        assertThat(reference.value()).isEqualTo("proofs/v1/123e4567-e89b-42d3-a456-426614174000");

        List<String> unsafeReferences = Arrays.asList(
                null,
                "",
                "../proof",
                "proofs/v1/../../secret",
                "proofs\\v1\\123e4567-e89b-42d3-a456-426614174000",
                "/proofs/v1/123e4567-e89b-42d3-a456-426614174000",
                "https://storage.example/proof",
                "proofs/v1/123e4567-e89b-12d3-a456-426614174000",
                "proofs/v1/123E4567-E89B-42D3-A456-426614174000",
                "proofs/v1/123e4567-e89b-42d3-a456-426614174000/extra");

        unsafeReferences.forEach(value -> {
            var assertion = assertThatThrownBy(() -> new ProofObjectReference(value))
                    .isInstanceOf(InvalidProofObjectException.class)
                    .hasMessage("Invalid proof object reference");
            if (value != null && !value.isEmpty()) {
                assertion.hasMessageNotContaining(value);
            }
        });
    }

    @Test
    void uploadContractCannotAcceptOrUseClientFilenames() {
        Method[] constructorsAndMethods = ProofObjectUpload.class.getDeclaredMethods();

        assertThat(Arrays.stream(constructorsAndMethods).map(Method::getName))
                .doesNotContain("filename", "fileName", "path", "key");

        ProofObjectUpload upload = new ProofObjectUpload(
                new ByteArrayInputStream(new byte[]{1}), 1, "image/example");
        assertThat(upload.contentLength()).isOne();
    }

    @Test
    void rejectsEmptyMissingAndHeaderUnsafeContentWithoutInventingMediaPolicy() {
        assertThatThrownBy(() -> new ProofObjectUpload(null, 1, "image/png"))
                .isInstanceOf(InvalidProofObjectException.class)
                .hasMessage("Proof content is required");
        assertThatThrownBy(() -> new ProofObjectUpload(new ByteArrayInputStream(new byte[0]), 0, "image/png"))
                .isInstanceOf(InvalidProofObjectException.class)
                .hasMessage("Proof content must not be empty");
        assertThatThrownBy(() -> new ProofObjectUpload(
                new ByteArrayInputStream(new byte[]{1}), 1, "image/png\r\nX-Injected: true"))
                .isInstanceOf(InvalidProofObjectException.class)
                .hasMessage("Invalid proof content type");
        assertThatThrownBy(() -> new ProofObjectUpload(
                new ByteArrayInputStream(new byte[]{1}), 1, "not-a-media-type"))
                .isInstanceOf(InvalidProofObjectException.class)
                .hasMessage("Invalid proof content type");
        assertThatThrownBy(() -> new ProofObjectUpload(
                new ByteArrayInputStream(new byte[]{1}), 1, "image/*"))
                .isInstanceOf(InvalidProofObjectException.class)
                .hasMessage("Invalid proof content type");

        ProofObjectUpload policyNeutral = new ProofObjectUpload(
                new ByteArrayInputStream(new byte[]{1}), 1, "application/x-future-approved-evidence");
        assertThat(policyNeutral.contentType()).isEqualTo("application/x-future-approved-evidence");
    }

    @Test
    void configurationStringNeverDisclosesCredentials() {
        ProofStorageProperties properties = new ProofStorageProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("http://127.0.0.1:9000"));
        properties.setBucket("private-bucket");
        properties.setRegion("us-east-1");
        properties.setAccessKey("access-value-that-must-not-appear");
        properties.setSecretKey("secret-value-that-must-not-appear");

        assertThat(properties.toString())
                .doesNotContain("access-value-that-must-not-appear")
                .doesNotContain("secret-value-that-must-not-appear")
                .doesNotContain("private-bucket")
                .doesNotContain("127.0.0.1")
                .contains("[REDACTED]");
    }
}
