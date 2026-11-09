package com.swiftdrop.assistant;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Minimal client for the Gemini generateContent REST API with function calling.
 * The API key travels only in the x-goog-api-key header; it is never put in a URL, a log line or an error.
 */
@Component
public class GeminiClient {

    /** Failure talking to Gemini; the message is safe to show (no key, no provider body). */
    public static class GeminiException extends RuntimeException {
        private final boolean rateLimited;

        public GeminiException(String message, boolean rateLimited) {
            super(message);
            this.rateLimited = rateLimited;
        }

        public boolean rateLimited() {
            return rateLimited;
        }
    }

    private final AssistantProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GeminiClient(AssistantProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Sends the conversation and returns the first candidate's content node ({role, parts}). */
    public JsonNode generate(String systemInstruction, List<JsonNode> contents, ArrayNode functionDeclarations) {
        if (!properties.enabled()) {
            throw new GeminiException("The assistant is not configured.", false);
        }
        ObjectNode body = mapper.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
        ArrayNode contentArray = body.putArray("contents");
        contents.forEach(contentArray::add);
        body.putArray("tools").addObject().set("functionDeclarations", functionDeclarations);
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("temperature", 0.2);
        generation.put("maxOutputTokens", 1024);

        URI uri = URI.create(properties.geminiBaseUrl() + "/v1beta/models/" + properties.geminiModel() + ":generateContent");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", properties.geminiApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GeminiException("The AI provider could not be reached.", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiException("The AI request was interrupted.", false);
        }
        if (response.statusCode() == 429) {
            throw new GeminiException("The AI provider is busy. Please try again shortly.", true);
        }
        if (response.statusCode() != 200) {
            throw new GeminiException("The AI provider returned an error (HTTP " + response.statusCode() + ").", false);
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode content = root.path("candidates").path(0).path("content");
        if (!content.isObject() || !content.path("parts").isArray()) {
            String reason = root.path("promptFeedback").path("blockReason").asText("");
            throw new GeminiException(reason.isEmpty()
                    ? "The AI provider returned no answer."
                    : "The AI provider declined to answer this message.", false);
        }
        return content;
    }
}
