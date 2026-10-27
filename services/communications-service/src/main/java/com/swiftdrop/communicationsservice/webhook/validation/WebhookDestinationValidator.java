package com.swiftdrop.communicationsservice.webhook.validation;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Server-side destination URL validator enforcing DEC-COM-01 and defense against SSRF.
 * Invariants:
 * 1. Protocol must be HTTPS (TLS 1.2+).
 * 2. Embedded credentials (userinfo) are prohibited.
 * 3. Localhost, loopback (127.0.0.0/8, ::1, 0.0.0.0), and RFC 1918 private IPv4 are rejected.
 * 4. Link-local (169.254.0.0/16, fe80::/10) and cloud instance metadata (169.254.169.254, fd00:ec2::254) are rejected.
 * 5. IPv6 unique-local (fc00::/7) and IPv4-mapped IPv6 pointing to private/loopback are rejected.
 * 6. Internal non-routable suffixes (.local, .internal, .localhost) are rejected.
 * 7. Hostname is verified through an injected DNS resolver, failing closed on resolution error.
 * 8. All resolved A/AAAA records are validated (mixed safe/unsafe answers fail closed).
 * 9. Returns ValidatedWebhookDestination with pinned IP address to eliminate DNS rebinding TOCTOU vulnerabilities.
 */
