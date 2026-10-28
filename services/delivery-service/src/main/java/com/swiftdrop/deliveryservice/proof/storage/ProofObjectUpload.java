package com.swiftdrop.deliveryservice.proof.storage;

import com.swiftdrop.deliveryservice.proof.storage.exception.InvalidProofObjectException;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.io.InputStream;

/**
 * One bounded storage request. The application layer owns evidence-policy and upload-size checks.
 * The caller retains responsibility for closing the input stream.
 */
public record ProofObjectUpload(InputStream content, long contentLength, String contentType) {

    public ProofObjectUpload {
        if (content == null) {
            throw new InvalidProofObjectException("Proof content is required");
        }
        if (contentLength <= 0) {
            throw new InvalidProofObjectException("Proof content must not be empty");
        }
        contentType = normalizeContentType(contentType);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        if (contentType.length() > 255 || contentType.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new InvalidProofObjectException("Invalid proof content type");
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType.trim());
            if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
                throw new InvalidProofObjectException("Invalid proof content type");
            }
            return mediaType.toString();
        } catch (InvalidMediaTypeException exception) {
            throw new InvalidProofObjectException("Invalid proof content type", exception);
        }
    }
}
