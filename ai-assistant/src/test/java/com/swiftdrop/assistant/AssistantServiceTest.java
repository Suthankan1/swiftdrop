package com.swiftdrop.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private String base;
    private final Deque<String> geminiReplies = new ArrayDeque<>();
    private final List<JsonNode> geminiRequests = new ArrayList<>();
    private final List<String> geminiPaths = new ArrayList<>();
    private final List<String> geminiKeys = new ArrayList<>();
    private final Map<String, String> authSeen = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/", exchange -> {
            geminiPaths.add(exchange.getRequestURI().toString());
            geminiKeys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            geminiRequests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, geminiReplies.isEmpty() ? text("done") : geminiReplies.poll());
        });
        server.createContext("/api/v1/tracking/", exchange -> respond(exchange, 200, """
                {"trackingNumber":"SWD-20261108-ABCD1234","currentStatus":"IN_TRANSIT","originCity":"Colombo",
                 "destinationCity":"Kandy","timeline":[{"status":"CREATED","occurredAt":"2026-11-08T08:00:00Z"},
                 {"status":"IN_TRANSIT","occurredAt":"2026-11-08T10:00:00Z","cityOrZone":"En route"}]}"""));
        server.createContext("/api/v1/parcels", exchange -> {
            authSeen.put("parcels", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, 200, """
                    {"items":[{"parcelId":"p1","trackingNumber":"SWD-20261108-ABCD1234","currentStatus":"DELIVERED",
                      "amountMinorUnits":45000,"currency":"LKR","createdAt":"2026-11-08T08:00:00Z",
                      "pickupAddress":"12 Secret Lane","dropoffAddress":"99 Private Road","recipientName":"Nimal"}],
                     "totalElements":1}""");
        });
        server.createContext("/api/v1/quotes", exchange -> {
            JsonNode req = mapper.readTree(exchange.getRequestBody().readAllBytes());
            authSeen.put("quoteTier", req.path("serviceTier").asText());
            respond(exchange, 201, """
                    {"quoteId":"q1","pickupZone":"ZONE_1","dropoffZone":"ZONE_2","serviceTier":"EXPRESS","currency":"LKR",
                     "baseFeeMinorUnits":35000,"weightSurchargeMinorUnits":15000,"expressSurchargeMinorUnits":25000,
                     "totalAmountMinorUnits":75000}""");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String text(String value) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + value + "\"}]}}]}";
    }

    private static String call(String name, String argsJson) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"" + name
                + "\",\"args\":" + argsJson + "},\"thoughtSignature\":\"sig-1\"}]}}]}";
    }

    private AssistantService service(String key) {
        AssistantProperties props = new AssistantProperties(key, "gemini-test", base, base, base, 5, 4);
        return new AssistantService(new GeminiClient(props, mapper), new AssistantTools(props, mapper), props, mapper);
    }

    @Test
    void tracksParcelThroughToolCallAndAnswersFromLiveData() {
        geminiReplies.add(call("track_parcel", "{\"trackingNumber\":\"swd-20261108-abcd1234\"}"));
        geminiReplies.add(text("Your parcel is in transit to Kandy."));

        AssistantService.Reply reply = service("test-key").chat(
                List.of(new AssistantService.Turn("user", "Where is SWD-20261108-ABCD1234?")), null);

        assertThat(reply.text()).isEqualTo("Your parcel is in transit to Kandy.");
        assertThat(reply.toolsUsed()).containsExactly("track_parcel");
        // The key goes in the header only, never in the URL.
        assertThat(geminiKeys).containsOnly("test-key");
        assertThat(geminiPaths).allSatisfy(p -> assertThat(p).doesNotContain("test-key").contains("gemini-test:generateContent"));
        // Second request carries the model turn verbatim (with its thought signature) and the tool result.
        JsonNode second = geminiRequests.get(1).path("contents");
        assertThat(second.get(1).path("parts").get(0).path("thoughtSignature").asText()).isEqualTo("sig-1");
        JsonNode result = second.get(2).path("parts").get(0).path("functionResponse");
        assertThat(result.path("name").asText()).isEqualTo("track_parcel");
        assertThat(result.path("response").path("currentStatus").asText()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void myParcelsUsesCallerTokenAndNeverSendsAddressesToTheModel() {
        geminiReplies.add(call("list_my_parcels", "{}"));
        geminiReplies.add(text("Your latest parcel was delivered."));

        service("k").chat(List.of(new AssistantService.Turn("user", "Where is my parcel?")), "caller-jwt");

        assertThat(authSeen.get("parcels")).isEqualTo("Bearer caller-jwt");
        String sentToModel = geminiRequests.get(1).toString();
        assertThat(sentToModel).contains("SWD-20261108-ABCD1234").contains("DELIVERED")
                .doesNotContain("Secret Lane").doesNotContain("Private Road").doesNotContain("Nimal");
    }

    @Test
    void anonymousCallersAreNotOfferedMyParcels() {
        geminiReplies.add(text("Please share your tracking number."));
        service("k").chat(List.of(new AssistantService.Turn("user", "Where is my parcel?")), null);
        List<String> offered = new ArrayList<>();
        geminiRequests.get(0).path("tools").get(0).path("functionDeclarations").forEach(f -> offered.add(f.path("name").asText()));
        assertThat(offered).contains("track_parcel", "get_quote", "list_zones").doesNotContain("list_my_parcels");
    }

    @Test
    void anonymousCallCannotReachMyParcelsEvenIfTheModelAsks() {
        geminiReplies.add(call("list_my_parcels", "{}"));
        geminiReplies.add(text("I need you to sign in."));
        service("k").chat(List.of(new AssistantService.Turn("user", "list my parcels")), null);
        assertThat(authSeen).doesNotContainKey("parcels");
        assertThat(geminiRequests.get(1).toString()).contains("not signed in");
    }

    @Test
    void quoteToolPricesSameDayAndValidatesArguments() {
        geminiReplies.add(call("get_quote", "{\"pickupZone\":\"zone_1\",\"dropoffZone\":\"ZONE_2\",\"weightKg\":2,\"serviceTier\":\"express\"}"));
        geminiReplies.add(text("Same Day costs LKR 750.00."));
        service("k").chat(List.of(new AssistantService.Turn("user", "Same day price for 2 kg?")), null);
        assertThat(authSeen.get("quoteTier")).isEqualTo("EXPRESS");
        assertThat(geminiRequests.get(1).toString()).contains("\"totalAmountMinorUnits\":75000");

        AssistantTools tools = new AssistantTools(new AssistantProperties("k", "m", base, base, base, 5, 4), mapper);
        assertThat(tools.execute("get_quote", mapper.readTree("{\"pickupZone\":\"Z1; DROP\",\"dropoffZone\":\"ZONE_2\",\"weightKg\":2}"), null)
                .path("error").asText()).contains("Zone codes");
        assertThat(tools.execute("get_quote", mapper.readTree("{\"pickupZone\":\"ZONE_1\",\"dropoffZone\":\"ZONE_2\",\"weightKg\":80}"), null)
                .path("error").asText()).contains("Weight");
        assertThat(tools.execute("track_parcel", mapper.readTree("{\"trackingNumber\":\"../../admin\"}"), null)
                .path("error").asText()).contains("not a valid");
    }

    @Test
    void stopsAfterTheToolRoundLimit() {
        for (int i = 0; i < 10; i++) geminiReplies.add(call("list_zones", "{}"));
        server.createContext("/api/v1/zones", exchange -> respond(exchange, 200, "{\"zones\":[]}"));
        AssistantService.Reply reply = service("k").chat(List.of(new AssistantService.Turn("user", "zones?")), null);
        assertThat(reply.text()).contains("more steps");
        assertThat(geminiRequests).hasSize(5); // maxToolRounds (4) + the final attempt
    }

    @Test
    void disabledWithoutKeyAndProviderErrorsAreSanitised() {
        assertThatThrownBy(() -> service("").chat(List.of(new AssistantService.Turn("user", "hi")), null))
                .isInstanceOf(GeminiClient.GeminiException.class).hasMessageContaining("not configured");

        server.removeContext("/v1beta/models/");
        server.createContext("/v1beta/models/", exchange -> respond(exchange, 400, "{\"error\":{\"message\":\"API key not valid: secret-key\"}}"));
        assertThatThrownBy(() -> service("secret-key").chat(List.of(new AssistantService.Turn("user", "hi")), null))
                .isInstanceOf(GeminiClient.GeminiException.class)
                .hasMessageNotContaining("secret-key").hasMessageContaining("HTTP 400");
        assertThat(new AssistantProperties("secret-key", null, null, null, null, 0, 0).toString()).doesNotContain("secret-key");
    }
}
