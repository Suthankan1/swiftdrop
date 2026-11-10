package com.swiftdrop.assistant;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only tools the model may call. Each tool calls an existing SwiftDrop API exactly as the user could:
 * public endpoints anonymously, "my parcels" only with the caller's own bearer token. Results are reduced to
 * the fields needed to answer (no street addresses, recipient names or phone numbers are sent to the model).
 */
@Component
public class AssistantTools {

    static final Pattern TRACKING = Pattern.compile("^SWD-[0-9]{8}-[A-Z0-9]{4,8}$");
    static final Pattern ZONE = Pattern.compile("^[A-Z0-9_]{1,32}$");
    static final Set<String> TIERS = Set.of("STANDARD", "EXPRESS");

    private final AssistantProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AssistantTools(AssistantProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Function declarations in Gemini's OpenAPI-subset schema. "list_my_parcels" is offered only to signed-in users. */
    public ArrayNode declarations(boolean authenticated) {
        ArrayNode list = mapper.createArrayNode();
        ObjectNode track = function(list, "track_parcel",
                "Get the current status and milestone timeline of a parcel by its SwiftDrop tracking number (format SWD-YYYYMMDD-XXXX).");
        property(track, "trackingNumber", "STRING", "Tracking number, e.g. SWD-20261108-6C8FCFCF");
        required(track, "trackingNumber");

        function(list, "list_zones", "List the active delivery zones with their codes, names and standard transit hours.");

        ObjectNode quote = function(list, "get_quote",
                "Price a delivery between two zone codes. Call list_zones first if the user gives place names instead of zone codes.");
        property(quote, "pickupZone", "STRING", "Pickup zone code, e.g. ZONE_1");
        property(quote, "dropoffZone", "STRING", "Drop-off zone code, e.g. ZONE_2");
        property(quote, "weightKg", "NUMBER", "Parcel weight in kilograms (0.01 to 30)");
        property(quote, "lengthCm", "INTEGER", "Length in centimetres (default 20 if unknown)");
        property(quote, "widthCm", "INTEGER", "Width in centimetres (default 15 if unknown)");
        property(quote, "heightCm", "INTEGER", "Height in centimetres (default 10 if unknown)");
        property(quote, "serviceTier", "STRING", "STANDARD or EXPRESS (Same Day)");
        required(quote, "pickupZone", "dropoffZone", "weightKg");

        if (authenticated) {
            function(list, "list_my_parcels",
                    "List the signed-in user's most recent parcels with tracking number, status, fee and booking date.");
        }
        return list;
    }

    /** Runs a tool and returns a JSON object for the functionResponse. Errors are returned as {error: "..."}. */
    public ObjectNode execute(String name, JsonNode args, String bearerToken) {
        try {
            return switch (name) {
                case "track_parcel" -> trackParcel(args);
                case "list_zones" -> listZones();
                case "get_quote" -> getQuote(args);
                case "list_my_parcels" -> listMyParcels(bearerToken);
                default -> error("Unknown tool.");
            };
        } catch (ToolCallException e) {
            return error(e.getMessage());
        }
    }

    private ObjectNode trackParcel(JsonNode args) {
        String tn = args.path("trackingNumber").asText("").trim().toUpperCase(Locale.ROOT);
        if (!TRACKING.matcher(tn).matches()) {
            return error("That is not a valid SwiftDrop tracking number (format SWD-YYYYMMDD-XXXX).");
        }
        Response r = get(properties.deliveryUrl() + "/api/v1/tracking/" + URLEncoder.encode(tn, StandardCharsets.UTF_8), null);
        if (r.status == 404) return error("No parcel was found with that tracking number.");
        JsonNode body = r.require();
        ObjectNode out = mapper.createObjectNode();
        out.put("trackingNumber", body.path("trackingNumber").asText());
        out.put("currentStatus", body.path("currentStatus").asText());
        copyIfText(body, out, "originCity");
        copyIfText(body, out, "destinationCity");
        ArrayNode timeline = out.putArray("timeline");
        body.path("timeline").forEach(item -> {
            ObjectNode t = timeline.addObject();
            t.put("status", item.path("status").asText());
            t.put("occurredAt", item.path("occurredAt").asText());
            copyIfText(item, t, "cityOrZone");
        });
        return out;
    }

    private ObjectNode listZones() {
        JsonNode body = get(properties.pricingUrl() + "/api/v1/zones", null).require();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode zones = out.putArray("zones");
        body.path("zones").forEach(zone -> {
            ObjectNode z = zones.addObject();
            z.put("zoneCode", zone.path("zoneCode").asText());
            z.put("name", zone.path("name").asText());
            copyIfText(zone, z, "description");
            if (zone.path("standardTransitHours").isNumber()) z.put("standardTransitHours", zone.path("standardTransitHours").asInt());
        });
        return out;
    }

    private ObjectNode getQuote(JsonNode args) {
        String pickup = args.path("pickupZone").asText("").trim().toUpperCase(Locale.ROOT);
        String dropoff = args.path("dropoffZone").asText("").trim().toUpperCase(Locale.ROOT);
        double weight = args.path("weightKg").asDouble(-1);
        int length = args.path("lengthCm").asInt(20);
        int width = args.path("widthCm").asInt(15);
        int height = args.path("heightCm").asInt(10);
        String tier = args.path("serviceTier").asText("STANDARD").trim().toUpperCase(Locale.ROOT);
        if (!ZONE.matcher(pickup).matches() || !ZONE.matcher(dropoff).matches()) {
            return error("Zone codes look like ZONE_1. Use list_zones to find the right codes.");
        }
        if (!(weight > 0 && weight <= 30)) return error("Weight must be between 0.01 and 30 kg.");
        if (length < 1 || length > 300 || width < 1 || width > 300 || height < 1 || height > 300) {
            return error("Each dimension must be between 1 and 300 cm.");
        }
        if (!TIERS.contains(tier)) tier = "STANDARD";
        ObjectNode request = mapper.createObjectNode();
        request.put("pickupZone", pickup);
        request.put("dropoffZone", dropoff);
        request.put("weightKg", Math.round(weight * 100) / 100.0);
        request.put("lengthCm", length);
        request.put("widthCm", width);
        request.put("heightCm", height);
        request.put("serviceTier", tier);
        Response r = post(properties.pricingUrl() + "/api/v1/quotes", request);
        if (r.status == 400 || r.status == 404 || r.status == 422) {
            return error("Pricing could not price that route or parcel: " + safeDetail(r));
        }
        JsonNode body = r.require();
        ObjectNode out = mapper.createObjectNode();
        for (String field : new String[]{"pickupZone", "dropoffZone", "serviceTier", "currency", "sizeCategory", "expiresAt"}) {
            copyIfText(body, out, field);
        }
        for (String field : new String[]{"baseFeeMinorUnits", "weightSurchargeMinorUnits", "expressSurchargeMinorUnits", "totalAmountMinorUnits"}) {
            if (body.path(field).isNumber()) out.put(field, body.path(field).asLong());
        }
        if (body.path("chargeableWeightKg").isNumber()) out.put("chargeableWeightKg", body.path("chargeableWeightKg").asDouble());
        out.put("note", "Amounts are in minor units (cents); divide by 100 for the display amount. A quote is a price only, not a booking.");
        return out;
    }

    private ObjectNode listMyParcels(String bearerToken) {
        if (bearerToken == null) return error("The user is not signed in, so their parcels cannot be listed.");
        Response r = get(properties.deliveryUrl() + "/api/v1/parcels?page=0&size=10", bearerToken);
        if (r.status == 401 || r.status == 403) return error("The user's session does not allow listing parcels.");
        JsonNode body = r.require();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode parcels = out.putArray("parcels");
        body.path("items").forEach(item -> {
            ObjectNode p = parcels.addObject();
            p.put("trackingNumber", item.path("trackingNumber").asText());
            p.put("currentStatus", item.path("currentStatus").asText());
            if (item.path("amountMinorUnits").isNumber()) p.put("amountMinorUnits", item.path("amountMinorUnits").asLong());
            copyIfText(item, p, "currency");
            copyIfText(item, p, "createdAt");
        });
        if (body.path("totalElements").isNumber()) out.put("totalParcels", body.path("totalElements").asLong());
        return out;
    }

    // ---- HTTP helpers --------------------------------------------------------------------------------

    private static final class ToolCallException extends RuntimeException {
        ToolCallException(String message) {
            super(message);
        }
    }

    private final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        JsonNode require() {
            if (status == 429) throw new ToolCallException("SwiftDrop is rate limiting requests right now; try again shortly.");
            if (status < 200 || status >= 300) throw new ToolCallException("The SwiftDrop service is unavailable right now.");
            try {
                return mapper.readTree(body);
            } catch (RuntimeException e) {
                throw new ToolCallException("The SwiftDrop service returned an unreadable answer.");
            }
        }
    }

