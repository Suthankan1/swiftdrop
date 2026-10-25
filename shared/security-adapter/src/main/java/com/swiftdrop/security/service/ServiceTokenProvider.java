package com.swiftdrop.security.service;

/**
 * Interface for acquiring valid service-to-service JWTs for outgoing gRPC calls.
 */
@FunctionalInterface
public interface ServiceTokenProvider {

    String getServiceToken();

    static ServiceTokenProvider of(String staticToken) {
        return () -> staticToken;
    }
}
