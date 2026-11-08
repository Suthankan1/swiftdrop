package com.swiftdrop.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Assistant settings. The Gemini key is read from the environment only (GEMINI_API_KEY) and is never
 * logged or returned; an empty key keeps the assistant disabled.
 */
@ConfigurationProperties("swiftdrop.assistant")
public record AssistantProperties(
        String geminiApiKey,
        String geminiModel,
        String geminiBaseUrl,
        String pricingUrl,
        String deliveryUrl,
        int requestTimeoutSeconds,
        int maxToolRounds) {

    public AssistantProperties {
        geminiApiKey = geminiApiKey == null ? "" : geminiApiKey.trim();
        geminiModel = blankTo(geminiModel, "gemini-2.5-flash");
        geminiBaseUrl = stripSlash(blankTo(geminiBaseUrl, "https://generativelanguage.googleapis.com"));
        pricingUrl = stripSlash(blankTo(pricingUrl, "http://localhost:8082"));
        deliveryUrl = stripSlash(blankTo(deliveryUrl, "http://localhost:8083"));
        requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 20 : requestTimeoutSeconds;
        maxToolRounds = maxToolRounds <= 0 ? 4 : Math.min(maxToolRounds, 8);
        if (!geminiModel.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("GEMINI_MODEL must be a plain model name");
        }
    }

    public boolean enabled() {
        return !geminiApiKey.isEmpty();
    }

    @Override
    public String toString() { // never print the key
        return "AssistantProperties[model=" + geminiModel + ", enabled=" + enabled() + "]";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
