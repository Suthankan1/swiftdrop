package com.swiftdrop.accountsservice;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.profile.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller-only synthetic fixture; this is NOT a live authenticated HTTP success test. */
class CurrentUserProfileContractTest {
    @Test
    void serializesFrozenContractAndUsesOnlyProviderIdentity() throws Exception {
        var identity = new TrustedIdentity("synthetic-owner", null, null, List.of("CUSTOMER"));
        var profile = new CurrentUserProfile(UUID.randomUUID(), identity.subject(), "", "", "", identity.roles(),
                null, Instant.parse("2026-10-16T00:00:00Z"));
        var service = mock(CurrentUserProfileService.class);
        when(service.getOrCreate(identity)).thenReturn(profile);
        var mvc = MockMvcBuilders.standaloneSetup(new CurrentUserProfileController(() -> identity, service)).build();
        var response = mvc.perform(get("/api/v1/users/me").param("subject", "attacker-chosen")
                        .header("X-Keycloak-Sub", "attacker-chosen"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        var json = JsonMapper.builder().build().readTree(response);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("id", "keycloakSub", "email", "fullName",
                "phoneNumber", "roles", "merchantProfile", "createdAt");
        for (var field : List.of("id", "keycloakSub", "email", "fullName", "phoneNumber", "createdAt")) {
            assertThat(json.get(field).isString()).as(field).isTrue();
        }
        assertThat(json.get("id").asString()).isEqualTo(profile.id().toString());
        assertThat(json.get("keycloakSub").asString()).isEqualTo(identity.subject());
        assertThat(json.get("merchantProfile").isNull()).isTrue();
        assertThat(json.get("roles").get(0).asString()).isEqualTo("CUSTOMER");
        assertThat(json.get("createdAt").asString()).isEqualTo("2026-10-16T00:00:00Z");
        verify(service).getOrCreate(identity);
        verifyNoMoreInteractions(service);
    }

    @Test
    void domainErrorDoesNotExposeInternalValidationDetail() throws Exception {
        var service = mock(CurrentUserProfileService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CurrentUserProfileController(
                () -> { throw new IllegalArgumentException("sensitive-subject-mapping"); }, service))
                .setControllerAdvice(new ProfileProblemHandler()).build();
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("Profile identity attributes are invalid."));
        verifyNoInteractions(service);
    }
}
