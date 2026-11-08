package com.swiftdrop.security.grpc;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Builds internal gRPC servers and channels. With {@code SWIFTDROP_GRPC_TLS_ENABLED=true} every hop uses TLS,
 * so service tokens and delegated actor tokens never cross the network in clear text:
 * <ul>
 *   <li>servers present {@code SWIFTDROP_GRPC_TLS_CERT} / {@code SWIFTDROP_GRPC_TLS_KEY} (PEM, PKCS#8 key);</li>
 *   <li>clients trust only {@code SWIFTDROP_GRPC_TLS_CA}.</li>
 * </ul>
 * TLS stays off by default so unit tests and single-process development keep working.
 */
public final class GrpcTransport {

    private GrpcTransport() {
    }

    public static boolean tlsEnabled() {
        return Boolean.parseBoolean(setting("SWIFTDROP_GRPC_TLS_ENABLED", "false"));
    }

    public static ManagedChannelBuilder<?> channel(String host, int port) {
        return Grpc.newChannelBuilderForAddress(host, port, channelCredentials());
    }

    public static ManagedChannelBuilder<?> channel(String target) {
        return Grpc.newChannelBuilder(target, channelCredentials());
    }

    public static ServerBuilder<?> server(int port) {
        if (!tlsEnabled()) {
            return Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
        }
        try {
            return Grpc.newServerBuilderForPort(port, TlsServerCredentials.create(
                    requiredFile("SWIFTDROP_GRPC_TLS_CERT"), requiredFile("SWIFTDROP_GRPC_TLS_KEY")));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load gRPC TLS certificate or key", ex);
        }
    }

    private static io.grpc.ChannelCredentials channelCredentials() {
        if (!tlsEnabled()) {
            return InsecureChannelCredentials.create();
        }
        try {
            return TlsChannelCredentials.newBuilder().trustManager(requiredFile("SWIFTDROP_GRPC_TLS_CA")).build();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load gRPC TLS trust certificate", ex);
        }
    }

    private static File requiredFile(String name) {
        String path = setting(name, "");
        File file = new File(path);
        if (path.isBlank() || !file.canRead()) {
            throw new IllegalStateException(name + " must point to a readable PEM file when gRPC TLS is enabled");
        }
        return file;
    }

    private static String setting(String name, String fallback) {
        String fromProperty = System.getProperty(name);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = System.getenv(name);
        return fromEnv == null || fromEnv.isBlank() ? fallback : fromEnv.trim();
    }
}
