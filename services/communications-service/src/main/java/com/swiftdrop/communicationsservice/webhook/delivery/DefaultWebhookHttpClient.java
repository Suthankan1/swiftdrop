package com.swiftdrop.communicationsservice.webhook.delivery;

import com.swiftdrop.communicationsservice.webhook.validation.WebhookDestinationValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Production implementation of WebhookHttpClient utilizing JDK java.net.http.HttpClient.
 * Enforces:
 * 1. Pre-dispatch HTTPS destination and SSRF validation via WebhookDestinationValidator.
 * 2. Mandatory TLS 1.2+ HTTPS transport with full certificate and hostname verification.
 * 3. Never follows redirects (Redirect.NEVER) to prevent SSRF bypass and HTTPS downgrade.
 * 4. Signs canonical payload and attaches X-SwiftDrop-Signature header.
 * 5. Enforces connection and request timeouts without worker stalling.
 * 6. Accurate latency measurement.
 */
public class DefaultWebhookHttpClient implements WebhookHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookHttpClient.class);

    private final HttpClient httpClient;
    private final WebhookDestinationValidator destinationValidator;
    private final WebhookDispatchProperties properties;

    public DefaultWebhookHttpClient(
            HttpClient httpClient,
            WebhookDestinationValidator destinationValidator,
            WebhookDispatchProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.destinationValidator = Objects.requireNonNull(destinationValidator, "destinationValidator cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    @Override
    public WebhookHttpResponse send(WebhookHttpRequest request) throws Exception {
        Objects.requireNonNull(request, "request cannot be null");

        // 1. Enforce HTTPS and outbound destination security policy (SSRF defense)
        String validatedUrl = destinationValidator.validate(request.url());

        // 2. Resolve request timeout
        Duration timeout = request.timeout() != null
                ? request.timeout()
                : Duration.ofMillis(properties.getRequestTimeoutMs());

        // 3. Build HTTP request
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(validatedUrl))
                .header("Content-Type", "application/json; charset=utf-8")
                .header(WebhookSigner.SIGNATURE_HEADER_NAME, request.signatureHeader())
                .header("User-Agent", "SwiftDrop-Webhook/1.0")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(request.payloadJson(), StandardCharsets.UTF_8));

        if (request.correlationId() != null && !request.correlationId().isBlank()) {
            builder.header("x-swiftdrop-correlation-id", request.correlationId());
        }

        HttpRequest httpRequest = builder.build();

        log.debug("Dispatching signed webhook POST [url={}, timeout={}ms]", validatedUrl, timeout.toMillis());

        // 4. Measure execution latency and send request
        long startTime = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            int durationMs = (int) (System.currentTimeMillis() - startTime);

            log.debug("Webhook POST completed [url={}, statusCode={}, duration={}ms]",
                    validatedUrl, response.statusCode(), durationMs);

            return new WebhookHttpResponse(
                    response.statusCode(),
                    response.headers().map(),
                    durationMs
            );

        } catch (Exception ex) {
            int durationMs = (int) (System.currentTimeMillis() - startTime);
            log.warn("Webhook POST failed [url={}, duration={}ms, error={}]",
                    validatedUrl, durationMs, ex.getMessage());
            throw ex;
        }
    }
}
