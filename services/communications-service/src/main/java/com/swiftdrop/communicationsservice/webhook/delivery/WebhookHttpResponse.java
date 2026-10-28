package com.swiftdrop.communicationsservice.webhook.delivery;

import java.util.List;
import java.util.Map;

/**
 * Value object representing an HTTP response received from a webhook endpoint.
 */
public record WebhookHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        int responseTimeMs,
        String responseBodyExcerpt
) {
    public WebhookHttpResponse(int statusCode, Map<String, List<String>> headers, int responseTimeMs) {
        this(statusCode, headers, responseTimeMs, null);
    }

    public String getFirstHeader(String headerName) {
        if (headers == null || headerName == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (headerName.equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                return (values != null && !values.isEmpty()) ? values.getFirst() : null;
            }
        }
        return null;
    }
}
