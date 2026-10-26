package com.swiftdrop.pricing.api;

import com.swiftdrop.pricing.api.dto.QuoteRequest;
import com.swiftdrop.pricing.api.dto.QuoteResponse;
import com.swiftdrop.pricing.api.dto.ZoneListResponse;
import com.swiftdrop.pricing.application.QuoteApplicationService;
import com.swiftdrop.pricing.application.RateVersionQueryService;
import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.PricingZone;
import com.swiftdrop.pricing.domain.RateVersion;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.error.InactiveZoneException;
import com.swiftdrop.pricing.error.InvalidMeasurementsException;
import com.swiftdrop.pricing.error.QuoteNotFoundException;
import com.swiftdrop.pricing.error.ZoneNotFoundException;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.SecurityContextCallerResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PricingController.class)
@Import(PricingProblemHandler.class)
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
class PricingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteApplicationService quoteService;

    @MockitoBean
    private RateVersionQueryService rateVersionQueryService;

    @MockitoBean
    private SecurityContextCallerResolver callerResolver;

    private static final Instant CREATED_AT = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-26T10:15:00Z");

    @Test
    @DisplayName("POST /api/v1/quotes returns 201 Created on valid quote request")
    @WithMockUser
    void calculateQuoteSuccess() throws Exception {
        UUID quoteId = UUID.randomUUID();
        QuoteResponse response = new QuoteResponse(
                quoteId, "RATE-2026-V1", "ZONE_1", "ZONE_2",
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM, 35000L, 15000L, 50000L, "LKR",
                CREATED_AT, EXPIRES_AT, false
        );

        when(callerResolver.resolveCurrentCaller()).thenReturn(CallerContext.anonymous());
        when(quoteService.createQuote(any(QuoteRequest.class), any(CallerContext.class)))
                .thenReturn(response);

        String payload = """
                {
                  "pickupZone": "ZONE_1",
                  "dropoffZone": "ZONE_2",
                  "weightKg": 2.50,
                  "lengthCm": 30,
                  "widthCm": 20,
                  "heightCm": 15
                }
                """;

        mockMvc.perform(post("/api/v1/quotes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
                .andExpect(jsonPath("$.rateVersion").value("RATE-2026-V1"))
                .andExpect(jsonPath("$.pickupZone").value("ZONE_1"))
                .andExpect(jsonPath("$.dropoffZone").value("ZONE_2"))
                .andExpect(jsonPath("$.totalAmountMinorUnits").value(50000))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.isExpired").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/quotes rejects client-supplied unknown fields (e.g. injected totalPrice)")
    @WithMockUser
    void calculateQuoteRejectsInjectedPriceFields() throws Exception {
        String payloadWithExtra = """
                {
                  "pickupZone": "ZONE_1",
                  "dropoffZone": "ZONE_2",
                  "weightKg": 2.50,
                  "lengthCm": 30,
                  "widthCm": 20,
                  "heightCm": 15,
                  "totalPrice": 100,
                  "quoteId": "11111111-1111-1111-1111-111111111111"
                }
                """;

        mockMvc.perform(post("/api/v1/quotes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithExtra))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"));
    }

    @Test
    @DisplayName("POST /api/v1/quotes returns 400 Bad Request on invalid bean validation parameters")
    @WithMockUser
    void calculateQuoteValidationErrors() throws Exception {
        // weight out of bounds (35kg > 30kg) and zero dimension
        String invalidPayload = """
                {
                  "pickupZone": "",
                  "dropoffZone": "ZONE_2",
                  "weightKg": 35.00,
                  "lengthCm": 0,
                  "widthCm": 20,
                  "heightCm": 15
                }
                """;

        mockMvc.perform(post("/api/v1/quotes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.invalidParameters").isArray());
    }

    @Test
    @DisplayName("POST /api/v1/quotes returns 422 Unprocessable Entity when zone is not found")
    @WithMockUser
    void calculateQuoteUnknownZoneReturns422() throws Exception {
        when(callerResolver.resolveCurrentCaller()).thenReturn(CallerContext.anonymous());
        when(quoteService.createQuote(any(QuoteRequest.class), any(CallerContext.class)))
                .thenThrow(new ZoneNotFoundException("UNKNOWN_ZONE"));

        String payload = """
                {
                  "pickupZone": "UNKNOWN_ZONE",
                  "dropoffZone": "ZONE_2",
                  "weightKg": 2.50,
                  "lengthCm": 30,
                  "widthCm": 20,
                  "heightCm": 15
                }
                """;

        mockMvc.perform(post("/api/v1/quotes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/quotes returns 422 Unprocessable Entity when zone is inactive")
    @WithMockUser
    void calculateQuoteInactiveZoneReturns422() throws Exception {
        when(callerResolver.resolveCurrentCaller()).thenReturn(CallerContext.anonymous());
        when(quoteService.createQuote(any(QuoteRequest.class), any(CallerContext.class)))
                .thenThrow(new InactiveZoneException("ZONE_INACTIVE"));

        String payload = """
                {
                  "pickupZone": "ZONE_INACTIVE",
                  "dropoffZone": "ZONE_2",
                  "weightKg": 2.50,
                  "lengthCm": 30,
                  "widthCm": 20,
                  "heightCm": 15
                }
                """;

        mockMvc.perform(post("/api/v1/quotes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ZONE_INACTIVE"));
    }

    @Test
    @DisplayName("POST /api/v1/quotes returns 400 Bad Request on invalid measurements domain rule")
    @WithMockUser
    void calculateQuoteInvalidMeasurementsReturns400() throws Exception {
        when(callerResolver.resolveCurrentCaller()).thenReturn(CallerContext.anonymous());
        when(quoteService.createQuote(any(QuoteRequest.class), any(CallerContext.class)))
                .thenThrow(new InvalidMeasurementsException("Parcel girth exceeds limit"));

        String payload = """
                {
                  "pickupZone": "ZONE_1",
                  "dropoffZone": "ZONE_2",
                  "weightKg": 2.50,
                  "lengthCm": 30,
                  "widthCm": 20,
                  "heightCm": 15
                }
                """;

        mockMvc.perform(post("/api/v1/quotes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEASUREMENTS"));
    }

    @Test
    @DisplayName("GET /api/v1/quotes/{quoteId} returns 200 OK with quote response")
    @WithMockUser
    void getQuoteByIdSuccess() throws Exception {
        UUID quoteId = UUID.randomUUID();
        QuoteResponse response = new QuoteResponse(
                quoteId, "RATE-2026-V1", "ZONE_1", "ZONE_2",
                new BigDecimal("2.50"), new BigDecimal("1.80"), new BigDecimal("2.50"),
                SizeCategory.MEDIUM, 35000L, 15000L, 50000L, "LKR",
                CREATED_AT, EXPIRES_AT, false
        );

        when(callerResolver.resolveCurrentCaller()).thenReturn(CallerContext.anonymous());
        when(quoteService.getQuote(eq(quoteId), any(CallerContext.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/quotes/{quoteId}", quoteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
                .andExpect(jsonPath("$.rateVersion").value("RATE-2026-V1"))
                .andExpect(jsonPath("$.totalAmountMinorUnits").value(50000))
                .andExpect(jsonPath("$.isExpired").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/quotes/{quoteId} returns 404 Not Found when quote missing or expired/forbidden")
    @WithMockUser
    void getQuoteByIdNotFound() throws Exception {
        UUID quoteId = UUID.randomUUID();
        when(callerResolver.resolveCurrentCaller()).thenReturn(CallerContext.anonymous());
        when(quoteService.getQuote(eq(quoteId), any(CallerContext.class)))
                .thenThrow(new QuoteNotFoundException(quoteId));

        mockMvc.perform(get("/api/v1/quotes/{quoteId}", quoteId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/quotes/{quoteId} returns 400 Bad Request on invalid UUID in path")
    @WithMockUser
    void getQuoteByIdInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/v1/quotes/not-a-valid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDENTIFIER"));
    }

    @Test
    @DisplayName("GET /api/v1/zones returns 200 OK with active zones list")
    @WithMockUser
    void getZonesSuccess() throws Exception {
        RateVersion activeRate = new RateVersion(
                UUID.randomUUID(), "RATE-2026-V1", Money.DEFAULT_CURRENCY,
                RateVersionStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"),
                null, "Default 2026 Rates", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), null
        );

        PricingZone z1 = new PricingZone(UUID.randomUUID(), "ZONE_1", "Western Zone", "Western Description", 24, true);
        PricingZone z2 = new PricingZone(UUID.randomUUID(), "ZONE_2", "Central Zone", "Central Description", 48, true);

        when(rateVersionQueryService.getActiveRateVersion(Money.DEFAULT_CURRENCY)).thenReturn(activeRate);
        when(rateVersionQueryService.getActiveZones()).thenReturn(List.of(z1, z2));

        mockMvc.perform(get("/api/v1/zones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRateVersion").value("RATE-2026-V1"))
                .andExpect(jsonPath("$.zones", hasSize(2)))
                .andExpect(jsonPath("$.zones[0].zoneCode").value("ZONE_1"))
                .andExpect(jsonPath("$.zones[1].zoneCode").value("ZONE_2"));
    }
}
