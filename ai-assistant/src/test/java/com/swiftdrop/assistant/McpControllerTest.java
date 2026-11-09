package com.swiftdrop.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MCP over the real security chain; no Gemini key is configured, the tools still work. */
@SpringBootTest
@AutoConfigureMockMvc
class McpControllerTest {

    static final HttpServer DELIVERY;

    static {
        try {
            DELIVERY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DELIVERY.createContext("/api/v1/tracking/", exchange -> {
            byte[] body = """
                    {"trackingNumber":"SWD-20261108-ABCD1234","currentStatus":"OUT_FOR_DELIVERY",
                     "timeline":[{"status":"CREATED","occurredAt":"2026-11-08T08:00:00Z"}]}""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        DELIVERY.start();
    }

    @AfterAll
    static void stop() {
        DELIVERY.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("swiftdrop.assistant.gemini-api-key", () -> "");
        registry.add("swiftdrop.assistant.delivery-url", () -> "http://127.0.0.1:" + DELIVERY.getAddress().getPort());
    }

    @Autowired
    MockMvc mvc;

    private ResultActions rpc(String json) throws Exception {
        return mvc.perform(post("/api/v1/assistant/mcp").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void initializeNegotiatesProtocolAndAdvertisesTools() throws Exception {
        rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.serverInfo.name").value("swiftdrop-delivery-tools"));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}")
                .andExpect(jsonPath("$.result.protocolVersion").value(McpController.LATEST_PROTOCOL));
    }

    @Test
    void anonymousToolListHasOnlyPublicReadOnlyTools() throws Exception {
        rpc("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\"}")
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("track_parcel")))
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("get_quote")))
                .andExpect(jsonPath("$.result.tools[*].name", not(hasItem("list_my_parcels"))))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.type").value("object"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.properties.trackingNumber.type").value("string"))
                .andExpect(jsonPath("$.result.tools[0].annotations.readOnlyHint").value(true));
    }

    @Test
    void toolCallReturnsLiveDataAsTextAndStructuredContent() throws Exception {
        rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"track_parcel\",\"arguments\":{\"trackingNumber\":\"SWD-20261108-ABCD1234\"}}}")
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.currentStatus").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text", containsString("OUT_FOR_DELIVERY")));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"track_parcel\",\"arguments\":{\"trackingNumber\":\"../etc\"}}}")
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    @Test
    void anonymousCallerCannotInvokeMyParcels() throws Exception {
        rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"list_my_parcels\"}}")
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void protocolErrorsAndNotifications() throws Exception {
        rpc("not json").andExpect(jsonPath("$.error.code").value(-32700));
        rpc("{\"jsonrpc\":\"1.0\",\"id\":6,\"method\":\"ping\"}").andExpect(jsonPath("$.error.code").value(-32600));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}").andExpect(jsonPath("$.error.code").value(-32601));
        rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").andExpect(status().isAccepted())
                .andExpect(content().string(""));
        rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}").andExpect(jsonPath("$.result").isMap());
        mvc.perform(get("/api/v1/assistant/mcp")).andExpect(status().isMethodNotAllowed());
    }
}
