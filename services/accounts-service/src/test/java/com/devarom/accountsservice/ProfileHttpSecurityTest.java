package com.devarom.accountsservice;

import com.devarom.accountsservice.profile.CurrentUserProfileController;
import com.devarom.accountsservice.profile.CurrentUserProfileService;
import com.devarom.accountsservice.security.ProfileSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CurrentUserProfileController.class,
        excludeAutoConfiguration = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@Import(ProfileSecurityConfiguration.class)
class ProfileHttpSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProfileService profiles;

    @Test
    void anonymousProfileAccessIsRejected() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401));
        verifyNoInteractions(profiles);
    }

    @Test
    void spoofedHeadersAndSubjectParametersCannotGrantAccess() throws Exception {
        mvc.perform(get("/api/v1/users/me").param("subject", "other-person")
                        .param("id", "chosen-id")
                        .header("X-User-Id", "other-person").header("X-Keycloak-Sub", "other-person")
                        .header("X-Roles", "ADMIN").header("X-User-Email", "other@example.test"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void arbitraryBearerDoesNotAuthenticate() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer synthetic-unverified"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void basicAndFormLoginAreUnavailable() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Basic dGVzdDp0ZXN0"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test
    void evenAuthenticatedFixtureIsDeniedUntilIssue37() throws Exception {
        mvc.perform(get("/api/v1/users/me").with(user("synthetic").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("status").value(403));
        verifyNoInteractions(profiles);
    }
}
