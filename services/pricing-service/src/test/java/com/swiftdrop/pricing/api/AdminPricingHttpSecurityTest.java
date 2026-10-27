package com.swiftdrop.pricing.api;

import com.swiftdrop.pricing.api.dto.AdminPricingDtos.*;
import com.swiftdrop.pricing.application.AdminPricingService;
import com.swiftdrop.pricing.grpc.JwtTestHelper;
import com.swiftdrop.pricing.security.PricingSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminPricingController.class)
@Import({PricingSecurityConfiguration.class, PricingProblemHandler.class, AdminPricingHttpSecurityTest.Tokens.class})
@TestPropertySource(properties = {"spring.jackson.deserialization.fail-on-unknown-properties=true", "spring.jackson.deserialization.accept-float-as-int=false"})
class AdminPricingHttpSecurityTest {
    private static final JwtTestHelper JWT = new JwtTestHelper();
    private static final String ROOT = "/api/v1/admin/pricing";
    private static final String ZONE = """
            {"zoneCode":"ZONE_4","name":"New zone","description":"","standardTransitHours":24}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean AdminPricingService service;

    @TestConfiguration(proxyBeanMethods = false)
    static class Tokens {
        @Bean @org.springframework.context.annotation.Primary
        PricingSecurityConfiguration.HttpJwtValidation adminTestJwtDecoder() {
            return new PricingSecurityConfiguration.HttpJwtValidation(JWT.createUserJwtDecoder());
        }
    }

    @Test
    void noJwtIs401EvenWithForgedRoleAndTenantHeaders() throws Exception {
        mvc.perform(post(ROOT + "/zones").header("X-Roles", "ADMIN").header("X-Tenant-Id", "other")
                .contentType(MediaType.APPLICATION_JSON).content(ZONE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "ROLE_ADMIN", "SUPER_ADMIN"})
    void allNonAdminRolesAreDeniedForEveryOperation(String role) throws Exception {
        String token = JWT.createSignedUserToken("actor-123", List.of(role));
        String id = UUID.randomUUID().toString();
        var requests = List.of(get(ROOT + "/zones"), get(ROOT + "/zones/" + id), post(ROOT + "/zones"),
                put(ROOT + "/zones/" + id), post(ROOT + "/zones/" + id + "/disable"), get(ROOT + "/rate-versions"),
                get(ROOT + "/rate-versions/" + id + "/rules"), post(ROOT + "/rate-versions"),
                post(ROOT + "/rate-versions/" + id + "/rules"), put(ROOT + "/rate-versions/" + id + "/rules/" + id),
                post(ROOT + "/rate-versions/" + id + "/activate"), post(ROOT + "/rate-versions/" + id + "/archive"));
        for (var request : requests) mvc.perform(request.header("Authorization", "Bearer " + token)
                .header("X-SwiftDrop-Actor-Roles", "ADMIN").header("X-Tenant-Id", "forged")
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void invalidExpiredUntrustedAndMissingSubjectTokensAre401() throws Exception {
        List<String> tokens = List.of("invalid", JWT.createExpiredUserToken("admin", List.of("ADMIN")),
                JWT.createUntrustedSignedUserToken("admin", List.of("ADMIN")),
                JWT.createSignedUserToken(" ", List.of("ADMIN")));
        for (String token : tokens) mvc.perform(post(ROOT + "/zones").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(ZONE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void verifiedAdminCanCreateAndRead() throws Exception {
        UUID id = UUID.randomUUID();
        ZoneView zone = new ZoneView(id, "ZONE_4", "New zone", "", 24, true, 0, Instant.EPOCH, Instant.EPOCH);
        when(service.createZone(any())).thenReturn(zone);
        when(service.listZones(0, 20)).thenReturn(new PageView<>(List.of(zone), 0, 20, 1));
        String token = JWT.createSignedUserToken("admin-123", List.of("ADMIN"));
        mvc.perform(post(ROOT + "/zones").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(ZONE))
                .andExpect(status().isCreated()).andExpect(header().string("Location", ROOT + "/zones/" + id));
        mvc.perform(get(ROOT + "/zones").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].zoneCode").value("ZONE_4"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"zoneCode\":\"\",\"name\":\"Zone\",\"description\":\"\",\"standardTransitHours\":24}",
            "{\"zoneCode\":\"ZONE_4\",\"name\":\"Zone\",\"description\":\"\",\"standardTransitHours\":0}",
            "{\"zoneCode\":\"ZONE_4\",\"name\":\"Zone\",\"description\":\"\",\"standardTransitHours\":1.5}",
            "{\"zoneCode\":\"ZONE_4\",\"name\":\"Zone\",\"description\":\"\",\"standardTransitHours\":24,\"active\":true}"
    })
    void invalidAndUnknownFieldsAre400(String body) throws Exception {
        mvc.perform(post(ROOT + "/zones").header("Authorization", "Bearer " + JWT.createSignedUserToken("admin", List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void malformedIdentifierIs400() throws Exception {
        mvc.perform(get(ROOT + "/zones/not-a-uuid").header("Authorization", "Bearer " + JWT.createSignedUserToken("admin", List.of("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifiedAdminStillReceivesApplicationPolicyDenial() throws Exception {
        when(service.createZone(any())).thenThrow(new org.springframework.security.access.AccessDeniedException("Policy denied"));
        mvc.perform(post(ROOT + "/zones").header("Authorization", "Bearer " + JWT.createSignedUserToken("admin", List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(ZONE)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative", "fractional", "overflow", "category", "precision"})
    void invalidMoneyAndWeightRepresentationCannotReachCommands(String variant) throws Exception {
        String body = """
                {"expectedRevision":0,"pickupZoneCode":"ZONE_1","dropoffZoneCode":"ZONE_2","sizeCategory":"MEDIUM",
                "minWeightKg":0.01,"maxWeightKg":5.00,"baseFeeMinorUnits":35000,"perKgSurchargeMinorUnits":5000,"weightThresholdKg":1.00}
                """;
        body = switch (variant) {
            case "negative" -> body.replace("35000", "-1");
            case "fractional" -> body.replace("35000", "1.5");
            case "overflow" -> body.replace("35000", "9223372036854775808");
            case "category" -> body.replace("MEDIUM", "UNKNOWN");
            default -> body.replace("0.01", "0.001");
        };
        mvc.perform(post(ROOT + "/rate-versions/" + UUID.randomUUID() + "/rules")
                .header("Authorization", "Bearer " + JWT.createSignedUserToken("admin", List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
