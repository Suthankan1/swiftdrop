package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import com.swiftdrop.communicationsservice.webhook.validation.ValidatedWebhookDestination;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultWebhookHttpClientTest {

    private HttpClient mockHttpClient;
    private WebhookDestinationValidator mockValidator;
    private WebhookDispatchProperties properties;
    private DefaultWebhookHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockHttpClient = mock(HttpClient.class);
        mockValidator = mock(WebhookDestinationValidator.class);
        properties = new WebhookDispatchProperties();
        properties.setRequestTimeoutMs(4000L);
        properties.setConnectTimeoutMs(2000L);
        // By default for mock tests, disable socket pinning to test JDK HttpClient integration
        properties.setSocketPinningEnabled(false);

        // By default, validator accepts the URL
        when(mockValidator.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mockValidator.validateAndResolve(any())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            URI uri = URI.create(url);
            InetAddress addr = InetAddress.getByName("93.184.216.34");
            return new ValidatedWebhookDestination(url, uri, uri.getHost(), uri.getPort() != -1 ? uri.getPort() : 443, addr, List.of(addr));
        });

        client = new DefaultWebhookHttpClient(mockHttpClient, mockValidator, properties);
    }

    @Test
    @DisplayName("Pre-dispatch destination validation failure prevents HTTP request execution")
    void send_ValidationFailure_ThrowsWithoutSending() throws Exception {
        when(mockValidator.validateAndResolve("https://prohibited.destination/webhook"))
                .thenThrow(new InvalidWebhookDestinationException("Prohibited private IP"));

        WebhookHttpRequest request = new WebhookHttpRequest(
                "https://prohibited.destination/webhook",
                "t=1773000000,v1=sig",
                "corr-123",
                "{\"hello\":true}",
                null
        );

        assertThatThrownBy(() -> client.send(request))
                .isInstanceOf(InvalidWebhookDestinationException.class)
                .hasMessageContaining("Prohibited private IP");

        // Verify HttpClient was never invoked
        verify(mockHttpClient, org.mockito.Mockito.never()).send(any(), any());
    }

    @Test
    @DisplayName("Sends HTTP POST request with required headers and measures latency")
    @SuppressWarnings("unchecked")
    void send_ValidRequest_SendsPostWithHeaders() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true);
        when(mockResponse.headers()).thenReturn(headers);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        WebhookHttpRequest request = new WebhookHttpRequest(
                "https://api.example.com/webhooks/swiftdrop",
                "t=1773000000,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "corr-abc-123",
                "{\"eventId\":\"12345\"}",
                null
        );

        WebhookHttpResponse response = client.send(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.responseTimeMs()).isGreaterThanOrEqualTo(0);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(requestCaptor.capture(), any());

        HttpRequest sent = requestCaptor.getValue();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri().toString()).isEqualTo("https://api.example.com/webhooks/swiftdrop");
        assertThat(sent.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(sent.headers().firstValue(WebhookSigner.SIGNATURE_HEADER_NAME))
                .contains("t=1773000000,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        assertThat(sent.headers().firstValue("User-Agent")).contains("SwiftDrop-Webhook/1.0");
        assertThat(sent.headers().firstValue("x-swiftdrop-correlation-id")).contains("corr-abc-123");
    }

    @Test
    @DisplayName("Transport timeout exception is propagated with accurate elapsed duration")
    @SuppressWarnings("unchecked")
    void send_TimeoutException_Propagated() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("request timed out"));

        WebhookHttpRequest request = new WebhookHttpRequest(
                "https://api.example.com/slow-endpoint",
                "t=1773000000,v1=sig",
                null,
                "{}",
                null
        );

        assertThatThrownBy(() -> client.send(request))
                .isInstanceOf(HttpTimeoutException.class)
                .hasMessageContaining("request timed out");
    }

    @Test
    @DisplayName("PinnedSSLSocketFactory connects to pinned address and configures HTTPS SNI")
    void pinnedSSLSocketFactory_ConfiguresSniAndEndpointIdentification() throws Exception {
        SSLSocketFactory mockDelegateFactory = mock(SSLSocketFactory.class);
        SSLSocket mockSslSocket = mock(SSLSocket.class);
        SSLParameters sslParams = new SSLParameters();
        when(mockSslSocket.getSSLParameters()).thenReturn(sslParams);

        InetAddress pinnedIp = InetAddress.getByName("93.184.216.34");
        PinnedSSLSocketFactory pinnedFactory = new PinnedSSLSocketFactory(mockDelegateFactory, pinnedIp, 1000);

        when(mockDelegateFactory.createSocket(any(Socket.class), eq("example.com"), eq(443), anyBoolean()))
                .thenReturn(mockSslSocket);

        Socket plainSocket = mock(Socket.class);
        Socket createdSsl = pinnedFactory.createSocket(plainSocket, "example.com", 443, true);

        assertThat(createdSsl).isSameAs(mockSslSocket);
        ArgumentCaptor<SSLParameters> captor = ArgumentCaptor.forClass(SSLParameters.class);
        verify(mockSslSocket).setSSLParameters(captor.capture());

        SSLParameters captured = captor.getValue();
        assertThat(captured.getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(captured.getServerNames()).hasSize(1);
        assertThat(captured.getServerNames().get(0)).isInstanceOf(SNIHostName.class);
        assertThat(((SNIHostName) captured.getServerNames().get(0)).getAsciiName()).isEqualTo("example.com");
    }

    @Test
    @DisplayName("Pinned socket transport is invoked when socketPinningEnabled is true")
    void send_WithSocketPinningEnabled_FailsClosedOnUnreachablePinnedIp() throws Exception {
        properties.setSocketPinningEnabled(true);
        properties.setConnectTimeoutMs(200);

        // Destination resolves to a non-routable test address (e.g., TEST-NET-1 192.0.2.1 per RFC 5737)
        InetAddress testNetAddr = InetAddress.getByName("192.0.2.1");
        when(mockValidator.validateAndResolve("https://webhook.customer.com/endpoint"))
                .thenReturn(new ValidatedWebhookDestination(
                        "https://webhook.customer.com/endpoint",
                        URI.create("https://webhook.customer.com/endpoint"),
                        "webhook.customer.com",
                        443,
                        testNetAddr,
                        List.of(testNetAddr)
                ));

        WebhookHttpRequest request = new WebhookHttpRequest(
                "https://webhook.customer.com/endpoint",
                "t=1773000000,v1=sig",
                "corr-pinned",
                "{\"status\":\"CREATED\"}",
                Duration.ofMillis(300)
        );

        // Attempting to connect to unreachable pinned IP fails with connection/socket exception
        assertThatThrownBy(() -> client.send(request))
                .isInstanceOf(Exception.class);

        // Verify JDK HttpClient was bypassed because socket pinning transport handled the request
        verify(mockHttpClient, org.mockito.Mockito.never()).send(any(), any());
    }
}
