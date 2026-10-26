package com.swiftdrop.graphql.client;

import com.swiftdrop.security.service.ServiceTokenProvider;
import io.grpc.ManagedChannel;

/**
 * Test helper exposing the package-private in-process test seam of AuthenticatedReadClients.
 */
public final class TestClients {
    private TestClients() {}

    public static AuthenticatedReadClients inProcess(ManagedChannel channel, ServiceTokenProvider tokens) {
        return new AuthenticatedReadClients(channel, tokens);
    }
}
