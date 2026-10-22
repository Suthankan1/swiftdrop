package com.swiftdrop.accountsservice.staff;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StaffProfileServiceTest {

    private StaffProfileRepository staffProfiles;
    private AccountProfileRepository accountProfiles;
    private StaffProfileService service;

    private final UUID courierProfileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID dispatcherProfileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID adminProfileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID accountProfileId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final String courierSub = "sub-courier-01";
    private final String dispatcherSub = "sub-dispatcher-01";
    private final String adminSub = "sub-admin-01";
    private final String customerSub = "sub-customer-01";

    private final TrustedIdentity courierIdentity = new TrustedIdentity(
            courierSub, "courier.kamal@swiftdrop.test", "Kamal Perera", List.of("COURIER")
    );
    private final TrustedIdentity dispatcherIdentity = new TrustedIdentity(
            dispatcherSub, "dispatcher.nimal@swiftdrop.test", "Nimal Silva", List.of("DISPATCHER")
    );
    private final TrustedIdentity adminIdentity = new TrustedIdentity(
            adminSub, "admin.john@swiftdrop.test", "John Admin", List.of("ADMIN")
    );
    private final TrustedIdentity customerIdentity = new TrustedIdentity(
            customerSub, "cust@swiftdrop.test", "Customer Alice", List.of("CUSTOMER")
    );

    private StaffProfile courierStaffProfile;
    private StaffProfile dispatcherStaffProfile;
    private StaffProfile adminStaffProfile;

    @BeforeEach
    void setUp() {
        staffProfiles = mock(StaffProfileRepository.class);
        accountProfiles = mock(AccountProfileRepository.class);
        service = new StaffProfileService(staffProfiles, accountProfiles);

        Instant now = Instant.parse("2026-10-18T08:30:00Z");

        courierStaffProfile = new StaffProfile(
                courierProfileId,
                accountProfileId,
                courierSub,
                "STF-2026-0042",
                StaffRole.COURIER,
                "FIELD_LOGISTICS",
                "ZONE-WEST-COLOMBO",
                StaffStatus.ACTIVE,
                now,
                now
        );

        dispatcherStaffProfile = new StaffProfile(
                dispatcherProfileId,
                accountProfileId,
                dispatcherSub,
                "STF-2026-0010",
                StaffRole.DISPATCHER,
                "DISPATCH_OPERATIONS",
                null,
                StaffStatus.ACTIVE,
                now,
                now
        );

        adminStaffProfile = new StaffProfile(
                adminProfileId,
                accountProfileId,
                adminSub,
                "STF-2026-0001",
                StaffRole.ADMIN,
                "PLATFORM_ADMINISTRATION",
                null,
                StaffStatus.ACTIVE,
                now,
                now
        );
    }

    @Test
    @DisplayName("COURIER resolves own staff profile with correct attributes")
    void getStaffProfile_courierSuccess() {
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierStaffProfile));

        StaffProfileResponse response = service.getStaffProfile(courierIdentity);

        assertThat(response).isNotNull();
        assertThat(response.profileId()).isEqualTo(courierProfileId);
        assertThat(response.id()).isEqualTo(courierProfileId);
        assertThat(response.staffId()).isEqualTo("STF-2026-0042");
        assertThat(response.keycloakSub()).isEqualTo(courierSub);
        assertThat(response.role()).isEqualTo(StaffRole.COURIER);
        assertThat(response.department()).isEqualTo("FIELD_LOGISTICS");
        assertThat(response.assignedZone()).isEqualTo("ZONE-WEST-COLOMBO");
        assertThat(response.businessStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(response.status()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(response.displayName()).isEqualTo("Kamal Perera");
        assertThat(response.email()).isEqualTo("courier.kamal@swiftdrop.test");
    }

    @Test
    @DisplayName("DISPATCHER resolves own staff profile with correct attributes")
    void getStaffProfile_dispatcherSuccess() {
        when(staffProfiles.findByKeycloakSub(dispatcherSub)).thenReturn(Optional.of(dispatcherStaffProfile));

        StaffProfileResponse response = service.getStaffProfile(dispatcherIdentity);

        assertThat(response).isNotNull();
        assertThat(response.profileId()).isEqualTo(dispatcherProfileId);
        assertThat(response.staffId()).isEqualTo("STF-2026-0010");
        assertThat(response.role()).isEqualTo(StaffRole.DISPATCHER);
        assertThat(response.department()).isEqualTo("DISPATCH_OPERATIONS");
        assertThat(response.assignedZone()).isNull();
        assertThat(response.businessStatus()).isEqualTo(StaffStatus.ACTIVE);
    }

    @Test
    @DisplayName("ADMIN resolves own staff profile with correct attributes")
    void getStaffProfile_adminSuccess() {
        when(staffProfiles.findByKeycloakSub(adminSub)).thenReturn(Optional.of(adminStaffProfile));

        StaffProfileResponse response = service.getStaffProfile(adminIdentity);

        assertThat(response).isNotNull();
        assertThat(response.profileId()).isEqualTo(adminProfileId);
        assertThat(response.staffId()).isEqualTo("STF-2026-0001");
        assertThat(response.role()).isEqualTo(StaffRole.ADMIN);
        assertThat(response.department()).isEqualTo("PLATFORM_ADMINISTRATION");
        assertThat(response.businessStatus()).isEqualTo(StaffStatus.ACTIVE);
    }

    @Test
    @DisplayName("CUSTOMER role is rejected from staff self-read with 403 access denied")
    void getStaffProfile_customerRoleDenied() {
        assertThatThrownBy(() -> service.getStaffProfile(customerIdentity))
                .isInstanceOf(StaffAccessDeniedException.class)
                .hasMessageContaining("Caller lacks required staff role");
        verifyNoInteractions(staffProfiles);
    }

    @Test
    @DisplayName("MERCHANT role is rejected from staff self-read with 403 access denied")
    void getStaffProfile_merchantRoleDenied() {
        TrustedIdentity merchantIdentity = new TrustedIdentity(
                "sub-merch", "m@swiftdrop.test", "Merchant Owner", List.of("MERCHANT")
        );
        assertThatThrownBy(() -> service.getStaffProfile(merchantIdentity))
                .isInstanceOf(StaffAccessDeniedException.class)
                .hasMessageContaining("Caller lacks required staff role");
        verifyNoInteractions(staffProfiles);
    }

    @Test
    @DisplayName("Missing or null identity is rejected")
    void getStaffProfile_missingIdentityRejected() {
        assertThatThrownBy(() -> service.getStaffProfile(null))
                .isInstanceOf(StaffAccessDeniedException.class);
    }

    @Test
    @DisplayName("Missing staff profile in Accounts DB throws StaffProfileNotFoundException (404)")
    void getStaffProfile_profileNotFound() {
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStaffProfile(courierIdentity))
                .isInstanceOf(StaffProfileNotFoundException.class)
                .hasMessageContaining("No staff business profile found");
    }

    @Test
    @DisplayName("Role mismatch between Keycloak token and staff profile throws 403")
    void getStaffProfile_roleMismatchRejected() {
        // Token has COURIER role, but DB profile is registered as ADMIN
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(adminStaffProfile));

        assertThatThrownBy(() -> service.getStaffProfile(courierIdentity))
                .isInstanceOf(StaffRoleMismatchException.class)
                .hasMessageContaining("do not match profile staff role");
    }

    @Test
    @DisplayName("INACTIVE staff profile throws 403 StaffProfileInactiveException")
    void getStaffProfile_inactiveProfileRejected() {
        courierStaffProfile.setStatus(StaffStatus.INACTIVE, Instant.now());
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierStaffProfile));

        assertThatThrownBy(() -> service.getStaffProfile(courierIdentity))
                .isInstanceOf(StaffProfileInactiveException.class)
                .hasMessageContaining("Operational access is denied");
    }

    @Test
    @DisplayName("SUSPENDED staff profile throws 403 StaffProfileInactiveException")
    void getStaffProfile_suspendedProfileRejected() {
        courierStaffProfile.setStatus(StaffStatus.SUSPENDED, Instant.now());
        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierStaffProfile));

        assertThatThrownBy(() -> service.getStaffProfile(courierIdentity))
                .isInstanceOf(StaffProfileInactiveException.class)
                .hasMessageContaining("Operational access is denied");
    }

    @Test
    @DisplayName("Maps contact information from AccountProfile when available")
    void getStaffProfile_mapsFromAccountProfile() throws Exception {
        AccountProfile ap = mock(AccountProfile.class);
        when(ap.getFullName()).thenReturn("Kamal Perera Official");
        when(ap.getEmail()).thenReturn("kamal.p@logistics.lk");
        when(ap.getPhoneNumber()).thenReturn("+94771234567");

        when(staffProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(courierStaffProfile));
        when(accountProfiles.findByKeycloakSub(courierSub)).thenReturn(Optional.of(ap));

        StaffProfileResponse response = service.getStaffProfile(courierIdentity);

        assertThat(response.displayName()).isEqualTo("Kamal Perera Official");
        assertThat(response.email()).isEqualTo("kamal.p@logistics.lk");
        assertThat(response.phoneNumber()).isEqualTo("+94771234567");
    }

    @Test
    @DisplayName("ADMIN can provision new staff profile successfully")
    void provisionStaff_asAdminSuccess() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                "sub-new-courier",
                "STF-2026-0099",
                StaffRole.COURIER,
                "FIELD_LOGISTICS",
                "ZONE-SOUTH-COLOMBO",
                null
        );

        when(staffProfiles.existsByKeycloakSub("sub-new-courier")).thenReturn(false);
        when(staffProfiles.existsByStaffId("STF-2026-0099")).thenReturn(false);
        when(staffProfiles.save(any(StaffProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        StaffProfileResponse response = service.provisionStaff(adminIdentity, command);

        assertThat(response).isNotNull();
        assertThat(response.staffId()).isEqualTo("STF-2026-0099");
        assertThat(response.keycloakSub()).isEqualTo("sub-new-courier");
        assertThat(response.role()).isEqualTo(StaffRole.COURIER);
        assertThat(response.department()).isEqualTo("FIELD_LOGISTICS");
        assertThat(response.assignedZone()).isEqualTo("ZONE-SOUTH-COLOMBO");
        assertThat(response.businessStatus()).isEqualTo(StaffStatus.ACTIVE);
    }

    @Test
    @DisplayName("Non-ADMIN identity cannot provision staff")
    void provisionStaff_nonAdminRejected() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                "sub-new-courier",
                "STF-2026-0099",
                StaffRole.COURIER,
                "FIELD_LOGISTICS",
                null,
                null
        );

        assertThatThrownBy(() -> service.provisionStaff(courierIdentity, command))
                .isInstanceOf(StaffAccessDeniedException.class)
                .hasMessageContaining("Staff provisioning requires authenticated ADMIN role");
        verifyNoInteractions(staffProfiles);
    }

    @Test
    @DisplayName("Provisioning rejects duplicate Keycloak subject")
    void provisionStaff_duplicateSubjectRejected() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                courierSub,
                "STF-2026-0099",
                StaffRole.COURIER,
                "FIELD_LOGISTICS",
                null,
                null
        );

        when(staffProfiles.existsByKeycloakSub(courierSub)).thenReturn(true);

        assertThatThrownBy(() -> service.provisionStaff(adminIdentity, command))
                .isInstanceOf(StaffProvisioningException.class)
                .hasMessageContaining("Staff profile already exists for subject");
    }

    @Test
    @DisplayName("Provisioning rejects duplicate staffId")
    void provisionStaff_duplicateStaffIdRejected() {
        ProvisionStaffCommand command = new ProvisionStaffCommand(
                "sub-new",
                "STF-2026-0042",
                StaffRole.COURIER,
                "FIELD_LOGISTICS",
                null,
                null
        );

        when(staffProfiles.existsByKeycloakSub("sub-new")).thenReturn(false);
        when(staffProfiles.existsByStaffId("STF-2026-0042")).thenReturn(true);

        assertThatThrownBy(() -> service.provisionStaff(adminIdentity, command))
                .isInstanceOf(StaffProvisioningException.class)
                .hasMessageContaining("Staff ID is already assigned");
    }
}
