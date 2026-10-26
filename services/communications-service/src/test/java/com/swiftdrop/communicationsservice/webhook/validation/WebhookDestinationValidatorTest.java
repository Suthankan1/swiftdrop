package com.swiftdrop.communicationsservice.webhook.validation;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookDestinationValidatorTest {

    private DnsLookupResolver mockDnsResolver;
    private WebhookDestinationValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        mockDnsResolver = mock(DnsLookupResolver.class);
        // Default mock resolves to a public routable IP
        when(mockDnsResolver.resolve(anyString()))
                .thenReturn(new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        validator = new WebhookDestinationValidator(mockDnsResolver);
    }

    @Test
    @DisplayName("Valid public HTTPS URL is accepted")
    void validHttpsUrl_Accepted() {
        String url = "https://api.example.com/v1/webhooks/swiftdrop";
        String validated = validator.validate(url);
        assertThat(validated).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.example.com/webhooks",
            "ftp://example.com/wh",
            "javascript:alert(1)",
            "file:///etc/passwd"
    })
    @DisplayName("Non-HTTPS scheme is rejected")
    void nonHttpsScheme_Rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Only secure HTTPS destinations are permitted");
    }

    @Test
    @DisplayName("Null or blank destination URL is rejected")
    void nullOrBlank_Rejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Destination URL is required.");

        assertThatThrownBy(() -> validator.validate("   "))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Destination URL is required.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:pass@api.example.com/webhooks",
            "https://admin:secret@webhook.site/test"
    })
    @DisplayName("Destination containing embedded user credentials is rejected")
    void userCredentials_Rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Destination URL must not contain embedded user credentials.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/webhooks",
            "https://sub.localhost/webhooks",
            "https://service.local/webhooks",
            "https://corp.internal/webhooks"
    })
    @DisplayName("Localhost and internal domain suffixes are rejected")
    void internalDomains_Rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Localhost and private internal destinations are rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/wh",
            "https://127.0.0.2:8443/wh",
            "https://0.0.0.0/wh"
    })
    @DisplayName("IPv4 loopback addresses are rejected")
    void ipv4Loopback_Rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Localhost and loopback destinations are rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://10.0.0.1/wh",
            "https://10.254.1.1/wh",
            "https://172.16.0.1/wh",
            "https://172.31.255.254/wh",
            "https://192.168.1.1/wh",
            "https://192.168.0.254/wh"
    })
    @DisplayName("RFC 1918 private IPv4 addresses are rejected")
    void rfc1918PrivateIpv4_Rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Private RFC 1918 IP addresses are rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://169.254.169.254/latest/meta-data",
            "https://169.254.1.1/wh"
    })
    @DisplayName("Link-local and cloud instance metadata addresses are rejected")
    void linkLocalAndCloudMetadata_Rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Link-local and cloud instance metadata IP addresses are rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://[::1]/wh",
            "https://[fe80::1]/wh",
            "https://[fc00::1]/wh",
            "https://[fd00::1]/wh"
    })
    @DisplayName("IPv6 loopback, link-local, and unique-local literals are rejected")
    void ipv6UnsafeLiterals_Rejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class);
    }

    @Test
    @DisplayName("Hostname resolving to loopback IP is rejected via DNS check")
    void dnsResolvingToLoopback_Rejected() throws Exception {
        when(mockDnsResolver.resolve("safe-looking-domain.com"))
                .thenReturn(new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        assertThatThrownBy(() -> validator.validate("https://safe-looking-domain.com/wh"))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("resolves to loopback");
    }

    @Test
    @DisplayName("Hostname resolving to private RFC 1918 IP is rejected via DNS check")
    void dnsResolvingToPrivateIp_Rejected() throws Exception {
        when(mockDnsResolver.resolve("internal-proxy.com"))
                .thenReturn(new InetAddress[]{InetAddress.getByName("10.0.1.5")});

        assertThatThrownBy(() -> validator.validate("https://internal-proxy.com/wh"))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("resolves to private RFC 1918");
    }

    @Test
    @DisplayName("Hostname resolving to cloud metadata IP is rejected via DNS check")
    void dnsResolvingToCloudMetadata_Rejected() throws Exception {
        when(mockDnsResolver.resolve("metadata.cloud.com"))
                .thenReturn(new InetAddress[]{InetAddress.getByName("169.254.169.254")});

        assertThatThrownBy(() -> validator.validate("https://metadata.cloud.com/wh"))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("cloud instance metadata");
    }

    @Test
    @DisplayName("Unresolvable hostname fails closed")
    void dnsResolutionFailure_FailsClosed() throws Exception {
        when(mockDnsResolver.resolve("nonexistent.unresolvable.example"))
                .thenThrow(new UnknownHostException("Host unresolvable"));

        assertThatThrownBy(() -> validator.validate("https://nonexistent.unresolvable.example/wh"))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Destination hostname could not be resolved");
    }

    @Test
    @DisplayName("Excessively long URL (> 1000 chars) is rejected")
    void excessivelyLongUrl_Rejected() {
        String longPath = "a".repeat(1005);
        String url = "https://api.example.com/" + longPath;
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("exceeds maximum length of 1000 characters");
    }

    @Test
    @DisplayName("URL with CRLF injection is rejected")
    void crlfInjection_Rejected() {
        String url = "https://api.example.com/webhook\r\nInjected-Header: value";
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("forbidden control characters");
    }
}
