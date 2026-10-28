package com.swiftdrop.deliveryservice.booking.controller;

import com.swiftdrop.deliveryservice.booking.BookingFailure;
import com.swiftdrop.deliveryservice.booking.BookingService;
import com.swiftdrop.deliveryservice.booking.MerchantBookParcelRequest;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.security.merchant.MerchantAuthenticationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MerchantParcelBookingControllerTest {

    private BookingService bookingService;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    private final UUID merchantId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @BeforeEach
    void setUp() {
        bookingService = mock(BookingService.class);
        MerchantParcelBookingController controller = new MerchantParcelBookingController(bookingService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MerchantBookingProblemHandler())
                .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MerchantBookParcelRequest validRequest() {
        return new MerchantBookParcelRequest(
                quoteId,
                "No 12, Galle Road, Colombo 03",
                "No 45, Kandy Road, Kelaniya",
                "Saman Perera",
                "+94771234567",
                "REF-2026-001",
                "Fragile delivery"
        );
    }

    private ParcelDetailResponse sampleResponse(UUID parcelId) {
        return new ParcelDetailResponse(
                parcelId,
                "SWD-20261027-ABC12345",
                "merchant-caller",
                merchantId,
                ParcelStatus.CREATED,
                1L,
                2.5,
                "STANDARD",
                150000L,
                "LKR",
                "RATE-2026-V1",
                "No 12, Galle Road, Colombo 03",
                "No 45, Kandy Road, Kelaniya",
                null,
                now
        );
    }

    @Test
    @DisplayName("Valid booking request with X-API-Key auth returns 201 Created and ParcelDetailResponse")
    void validBookingWithApiKeyAuthReturnsCreated() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        UUID parcelId = UUID.randomUUID();
        when(bookingService.bookMerchant(eq(merchantId), any(), eq(idempotencyKey), any(MerchantBookParcelRequest.class)))
                .thenReturn(sampleResponse(parcelId));

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parcelId", equalTo(parcelId.toString())))
                .andExpect(jsonPath("$.currentStatus", equalTo("CREATED")))
                .andExpect(jsonPath("$.amountMinorUnits", equalTo(150000)))
                .andExpect(jsonPath("$.currency", equalTo("LKR")))
                .andExpect(jsonPath("$.trackingNumber", equalTo("SWD-20261027-ABC12345")));

        verify(bookingService).bookMerchant(eq(merchantId), any(), eq(idempotencyKey), any(MerchantBookParcelRequest.class));
    }

    @Test
    @DisplayName("Valid booking request with Bearer JWT auth and merchant_id claim returns 201 Created")
    void validBookingWithJwtAuthReturnsCreated() throws Exception {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("user-merchant-123")
                .claim("realm_access", Map.of("roles", List.of("MERCHANT")))
                .claim("merchant_id", merchantId.toString())
                .build();
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))
        );
        SecurityContextHolder.getContext().setAuthentication(jwtAuth);

        UUID parcelId = UUID.randomUUID();
        when(bookingService.bookMerchant(eq(merchantId), eq("user-merchant-123"), eq(idempotencyKey), any(MerchantBookParcelRequest.class)))
                .thenReturn(sampleResponse(parcelId));

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parcelId", equalTo(parcelId.toString())));
    }

    @Test
    @DisplayName("Missing Idempotency-Key header returns 400 Bad Request with RFC 7807 problem details")
    void missingIdempotencyKeyReturns400() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        mvc.perform(post("/api/v1/merchant/parcels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", equalTo("INVALID_REQUEST")))
                .andExpect(jsonPath("$.detail", containsString("Idempotency-Key header is required")));
    }

    @Test
    @DisplayName("Invalid UUID format in Idempotency-Key header returns 400 Bad Request")
    void invalidIdempotencyKeyFormatReturns400() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", "not-a-valid-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", equalTo("INVALID_REQUEST")))
                .andExpect(jsonPath("$.detail", containsString("must be a valid UUID format")));
    }

    @Test
    @DisplayName("Unauthenticated request returns 401 Unauthorized")
    void unauthenticatedRequestReturns401() throws Exception {
        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title", equalTo("UNAUTHENTICATED")));
    }

    @Test
    @DisplayName("JWT without MERCHANT role returns 403 Forbidden")
    void jwtWithoutMerchantRoleReturns403() throws Exception {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("customer-sub")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .claim("merchant_id", merchantId.toString())
                .build();
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        SecurityContextHolder.getContext().setAuthentication(jwtAuth);

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", equalTo("FORBIDDEN")))
                .andExpect(jsonPath("$.detail", containsString("MERCHANT role required")));
    }

    @Test
    @DisplayName("JWT without merchant_id claim returns 403 Forbidden")
    void jwtWithoutMerchantIdClaimReturns403() throws Exception {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("merchant-sub")
                .claim("realm_access", Map.of("roles", List.of("MERCHANT")))
                .build();
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))
        );
        SecurityContextHolder.getContext().setAuthentication(jwtAuth);

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", equalTo("FORBIDDEN")))
                .andExpect(jsonPath("$.detail", containsString("merchant_id tenant claim")));
    }

    @Test
    @DisplayName("Expired quote returns 422 Unprocessable Entity")
    void expiredQuoteReturns422() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        when(bookingService.bookMerchant(any(), any(), any(), any()))
                .thenThrow(new BookingFailure(422, "QUOTE_EXPIRED", "The provided pricing quote has expired."));

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title", equalTo("QUOTE_EXPIRED")));
    }

    @Test
    @DisplayName("Quote forbidden (cross-tenant) returns 403 Forbidden")
    void quoteForbiddenReturns403() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        when(bookingService.bookMerchant(any(), any(), any(), any()))
                .thenThrow(new BookingFailure(403, "QUOTE_FORBIDDEN", "The requested quote belongs to another merchant tenant."));

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", equalTo("QUOTE_FORBIDDEN")));
    }

    @Test
    @DisplayName("Idempotency conflict returns 409 Conflict")
    void idempotencyConflictReturns409() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        when(bookingService.bookMerchant(any(), any(), any(), any()))
                .thenThrow(new BookingFailure(409, "IDEMPOTENCY_CONFLICT", "The provided Idempotency-Key was previously submitted with different request parameters."));

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title", equalTo("IDEMPOTENCY_CONFLICT")));
    }

    @Test
    @DisplayName("Quote already consumed returns 409 Conflict")
    void quoteAlreadyConsumedReturns409() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        when(bookingService.bookMerchant(any(), any(), any(), any()))
                .thenThrow(new BookingFailure(409, "QUOTE_ALREADY_CONSUMED", "The provided pricing quote has already been consumed by an existing parcel booking."));

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title", equalTo("QUOTE_ALREADY_CONSUMED")));
    }

    @Test
    @DisplayName("Downstream service unavailable returns 503 Service Unavailable")
    void downstreamUnavailableReturns503() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        when(bookingService.bookMerchant(any(), any(), any(), any()))
                .thenThrow(new BookingFailure(503, "DOWNSTREAM_UNAVAILABLE", "Pricing verification service unavailable."));

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(validRequest())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title", equalTo("DOWNSTREAM_UNAVAILABLE")));
    }

    @Test
    @DisplayName("Validation failure on request body returns 400 Bad Request with field details")
    void validationErrorReturns400() throws Exception {
        MerchantAuthenticationToken token = new MerchantAuthenticationToken(
                merchantId, "Acme Corp", "API_KEY", Set.of("parcels:create")
        );
        SecurityContextHolder.getContext().setAuthentication(token);

        MerchantBookParcelRequest invalid = new MerchantBookParcelRequest(
                null, "", "", "", "invalid-phone", null, null
        );

        mvc.perform(post("/api/v1/merchant/parcels")
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", equalTo("Validation Error")))
                .andExpect(jsonPath("$.invalidParameters").isArray());
    }
}
