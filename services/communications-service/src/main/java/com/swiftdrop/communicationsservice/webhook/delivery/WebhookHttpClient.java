package com.swiftdrop.communicationsservice.webhook.delivery;

/**
 * Interface abstraction for executing outbound signed HTTPS webhook HTTP requests.
 */
public interface WebhookHttpClient {

    /**
     * Sends a signed webhook HTTP POST request.
     *
     * @param request outbound dispatch request parameters
     * @return WebhookHttpResponse containing status code, headers, and latency
     * @throws Exception if transport, timeout, SSL, or network failure occurs
     */
    WebhookHttpResponse send(WebhookHttpRequest request) throws Exception;

    /**
     * Convenience overload sending a webhook dispatch request.
     */
    default WebhookHttpResponse send(String url, String signatureHeader, String correlationId, String payloadJson) throws Exception {
        return send(new WebhookHttpRequest(url, signatureHeader, correlationId, payloadJson, null));
    }
}
