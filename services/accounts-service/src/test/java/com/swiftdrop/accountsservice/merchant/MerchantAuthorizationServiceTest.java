package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MerchantAuthorizationServiceTest {

    private MerchantRepository merchantRepository;
    private MerchantAuthorizationService service;

    private final UUID merchantAId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID merchantBId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID accountProfileAId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID accountProfileBId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final String subjectA = "keycloak-sub-merchant-a";
    private final String subjectB = "keycloak-sub-merchant-b";

    private final TrustedIdentity merchantAIdentity = new TrustedIdentity(
            subjectA, "merchant-a@swiftdrop.test", "Merchant A Owner", List.of("MERCHANT")
    );
    private final TrustedIdentity merchantBIdentity = new TrustedIdentity(
            subjectB, "merchant-b@swiftdrop.test", "Merchant B Owner", List.of("MERCHANT")
    );
    private final TrustedIdentity customerIdentity = new TrustedIdentity(
            "keycloak-sub-customer", "cust@swiftdrop.test", "Customer Alice", List.of("CUSTOMER")
    );
    private final TrustedIdentity adminIdentity = new TrustedIdentity(
            "keycloak-sub-admin", "admin@swiftdrop.test", "Admin Bob", List.of("ADMIN")
    );

    private Merchant merchantA;
    private Merchant merchantB;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        service = new MerchantAuthorizationService(merchantRepository);

        merchantA = new Merchant(
                merchantAId,
                accountProfileAId,
                subjectA,
                "Store Direct LK",
                "PV-123456",
                MerchantStatus.APPROVED,
                Instant.parse("2026-10-15T10:00:00Z"),
                Instant.parse("2026-10-15T10:00:00Z")
        );

        merchantB = new Merchant(
                merchantBId,
                accountProfileBId,
                subjectB,
                "Lanka Logistics Express",
                "PV-654321",
                MerchantStatus.PENDING,
                Instant.parse("2026-10-16T12:00:00Z"),
                Instant.parse("2026-10-16T12:00:00Z")
        );

        when(merchantRepository.findById(merchantAId)).thenReturn(Optional.of(merchantA));
        when(merchantRepository.findById(merchantBId)).thenReturn(Optional.of(merchantB));
        when(merchantRepository.findByKeycloakSub(subjectA)).thenReturn(Optional.of(merchantA));
        when(merchantRepository.findByKeycloakSub(subjectB)).thenReturn(Optional.of(merchantB));
    }

    // ==========================================
    // A. Merchant Approval State Tests
    // ==========================================

    @Test
    @DisplayName("Existing approved merchant resolves to APPROVED state and isApproved is true")
    void existingApprovedMerchantResolvesCorrectly() {
        var status = service.getApprovalStatus(merchantAId);
        assertThat(status).contains(MerchantStatus.APPROVED);
        assertThat(service.isApproved(merchantAId)).isTrue();
        assertThat(service.isApproved(subjectA)).isTrue();
    }

    @Test
    @DisplayName("Pending merchant resolves to PENDING state and isApproved is false")
    void pendingMerchantIsNotConsideredApproved() {
        var status = service.getApprovalStatus(merchantBId);
        assertThat(status).contains(MerchantStatus.PENDING);
        assertThat(service.isApproved(merchantBId)).isFalse();
        assertThat(service.isApproved(subjectB)).isFalse();
    }

    @Test
    @DisplayName("Suspended merchant resolves to SUSPENDED state and isApproved is false")
    void suspendedMerchantIsNotApproved() {
        var suspendedId = UUID.randomUUID();
        var suspended = new Merchant(
                suspendedId, UUID.randomUUID(), "sub-suspended",
                "Suspended Shop", "PV-999999", MerchantStatus.SUSPENDED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findById(suspendedId)).thenReturn(Optional.of(suspended));

        assertThat(service.getApprovalStatus(suspendedId)).contains(MerchantStatus.SUSPENDED);
        assertThat(service.isApproved(suspendedId)).isFalse();
    }

    @Test
    @DisplayName("Rejected merchant resolves to REJECTED state and isApproved is false")
    void rejectedMerchantIsNotApproved() {
        var rejectedId = UUID.randomUUID();
        var rejected = new Merchant(
                rejectedId, UUID.randomUUID(), "sub-rejected",
                "Rejected Store", "PV-888888", MerchantStatus.REJECTED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findById(rejectedId)).thenReturn(Optional.of(rejected));

        assertThat(service.getApprovalStatus(rejectedId)).contains(MerchantStatus.REJECTED);
        assertThat(service.isApproved(rejectedId)).isFalse();
    }

    @Test
    @DisplayName("Nonexistent merchant safely resolves to empty and isApproved is false")
    void nonexistentMerchantResolvesEmpty() {
        var unknownId = UUID.randomUUID();
        when(merchantRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThat(service.getApprovalStatus(unknownId)).isEmpty();
        assertThat(service.getApprovalStatusBySubject("unknown-sub")).isEmpty();
        assertThat(service.isApproved(unknownId)).isFalse();
        assertThat(service.isApproved("unknown-sub")).isFalse();
    }

    @Test
    @DisplayName("Null input to approval methods fails safely without throwing unexpected errors")
    void nullInputsFailSafely() {
        assertThat(service.getApprovalStatus(null)).isEmpty();
        assertThat(service.getApprovalStatusBySubject(null)).isEmpty();
        assertThat(service.getApprovalStatusBySubject("  ")).isEmpty();
        assertThat(service.isApproved((UUID) null)).isFalse();
        assertThat(service.isApproved((String) null)).isFalse();
        assertThat(service.isApproved(" ")).isFalse();
    }

    // ==========================================
    // B. Tenant Authorization Tests
    // ==========================================

    @Test
    @DisplayName("Merchant A is authorized to access Merchant A tenant")
    void merchantACanAccessOwnTenant() {
        var decision = service.authorizeTenantAccess(merchantAIdentity, merchantAId);
        assertThat(decision.authorized()).isTrue();
        assertThat(decision.merchantId()).isEqualTo(merchantAId);
        assertThat(decision.status()).isEqualTo(MerchantStatus.APPROVED);
        assertThat(decision.denialReason()).isNull();

        // requireTenantAccess should not throw
        service.requireTenantAccess(merchantAIdentity, merchantAId);
    }

    @Test
    @DisplayName("Merchant A is denied access to Merchant B tenant (cross-tenant denial)")
    void merchantACannotAccessMerchantBTenant() {
        var decision = service.authorizeTenantAccess(merchantAIdentity, merchantBId);
        assertThat(decision.authorized()).isFalse();
        assertThat(decision.denialReason()).contains("Cross-tenant access forbidden");

        assertThatThrownBy(() -> service.requireTenantAccess(merchantAIdentity, merchantBId))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessageContaining("Cross-tenant access denied");
    }

    @Test
    @DisplayName("Customer cannot access unrelated merchant tenant resources")
    void customerCannotAccessMerchantTenant() {
        var decision = service.authorizeTenantAccess(customerIdentity, merchantAId);
        assertThat(decision.authorized()).isFalse();
        assertThat(decision.denialReason()).contains("Caller lacks MERCHANT or ADMIN role");

        assertThatThrownBy(() -> service.requireTenantAccess(customerIdentity, merchantAId))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessageContaining("Caller lacks MERCHANT role");
    }

    @Test
    @DisplayName("Customer owning a PENDING merchant tenant can inspect own pending status")
    void customerOwningPendingMerchantCanInspectOwnTenant() {
        var pendingMerchantId = UUID.randomUUID();
        var pendingMerchant = new Merchant(
                pendingMerchantId, UUID.randomUUID(), customerIdentity.subject(),
                "Customer Owned Shop", "PV-112233", MerchantStatus.PENDING,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findById(pendingMerchantId)).thenReturn(Optional.of(pendingMerchant));

        var decision = service.authorizeTenantAccess(customerIdentity, pendingMerchantId);
        assertThat(decision.authorized()).isTrue();
        assertThat(decision.merchantId()).isEqualTo(pendingMerchantId);
        assertThat(decision.status()).isEqualTo(MerchantStatus.PENDING);

        // requireTenantAccess should not throw
        service.requireTenantAccess(customerIdentity, pendingMerchantId);
    }

    @Test
    @DisplayName("Customer cannot access another owner's PENDING merchant tenant (cross-tenant denial)")
    void customerCannotAccessOtherPendingMerchantTenant() {
        // merchantB is in PENDING status, owned by subjectB ("keycloak-sub-merchant-b")
        // customerIdentity has subject "keycloak-sub-customer"
        var decision = service.authorizeTenantAccess(customerIdentity, merchantBId);
        assertThat(decision.authorized()).isFalse();
        assertThat(decision.denialReason()).contains("Caller lacks MERCHANT or ADMIN role");

        assertThatThrownBy(() -> service.requireTenantAccess(customerIdentity, merchantBId))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessageContaining("Caller lacks MERCHANT role");
    }

    @Test
    @DisplayName("Customer owning an APPROVED merchant tenant without MERCHANT role is denied")
    void customerOwningApprovedMerchantWithoutRoleIsDenied() {
        var approvedMerchantId = UUID.randomUUID();
        var approvedMerchant = new Merchant(
                approvedMerchantId, UUID.randomUUID(), customerIdentity.subject(),
                "Customer Store", "PV-445566", MerchantStatus.APPROVED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findById(approvedMerchantId)).thenReturn(Optional.of(approvedMerchant));

        var decision = service.authorizeTenantAccess(customerIdentity, approvedMerchantId);
        assertThat(decision.authorized()).isFalse();
        assertThat(decision.denialReason()).contains("Caller lacks MERCHANT or ADMIN role");

        assertThatThrownBy(() -> service.requireTenantAccess(customerIdentity, approvedMerchantId))
                .isInstanceOf(TenantAccessDeniedException.class)
                .hasMessageContaining("Caller lacks MERCHANT role");
    }

    @Test
    @DisplayName("Admin can access merchant tenant resources for oversight")
    void adminCanAccessMerchantTenant() {
        var decision = service.authorizeTenantAccess(adminIdentity, merchantAId);
        assertThat(decision.authorized()).isTrue();
        assertThat(decision.merchantId()).isEqualTo(merchantAId);

        service.requireTenantAccess(adminIdentity, merchantAId);
    }

    @Test
    @DisplayName("Nonexistent tenant produces 404 MerchantNotFoundException on requireTenantAccess")
    void nonexistentTenantThrowsNotFound() {
        var unknownId = UUID.randomUUID();
        when(merchantRepository.findById(unknownId)).thenReturn(Optional.empty());

        var decision = service.authorizeTenantAccess(merchantAIdentity, unknownId);
        assertThat(decision.authorized()).isFalse();
        assertThat(decision.denialReason()).contains("Merchant tenant not found");

        assertThatThrownBy(() -> service.requireTenantAccess(merchantAIdentity, unknownId))
                .isInstanceOf(MerchantNotFoundException.class)
                .hasMessageContaining("Merchant not found");
    }

    @Test
    @DisplayName("requireApprovedTenantAccess succeeds for approved merchant")
    void requireApprovedTenantAccessSucceedsForApprovedMerchant() {
        service.requireApprovedTenantAccess(merchantAIdentity, merchantAId);
    }

    @Test
    @DisplayName("requireApprovedTenantAccess throws MerchantNotApprovedException for PENDING merchant")
    void requireApprovedTenantAccessFailsForPendingMerchant() {
        assertThatThrownBy(() -> service.requireApprovedTenantAccess(merchantBIdentity, merchantBId))
                .isInstanceOf(MerchantNotApprovedException.class)
                .hasMessageContaining("requires APPROVED status")
                .matches(e -> ((MerchantNotApprovedException) e).getCurrentStatus() == MerchantStatus.PENDING);
    }

    @Test
    @DisplayName("requireApprovedTenantAccess throws MerchantNotApprovedException for SUSPENDED merchant")
    void requireApprovedTenantAccessFailsForSuspendedMerchant() {
        var suspendedId = UUID.randomUUID();
        var suspendedSub = "keycloak-sub-suspended";
        var suspended = new Merchant(
                suspendedId, UUID.randomUUID(), suspendedSub,
                "Suspended Shop", "PV-999999", MerchantStatus.SUSPENDED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findById(suspendedId)).thenReturn(Optional.of(suspended));

        var suspendedIdentity = new TrustedIdentity(
                suspendedSub, "suspended@swiftdrop.test", "Suspended Owner", List.of("MERCHANT")
        );

        assertThatThrownBy(() -> service.requireApprovedTenantAccess(suspendedIdentity, suspendedId))
                .isInstanceOf(MerchantNotApprovedException.class)
                .matches(e -> ((MerchantNotApprovedException) e).getCurrentStatus() == MerchantStatus.SUSPENDED);
    }

    @Test
    @DisplayName("Missing identity or null merchant ID fails closed")
    void missingIdentityFailsClosed() {
        var decisionNullId = service.authorizeTenantAccess(null, merchantAId);
        assertThat(decisionNullId.authorized()).isFalse();
        assertThat(decisionNullId.denialReason()).contains("Caller identity is missing");

        var decisionNullMerchant = service.authorizeTenantAccess(merchantAIdentity, null);
        assertThat(decisionNullMerchant.authorized()).isFalse();
        assertThat(decisionNullMerchant.denialReason()).contains("Target merchant ID is missing");

        assertThatThrownBy(() -> service.requireTenantAccess(null, merchantAId))
                .isInstanceOf(TenantAccessDeniedException.class);

        assertThatThrownBy(() -> service.requireTenantAccess(merchantAIdentity, null))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    @DisplayName("resolveMerchantForIdentity returns MerchantRecord for bound identity")
    void resolveMerchantForIdentityReturnsRecord() {
        var recordOpt = service.resolveMerchantForIdentity(merchantAIdentity);
        assertThat(recordOpt).isPresent();
        var record = recordOpt.get();
        assertThat(record.id()).isEqualTo(merchantAId);
        assertThat(record.businessName()).isEqualTo("Store Direct LK");
        assertThat(record.registrationNumber()).isEqualTo("PV-123456");
        assertThat(record.status()).isEqualTo(MerchantStatus.APPROVED);
        assertThat(record.keycloakSub()).isEqualTo(subjectA);
    }

    @Test
    @DisplayName("resolveMerchantForIdentity returns empty for unbound identity")
    void resolveMerchantForIdentityReturnsEmptyForUnbound() {
        when(merchantRepository.findByKeycloakSub("unbound-sub")).thenReturn(Optional.empty());
        var unboundIdentity = new TrustedIdentity("unbound-sub", "u@swiftdrop.test", "Unbound", List.of("MERCHANT"));
        assertThat(service.resolveMerchantForIdentity(unboundIdentity)).isEmpty();
    }
}
