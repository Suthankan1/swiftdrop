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

import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantBusinessAudit;
import com.swiftdrop.accountsservice.merchant.MerchantBusinessAuditRepository;
import com.swiftdrop.accountsservice.merchant.MerchantNotFoundException;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;

class AdminReadServiceTest {
    private AccountProfileRepository accounts;
    private StaffProfileRepository staff;
    private MerchantRepository merchants;
    private MerchantBusinessAuditRepository businessAudits;
    private AdminReadService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountProfileRepository.class);
        staff = mock(StaffProfileRepository.class);
        merchants = mock(MerchantRepository.class);
        businessAudits = mock(MerchantBusinessAuditRepository.class);
        service = new AdminReadService(accounts, staff, merchants, businessAudits);
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

    @Test
    void merchantsArePagedAndFilteredByStatus() {
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = new Merchant(
                merchantId, UUID.randomUUID(), "sub-1",
                "Store Direct LK", "PV-123456", MerchantStatus.PENDING,
                Instant.now(), Instant.now()
        );
        when(merchants.findByStatus(eq(MerchantStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(merchant)));

        AdminPageResponse<AdminMerchantSummaryResponse> result = service.merchants(0, 100, MerchantStatus.PENDING);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().businessName()).isEqualTo("Store Direct LK");
        assertThat(result.content().getFirst().status()).isEqualTo(MerchantStatus.PENDING);
        assertThat(result.content().getFirst().reviewToken()).isNotBlank();
        // Clamped page size to 50
        verify(merchants).findByStatus(eq(MerchantStatus.PENDING), argThat(page -> page.getPageSize() == 50));
    }

    @Test
    void merchantDetailBuildsActionListAndAudits() {
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = new Merchant(
                merchantId, UUID.randomUUID(), "sub-1",
                "Store Direct LK", "PV-123456", MerchantStatus.PENDING,
                Instant.now(), Instant.now()
        );
        when(merchants.findById(merchantId)).thenReturn(Optional.of(merchant));

        MerchantBusinessAudit audit = new MerchantBusinessAudit(
                UUID.randomUUID(), merchantId, "admin-sub-1", "APPROVE", "PENDING", "APPROVED",
                null, Instant.now(), null, 0L, "SUCCESS", "SUCCESS", null
        );
        when(businessAudits.findByMerchantIdOrderByOccurredAtDesc(merchantId)).thenReturn(List.of(audit));

        AdminMerchantDetailResponse detail = service.merchantDetail(merchantId);

        assertThat(detail.id()).isEqualTo(merchantId);
        assertThat(detail.businessName()).isEqualTo("Store Direct LK");
        assertThat(detail.actions()).isNotEmpty();
        assertThat(detail.actions().getFirst().action()).isEqualTo("approve");
        assertThat(detail.audit()).hasSize(1);
        assertThat(detail.audit().getFirst().action()).isEqualTo("APPROVE");
    }

    @Test
    void merchantDetailThrowsNotFoundForMissingMerchant() {
        UUID missingId = UUID.randomUUID();
        when(merchants.findById(missingId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.merchantDetail(missingId))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void merchantAuditReturnsMappedHistory() {
        UUID merchantId = UUID.randomUUID();
        Merchant merchant = new Merchant(
                merchantId, UUID.randomUUID(), "sub-1",
                "Store Direct LK", "PV-123456", MerchantStatus.APPROVED,
                Instant.now(), Instant.now()
        );
        when(merchants.findById(merchantId)).thenReturn(Optional.of(merchant));

        MerchantBusinessAudit audit = new MerchantBusinessAudit(
                UUID.randomUUID(), merchantId, "admin-sub-1", "SUSPEND", "APPROVED", "SUSPENDED",
                "Suspended for fraud", Instant.now(), null, 0L, "SUCCESS", "SUCCESS", null
        );
        when(businessAudits.findByMerchantIdOrderByOccurredAtDesc(merchantId)).thenReturn(List.of(audit));

        List<AdminMerchantAuditResponse> audits = service.merchantAudit(merchantId);

        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().action()).isEqualTo("SUSPEND");
        assertThat(audits.getFirst().reason()).isEqualTo("Suspended for fraud");
    }
}