    private Response get(String url, String bearerToken) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json").GET();
        if (bearerToken != null) b.header("Authorization", "Bearer " + bearerToken);
        return send(b.build());
    }

    private Response post(String url, JsonNode payload) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new ToolCallException("The SwiftDrop service could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolCallException("The request was interrupted.");
        }
    }

    private String safeDetail(Response r) {
        try {
            String detail = mapper.readTree(r.body).path("detail").asText("");
            return detail.isBlank() ? "invalid request." : detail.substring(0, Math.min(200, detail.length()));
        } catch (RuntimeException e) {
            return "invalid request.";
        }
    }

    // ---- JSON helpers --------------------------------------------------------------------------------

    private ObjectNode error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        return node;
    }

    private static void copyIfText(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.path(field);
        if (value.isValueNode() && !value.isNull() && !value.asText().isBlank()) to.put(field, value.asText());
    }

    private ObjectNode function(ArrayNode list, String name, String description) {
        ObjectNode f = list.addObject();
        f.put("name", name);
        f.put("description", description);
        ObjectNode params = f.putObject("parameters");
        params.put("type", "OBJECT");
        params.putObject("properties");
        return f;
    }

    private static void property(ObjectNode function, String name, String type, String description) {
        ObjectNode p = ((ObjectNode) function.get("parameters").get("properties")).putObject(name);
        p.put("type", type);
        p.put("description", description);
    }

    private static void required(ObjectNode function, String... names) {
        ArrayNode req = ((ObjectNode) function.get("parameters")).putArray("required");
        for (String n : names) req.add(n);
    }
}
