package com.swiftdrop.communicationsservice.webhook.delivery;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;

/**
 * Custom SSLSocketFactory that connects the underlying TCP socket strictly to a pre-validated
 * pinned IP address, while preserving TLS SNI and standard HTTPS certificate hostname verification
 * for the target hostname.
 *
 * This eliminates DNS rebinding (TOCTOU) attacks where an initial DNS check passes but the
 * outbound connection re-resolves to an unvalidated internal/private IP.
 */
public class PinnedSSLSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;
    private final InetAddress pinnedAddress;
    private final int connectTimeoutMs;

    public PinnedSSLSocketFactory(SSLSocketFactory delegate, InetAddress pinnedAddress, int connectTimeoutMs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate SSLSocketFactory cannot be null");
        this.pinnedAddress = Objects.requireNonNull(pinnedAddress, "pinnedAddress cannot be null");
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket plainSocket = new Socket();
        plainSocket.connect(new InetSocketAddress(pinnedAddress, port), connectTimeoutMs);
        SSLSocket sslSocket = (SSLSocket) delegate.createSocket(plainSocket, host, port, true);
        configureSslSocket(sslSocket, host);
        return sslSocket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket plainSocket = new Socket();
        plainSocket.bind(new InetSocketAddress(localHost, localPort));
        plainSocket.connect(new InetSocketAddress(pinnedAddress, port), connectTimeoutMs);
        SSLSocket sslSocket = (SSLSocket) delegate.createSocket(plainSocket, host, port, true);
        configureSslSocket(sslSocket, host);
        return sslSocket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return createSocket(host.getHostName(), port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return createSocket(address.getHostName(), port, localAddress, localPort);
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        SSLSocket sslSocket = (SSLSocket) delegate.createSocket(s, host, port, autoClose);
        configureSslSocket(sslSocket, host);
        return sslSocket;
    }

    private void configureSslSocket(SSLSocket sslSocket, String host) {
        SSLParameters sslParams = sslSocket.getSSLParameters();
        sslParams.setEndpointIdentificationAlgorithm("HTTPS");
        try {
            sslParams.setServerNames(List.of(new SNIHostName(host)));
        } catch (IllegalArgumentException ignored) {
            // IP literals are not valid SNI hostnames per RFC 6066
        }
        sslSocket.setSSLParameters(sslParams);
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    public InetAddress getPinnedAddress() {
        return pinnedAddress;
    }
}
