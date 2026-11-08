package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.identity.keycloak.KeycloakIdentityAdapter;
import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import com.swiftdrop.accountsservice.reconciliation.ReconciliationAuditService;
import com.swiftdrop.accountsservice.staff.StaffAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserAccessControllerTest {

    private final UUID profileId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private CurrentIdentityProvider identityProvider;
    private AccountProfileRepository accounts;
    private KeycloakIdentityAdapter identityAdapter;
    private ReconciliationAuditService auditService;
    private AdminUserAccessController controller;

    @BeforeEach
    void setUp() {
        identityProvider = mock(CurrentIdentityProvider.class);
        accounts = mock(AccountProfileRepository.class);
        identityAdapter = mock(KeycloakIdentityAdapter.class);
        auditService = mock(ReconciliationAuditService.class);
        controller = new AdminUserAccessController(identityProvider, accounts, identityAdapter, auditService);
        AccountProfile profile = mock(AccountProfile.class);
        when(profile.getKeycloakSub()).thenReturn("customer-sub");
        when(accounts.findById(profileId)).thenReturn(Optional.of(profile));
    }

    private void caller(String subject, String... roles) {
        when(identityProvider.currentIdentity()).thenReturn(new TrustedIdentity(subject, "a@b.test", "Name", List.of(roles)));
    }

    @Test
    void adminDisablesUserAndTheChangeIsAudited() {
        caller("admin-sub", "ADMIN");

        var response = controller.disable(profileId);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().enabled()).isFalse();
        verify(identityAdapter).setUserEnabled("customer-sub", false);
        verify(auditService).recordAudit(eq("customer-sub"), eq("USER"), eq("DISABLE_USER"), eq("ENABLED"),
                eq("DISABLED"), eq(ReconciliationOutcome.SUCCESS), eq("admin-sub"), any(), any());
    }

    @Test
    void adminReEnablesUser() {
        caller("admin-sub", "ADMIN");

        assertThat(controller.enable(profileId).getBody().enabled()).isTrue();
        verify(identityAdapter).setUserEnabled("customer-sub", true);
    }

    @Test
    void nonAdminIsDenied() {
        caller("dispatcher-sub", "DISPATCHER");

        assertThatThrownBy(() -> controller.disable(profileId)).isInstanceOf(StaffAccessDeniedException.class);
        verify(identityAdapter, never()).setUserEnabled(anyString(), anyBoolean());
    }

    @Test
    void adminCannotDisableOwnAccount() {
        caller("customer-sub", "ADMIN");

        assertThatThrownBy(() -> controller.disable(profileId)).isInstanceOf(ResponseStatusException.class);
        verify(identityAdapter, never()).setUserEnabled(anyString(), anyBoolean());
    }

    @Test
    void unknownProfileIsNotFound() {
        caller("admin-sub", "ADMIN");

        assertThatThrownBy(() -> controller.disable(UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
    }
}
