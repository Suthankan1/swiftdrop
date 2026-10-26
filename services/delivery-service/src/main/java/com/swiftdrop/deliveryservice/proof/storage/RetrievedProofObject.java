package com.swiftdrop.deliveryservice.proof.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Streaming private-object response. Callers must close this value after consuming the content.
 */
public final class RetrievedProofObject implements AutoCloseable {

    private final InputStream content;
    private final long contentLength;
    private final String contentType;

    public RetrievedProofObject(InputStream content, long contentLength, String contentType) {
        this.content = Objects.requireNonNull(content, "content");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
        }
        this.contentLength = contentLength;
        this.contentType = contentType;
    }

    public InputStream content() {
        return content;
    }

    public long contentLength() {
        return contentLength;
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    @Override
    public void close() throws IOException {
        content.close();
    }
}
