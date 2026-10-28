package com.swiftdrop.deliveryservice.proof.storage.adapter;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Detects a stream that ends before its declared content length. */
final class ExactLengthInputStream extends FilterInputStream {

    private long remaining;

    ExactLengthInputStream(InputStream input, long expectedLength) {
        super(input);
        this.remaining = expectedLength;
    }

    @Override
    public int read() throws IOException {
        if (remaining == 0) {
            return -1;
        }
        int value = super.read();
        if (value < 0) {
            throw new EOFException("Proof stream ended before its declared length");
        }
        remaining--;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining == 0) {
            return -1;
        }
        int boundedLength = (int) Math.min(length, remaining);
        int count = super.read(buffer, offset, boundedLength);
        if (count < 0) {
            throw new EOFException("Proof stream ended before its declared length");
        }
        remaining -= count;
        return count;
    }

    @Override
    public void close() {
        // The caller owns the upload stream lifecycle.
    }
}
