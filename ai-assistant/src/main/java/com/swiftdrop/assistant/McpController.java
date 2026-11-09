package com.swiftdrop.assistant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/**
 * Model Context Protocol endpoint (Streamable HTTP transport, JSON responses) exposing the same four read-only
 * SwiftDrop tools the built-in assistant uses. External MCP clients get exactly the access the caller has:
 * public tools anonymously, "list_my_parcels" only with the caller's own bearer token. No Gemini key is needed.
 */
@RestController
public class McpController {

    static final String LATEST_PROTOCOL = "2025-06-18";
    static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final int MAX_BODY_BYTES = 16_384;

    private final AssistantTools tools;
    private final ObjectMapper mapper;

    public McpController(AssistantTools tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    /** This server does not open server-initiated SSE streams. */
    @GetMapping("/api/v1/assistant/mcp")
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST").build();
    }

    @PostMapping(value = "/api/v1/assistant/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestBody String raw, @AuthenticationPrincipal Jwt jwt) {
        if (raw.length() > MAX_BODY_BYTES) {
            return ResponseEntity.ok(error(null, -32600, "Request too large."));
        }
        JsonNode request;
        try {
            request = mapper.readTree(raw);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(error(null, -32700, "Parse error."));
        }
        if (request == null || !request.isObject() || !"2.0".equals(request.path("jsonrpc").asText(""))
                || !request.path("method").isValueNode()) {
            return ResponseEntity.ok(error(request != null ? request.get("id") : null, -32600, "Invalid JSON-RPC request."));
        }
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");
        // Notifications (no id) get 202 Accepted with no body.
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build();
        }
        String bearer = jwt == null ? null : jwt.getTokenValue();
        return ResponseEntity.ok(switch (method) {
            case "initialize" -> result(id, initialize(request.path("params")));
            case "ping" -> result(id, mapper.createObjectNode());
            case "tools/list" -> result(id, listTools(bearer != null));
            case "tools/call" -> callTool(id, request.path("params"), bearer);
            default -> error(id, -32601, "Method not found.");
        });
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode out = mapper.createObjectNode();
        out.put("protocolVersion", SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL);
        out.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = out.putObject("serverInfo");
        info.put("name", "swiftdrop-delivery-tools");
        info.put("version", "1.0.0");
        out.put("instructions", "Read-only SwiftDrop delivery tools: track a parcel, list zones, price a delivery, and "
                + "(with a user's bearer token) list that user's parcels. Amounts are in minor units (cents).");
        return out;
    }

    private ObjectNode listTools(boolean authenticated) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("tools");
        for (JsonNode declaration : tools.declarations(authenticated)) {
            ObjectNode tool = list.addObject();
            tool.put("name", declaration.path("name").asText());
            tool.put("description", declaration.path("description").asText());
            tool.set("inputSchema", toJsonSchema(declaration.path("parameters")));
            ObjectNode annotations = tool.putObject("annotations");
            annotations.put("readOnlyHint", true);
            annotations.put("openWorldHint", false);
        }
        return out;
    }

    private ObjectNode callTool(JsonNode id, JsonNode params, String bearer) {
        String name = params.path("name").asText("");
        boolean known = false;
        for (JsonNode declaration : tools.declarations(bearer != null)) {
            if (declaration.path("name").asText().equals(name)) known = true;
        }
        if (!known) {
            String safe = name.replaceAll("[^A-Za-z0-9_]", "");
            return error(id, -32602, "Unknown tool: " + safe.substring(0, Math.min(safe.length(), 40)));
        }
        JsonNode args = params.path("arguments").isObject() ? params.path("arguments") : mapper.createObjectNode();
        ObjectNode toolResult = tools.execute(name, args, bearer);
        ObjectNode out = mapper.createObjectNode();
        out.putArray("content").addObject().put("type", "text").put("text", mapper.writeValueAsString(toolResult));
        out.set("structuredContent", toolResult);
        out.put("isError", toolResult.has("error"));
        return result(id, out);
    }

    /** Gemini uses upper-case OpenAPI types; MCP expects JSON Schema. */
    private ObjectNode toJsonSchema(JsonNode parameters) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        parameters.path("properties").properties().forEach(entry -> {
            ObjectNode p = props.putObject(entry.getKey());
            p.put("type", entry.getValue().path("type").asText("string").toLowerCase(Locale.ROOT));
            p.put("description", entry.getValue().path("description").asText(""));
        });
        if (parameters.path("required").isArray()) schema.set("required", parameters.path("required"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode result(JsonNode id, JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id);
        out.set("result", value);
        return out;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", "2.0");
        if (id == null) out.putNull("id");
        else out.set("id", id);
        ObjectNode err = out.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return out;
    }
}
