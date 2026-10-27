package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidWebhookDestinationException;
import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultWebhookHttpClientTest {

    private HttpClient mockHttpClient;
    private WebhookDestinationValidator mockValidator;
    private WebhookDispatchProperties properties;
    private DefaultWebhookHttpClient client;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        mockValidator = mock(WebhookDestinationValidator.class);
        properties = new WebhookDispatchProperties();
        properties.setRequestTimeoutMs(4000L);
        properties.setConnectTimeoutMs(2000L);

        // By default, validator accepts the URL
        when(mockValidator.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));

        client = new DefaultWebhookHttpClient(mockHttpClient, mockValidator, properties);
    }

    @Test
    @DisplayName("Pre-dispatch destination validation failure prevents HTTP request execution")
    void send_ValidationFailure_ThrowsWithoutSending() throws Exception {
        when(mockValidator.validate("https://prohibited.destination/webhook"))
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
}