@Component
public class WebhookDestinationValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookDestinationValidator.class);

    public static final int MAX_URL_LENGTH = 1000;
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\r\n\t\0]");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final DnsLookupResolver dnsLookupResolver;

    public WebhookDestinationValidator(DnsLookupResolver dnsLookupResolver) {
        this.dnsLookupResolver = Objects.requireNonNull(dnsLookupResolver, "DnsLookupResolver is required");
    }

    /**
     * Validates destination URL, returning normalized URL string.
     * Backwards-compatible convenience method.
     *
     * @param urlString destination URL string
     * @return normalized URL string
     */
    public String validate(String urlString) {
        return validateAndResolve(urlString).normalizedUrl();
    }

    /**
     * Validates destination URL and resolves network addresses under SSRF rules.
     * Pinning metadata is returned to ensure the outbound transport connects ONLY
     * to a pre-validated IP address, eliminating DNS rebinding (TOCTOU).
     *
     * @param urlString destination URL string
     * @return validated destination with pinned IP address
     */
    public ValidatedWebhookDestination validateAndResolve(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new InvalidWebhookDestinationException("Destination URL is required.");
        }

        String trimmed = urlString.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidWebhookDestinationException(
                    "Destination URL exceeds maximum length of " + MAX_URL_LENGTH + " characters.");
        }

        if (CONTROL_CHARS.matcher(trimmed).find()) {
            throw new InvalidWebhookDestinationException("Destination URL contains forbidden control characters.");
        }

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new InvalidWebhookDestinationException(
                    "Destination must be a well-formed URL (e.g., https://api.example.com/webhooks).");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new InvalidWebhookDestinationException(
                    "Only secure HTTPS destinations are permitted (TLS 1.2+). Plain HTTP is rejected.");
        }

        if (uri.getUserInfo() != null || uri.getRawUserInfo() != null || trimmed.matches("(?i)^https://[^/]+@.*")) {
            throw new InvalidWebhookDestinationException("Destination URL must not contain embedded user credentials.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookDestinationException("Destination URL must contain a valid host.");
        }

        int port = uri.getPort() != -1 ? uri.getPort() : 443;
        if (port < 1 || port > 65535) {
            throw new InvalidWebhookDestinationException("Destination URL contains an invalid port number.");
        }

        String cleanHost = host.trim().toLowerCase(Locale.ROOT);
        if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
            cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
        }

        // Domain name safety checks
        if (cleanHost.equals("localhost") ||
                cleanHost.endsWith(".localhost") ||
                cleanHost.endsWith(".local") ||
                cleanHost.endsWith(".internal")) {
            throw new InvalidWebhookDestinationException(
                    "Localhost and private internal destinations are rejected for security (SSRF prevention).");
        }

        // IP literal safety check
        checkIpLiteralSafety(cleanHost);

        // DNS resolution check: resolve and validate ALL returned addresses
        InetAddress[] addresses = resolveAndCheckAddresses(cleanHost);

        InetAddress pinnedAddress = addresses[0];
        return new ValidatedWebhookDestination(
                trimmed,
                uri,
                cleanHost,
                port,
                pinnedAddress,
                List.of(addresses)
        );
    }

    private void checkIpLiteralSafety(String host) {
        var ipv4Matcher = IPV4_PATTERN.matcher(host);
        if (ipv4Matcher.matches()) {
            int o1 = Integer.parseInt(ipv4Matcher.group(1));
            int o2 = Integer.parseInt(ipv4Matcher.group(2));
            int o3 = Integer.parseInt(ipv4Matcher.group(3));
            int o4 = Integer.parseInt(ipv4Matcher.group(4));

            if (o1 > 255 || o2 > 255 || o3 > 255 || o4 > 255) {
                throw new InvalidWebhookDestinationException("Destination contains invalid IPv4 octets.");
            }

            // Loopback (127.0.0.0/8, 0.0.0.0)
            if (o1 == 127 || (o1 == 0 && o2 == 0 && o3 == 0 && o4 == 0)) {
                throw new InvalidWebhookDestinationException(
                        "Localhost and loopback destinations are rejected for security (SSRF prevention).");
            }

            // RFC 1918 Private IPv4: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
            if (o1 == 10 || (o1 == 172 && o2 >= 16 && o2 <= 31) || (o1 == 192 && o2 == 168)) {
                throw new InvalidWebhookDestinationException(
                        "Private RFC 1918 IP addresses are rejected for security (SSRF prevention).");
            }

            // Link-local and cloud metadata: 169.254.0.0/16
            if (o1 == 169 && o2 == 254) {
                throw new InvalidWebhookDestinationException(
                        "Link-local and cloud instance metadata IP addresses are rejected for security (SSRF prevention).");
            }

            // Shared address space (RFC 6598 carrier-grade NAT) & cloud metadata (e.g., 100.100.100.200)
            if (o1 == 100 && (o2 >= 64 && o2 <= 127)) {
                throw new InvalidWebhookDestinationException(
                        "Carrier-grade shared address space is rejected for security (SSRF prevention).");
            }
        }

        // IPv6 literal checks
        if (host.contains(":")) {
            if (host.equals("::1") || host.equals("0:0:0:0:0:0:0:1") || host.equals("::")) {
                throw new InvalidWebhookDestinationException(
                        "IPv6 loopback destination is rejected for security (SSRF prevention).");
            }
            if (host.startsWith("fe80:") || host.startsWith("fe80::")) {
                throw new InvalidWebhookDestinationException(
                        "IPv6 link-local addresses are rejected for security (SSRF prevention).");
            }
            if (host.startsWith("fc00:") || host.startsWith("fd00:") || host.startsWith("fc") || host.startsWith("fd")) {
                throw new InvalidWebhookDestinationException(
                        "IPv6 unique-local addresses are rejected for security (SSRF prevention).");
            }
        }
    }

    private InetAddress[] resolveAndCheckAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = dnsLookupResolver.resolve(host);
        } catch (UnknownHostException ex) {
            log.warn("DNS resolution failed for webhook destination [host={}]", host);
            throw new InvalidWebhookDestinationException(
                    "Destination hostname could not be resolved or is invalid.");
        } catch (Exception ex) {
            log.warn("Unexpected DNS resolution error [host={}, error={}]", host, ex.getMessage());
            throw new InvalidWebhookDestinationException(
                    "Destination hostname resolution failed.");
        }

        if (addresses == null || addresses.length == 0) {
            throw new InvalidWebhookDestinationException(
                    "Destination hostname resolved to no network addresses.");
        }

        for (InetAddress addr : addresses) {
            checkResolvedAddress(addr);
        }

        return addresses;
    }

    private void checkResolvedAddress(InetAddress addr) {
        String ip = addr.getHostAddress();

        // Cloud metadata addresses (IPv4 & IPv6)
        if ("169.254.169.254".equals(ip) || "169.254.170.2".equals(ip) || "100.100.100.200".equals(ip)) {
            throw new InvalidWebhookDestinationException(
                    "Destination resolves to cloud instance metadata address.");
        }

        if (addr.isLoopbackAddress()) {
            throw new InvalidWebhookDestinationException(
                    "Destination resolves to loopback network address.");
        }
        if (addr.isSiteLocalAddress()) {
            throw new InvalidWebhookDestinationException(
                    "Destination resolves to private RFC 1918 network address.");
        }
        if (addr.isLinkLocalAddress() || ip.startsWith("169.254.")) {
            throw new InvalidWebhookDestinationException(
                    "Destination resolves to link-local network address.");
        }
        if (addr.isAnyLocalAddress()) {
            throw new InvalidWebhookDestinationException(
                    "Destination resolves to wildcard/unspecified network address.");
        }
        if (addr.isMulticastAddress()) {
            throw new InvalidWebhookDestinationException(
                    "Destination resolves to multicast network address.");
        }

        // IPv6 unique-local (fc00::/7) or IPv4-mapped IPv6
        if (addr instanceof Inet6Address) {
            byte[] bytes = addr.getAddress();

            // fc00::/7 (unique local, covers fc00::/8 and fd00::/8, e.g. AWS IMDSv2 fd00:ec2::254)
            if ((bytes[0] & 0xfe) == 0xfc) {
                throw new InvalidWebhookDestinationException(
                        "Destination resolves to IPv6 unique-local address.");
            }

            // IPv4-mapped IPv6 (::ffff:x.x.x.x)
            if (bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0
                    && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0
                    && bytes[8] == 0 && bytes[9] == 0 && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                try {
                    byte[] ipv4Bytes = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                    InetAddress mappedIpv4 = InetAddress.getByAddress(ipv4Bytes);
                    checkResolvedAddress(mappedIpv4);
                } catch (UnknownHostException ex) {
                    throw new InvalidWebhookDestinationException(
                            "Destination resolves to invalid IPv4-mapped IPv6 address.");
                }
            }
        }

        // IPv4 carrier-grade NAT check on resolved IPv4
        if (addr instanceof Inet4Address) {
            byte[] bytes = addr.getAddress();
            int o1 = bytes[0] & 0xff;
            int o2 = bytes[1] & 0xff;
            if (o1 == 100 && (o2 >= 64 && o2 <= 127)) {
                throw new InvalidWebhookDestinationException(
                        "Destination resolves to carrier-grade shared address space.");
            }
        }
    }
}
