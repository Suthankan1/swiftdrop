package com.swiftdrop.communicationsservice.webhook.validation;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Validated destination metadata resulting from strict SSRF and DNS security analysis.
 * Contains the normalized URI, target host, port, all resolved IP addresses,
 * and the specific pinned IP address for outbound TCP socket connection.
 */
public record ValidatedWebhookDestination(
        String normalizedUrl,
        URI uri,
        String host,
        int port,
        InetAddress pinnedAddress,
        List<InetAddress> allAddresses
) {
    public ValidatedWebhookDestination {
        Objects.requireNonNull(normalizedUrl, "normalizedUrl cannot be null");
        Objects.requireNonNull(uri, "uri cannot be null");
        Objects.requireNonNull(host, "host cannot be null");
        Objects.requireNonNull(pinnedAddress, "pinnedAddress cannot be null");
        Objects.requireNonNull(allAddresses, "allAddresses cannot be null");
        if (allAddresses.isEmpty()) {
            throw new IllegalArgumentException("allAddresses must contain at least one resolved address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
