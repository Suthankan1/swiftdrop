package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffProfile;
import com.swiftdrop.accountsservice.staff.StaffProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminReadServiceTest {
    private AccountProfileRepository accounts;
    private StaffProfileRepository staff;
    private AdminReadService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountProfileRepository.class);
        staff = mock(StaffProfileRepository.class);
        service = new AdminReadService(accounts, staff);
    }

    @Test
    void usersArePagedWithStableDefaultOrdering() {
        AccountProfile profile = mock(AccountProfile.class);
        when(profile.getId()).thenReturn(UUID.randomUUID());
        when(profile.getKeycloakSub()).thenReturn("user-sub");
        when(profile.getEmail()).thenReturn("user@swiftdrop.test");
        when(profile.getFullName()).thenReturn("User");
        when(profile.getCreatedAt()).thenReturn(Instant.parse("2026-10-27T00:00:00Z"));
        when(accounts.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(profile)));

        AdminPageResponse<AdminUserSummaryResponse> result = service.users(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().email()).isEqualTo("user@swiftdrop.test");
        verify(accounts).findAll(argThat(page -> page.getPageSize() == 20
                && page.getSort().getOrderFor("createdAt") != null
                && page.getSort().getOrderFor("id") != null));
    }

    @Test
    void staffFiltersAndMapsSafeBusinessFields() {
        StaffProfile profile = new StaffProfile(UUID.randomUUID(), UUID.randomUUID(), "staff-sub",
                "STF-1", StaffRole.COURIER, "FIELD", "ZONE-A", StaffStatus.SUSPENDED,
                Instant.now(), Instant.now());
        when(staff.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(profile)));

        AdminPageResponse<AdminStaffSummaryResponse> result = service.staff(0, 20,
                StaffRole.COURIER, StaffStatus.SUSPENDED, "ZONE-A");

        assertThat(result.content().getFirst().businessStatus()).isEqualTo(StaffStatus.SUSPENDED);
        assertThat(result.content().getFirst().staffId()).isEqualTo("STF-1");
        verify(staff).findAll(any(Specification.class), argThat((Pageable page) -> page.getPageSize() == 20
                && page.getSort().getOrderFor("createdAt") != null));
    }

    @Test
    void unknownStaffDetailIsNotFound() {
        UUID id = UUID.randomUUID();
        when(staff.findById(id)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.staff(id))
                .isInstanceOf(AdminStaffNotFoundException.class);
    }
}
