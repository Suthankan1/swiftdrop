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
        when(profile.getFullName()).thenReturn("Old Name");
        when(profile.getPhoneNumber()).thenReturn("");
        when(profile.getEmail()).thenReturn("c@b.test");
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
        verify(accounts).setSignInDisabled(profileId, true);
        verify(auditService).recordAudit(eq("customer-sub"), eq("USER"), eq("DISABLE_USER"), eq("ENABLED"),
                eq("DISABLED"), eq(ReconciliationOutcome.SUCCESS), eq("admin-sub"), any(), any());
    }

    @Test
    void adminReEnablesUser() {
        caller("admin-sub", "ADMIN");

        assertThat(controller.enable(profileId).getBody().enabled()).isTrue();
        verify(identityAdapter).setUserEnabled("customer-sub", true);
        verify(accounts).setSignInDisabled(profileId, false);
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

    @Test
    void adminUpdatesNameAndPhoneAndTheChangeIsAudited() {
        caller("admin-sub", "ADMIN");

        var response = controller.update(profileId, new AdminUserAccessController.UpdateUserRequest("  New Name ", "+94 77 123 4567"));

        assertThat(response.getBody().fullName()).isEqualTo("New Name");
        assertThat(response.getBody().phoneNumber()).isEqualTo("+94 77 123 4567");
        verify(accounts).updateContactDetails(profileId, "New Name", "+94 77 123 4567");
        verify(auditService).recordAudit(eq("customer-sub"), eq("USER"), eq("UPDATE_USER"), any(), any(),
                eq(ReconciliationOutcome.SUCCESS), eq("admin-sub"), any(), any());
    }

    @Test
    void partialUpdateKeepsTheOtherField() {
        caller("admin-sub", "ADMIN");
        controller.update(profileId, new AdminUserAccessController.UpdateUserRequest(null, "0771234567"));
        verify(accounts).updateContactDetails(profileId, "Old Name", "0771234567");
    }

    @Test
    void invalidEditsAreRejectedAndNothingIsWritten() {
        caller("admin-sub", "ADMIN");
        for (var bad : java.util.List.of(
                new AdminUserAccessController.UpdateUserRequest(null, null),
                new AdminUserAccessController.UpdateUserRequest("   ", null),
                new AdminUserAccessController.UpdateUserRequest("Bad\u0000Name", null),
                new AdminUserAccessController.UpdateUserRequest(null, "call me maybe"))) {
            assertThatThrownBy(() -> controller.update(profileId, bad)).isInstanceOf(ResponseStatusException.class);
        }
        verify(accounts, never()).updateContactDetails(any(), any(), any());
    }

    @Test
    void nonAdminCannotEdit() {
        caller("dispatcher-sub", "DISPATCHER");
        assertThatThrownBy(() -> controller.update(profileId, new AdminUserAccessController.UpdateUserRequest("X", null)))
                .isInstanceOf(StaffAccessDeniedException.class);
        verify(accounts, never()).updateContactDetails(any(), any(), any());
    }
}
