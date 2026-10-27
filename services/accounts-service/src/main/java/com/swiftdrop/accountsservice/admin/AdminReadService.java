package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.staff.StaffProfile;
import com.swiftdrop.accountsservice.staff.StaffProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantBusinessAuditRepository;
import com.swiftdrop.accountsservice.merchant.MerchantNotFoundException;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminReadService {
    private final AccountProfileRepository accounts;
    private final StaffProfileRepository staff;
    private final MerchantRepository merchants;
    private final MerchantBusinessAuditRepository merchantAudits;

    public AdminReadService(AccountProfileRepository accounts, StaffProfileRepository staff) {
        this(accounts, staff, null, null);
    }

    @Autowired
    public AdminReadService(AccountProfileRepository accounts,
                            StaffProfileRepository staff,
                            @Autowired(required = false) MerchantRepository merchants,
                            @Autowired(required = false) MerchantBusinessAuditRepository merchantAudits) {
        this.accounts = accounts;
        this.staff = staff;
        this.merchants = merchants;
        this.merchantAudits = merchantAudits;
    }

    public AdminPageResponse<AdminUserSummaryResponse> users(int page, int size) {
        Page<AccountProfile> result = accounts.findAll(pageable(page, size));
        return new AdminPageResponse<>(result.map(this::toUser).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    public AdminPageResponse<AdminMerchantSummaryResponse> merchants(int page, int size, MerchantStatus status) {
        if (merchants == null) {
            throw new IllegalStateException("Merchant repository is not configured");
        }
        Page<Merchant> result = (status != null)
                ? merchants.findByStatus(status, pageable(page, size))
                : merchants.findAll(pageable(page, size));
        return new AdminPageResponse<>(result.map(AdminMerchantSummaryResponse::fromEntity).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    public AdminMerchantDetailResponse merchantDetail(UUID merchantId) {
        if (merchants == null) {
            throw new IllegalStateException("Merchant repository is not configured");
        }
        Merchant merchant = merchants.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));
        List<AdminMerchantAuditResponse> audits = merchantAudits != null
                ? merchantAudits.findByMerchantIdOrderByOccurredAtDesc(merchantId).stream()
                        .map(AdminMerchantAuditResponse::fromEntity)
                        .toList()
                : Collections.emptyList();
        return AdminMerchantDetailResponse.fromEntity(merchant, audits);
    }

    public List<AdminMerchantAuditResponse> merchantAudit(UUID merchantId) {
        if (merchants == null) {
            throw new IllegalStateException("Merchant repository is not configured");
        }
        if (merchants.findById(merchantId).isEmpty()) {
            throw new MerchantNotFoundException("Merchant not found: " + merchantId);
        }
        return merchantAudits != null
                ? merchantAudits.findByMerchantIdOrderByOccurredAtDesc(merchantId).stream()
                        .map(AdminMerchantAuditResponse::fromEntity)
                        .toList()
                : Collections.emptyList();
    }

    public AdminPageResponse<AdminStaffSummaryResponse> staff(int page, int size,
                                                               StaffRole role, StaffStatus status,
                                                               String assignedZone) {
        Specification<StaffProfile> specification = (root, query, cb) -> cb.and(
                role == null ? cb.conjunction() : cb.equal(root.get("role"), role),
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status),
                assignedZone == null || assignedZone.isBlank()
                        ? cb.conjunction() : cb.equal(root.get("assignedZone"), assignedZone));
        Page<StaffProfile> result = staff.findAll(specification, pageable(page, size));
        return new AdminPageResponse<>(result.map(this::toStaff).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    public AdminStaffSummaryResponse staff(UUID profileId) {
        return staff.findById(profileId).map(this::toStaff)
                .orElseThrow(() -> new AdminStaffNotFoundException(profileId));
    }

    private Pageable pageable(int page, int size) {
        int boundedPage = Math.max(0, page);
        int boundedSize = (size <= 0) ? 20 : Math.min(50, size);
        return PageRequest.of(boundedPage, boundedSize, Sort.by(Sort.Direction.ASC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "id")));
    }

    private AdminUserSummaryResponse toUser(AccountProfile profile) {
        return new AdminUserSummaryResponse(profile.getId(), profile.getKeycloakSub(), profile.getEmail(),
                profile.getFullName(), profile.getCreatedAt());
    }

    private AdminStaffSummaryResponse toStaff(StaffProfile profile) {
        return new AdminStaffSummaryResponse(profile.getId(), profile.getAccountProfileId(), profile.getStaffId(),
                profile.getKeycloakSub(), profile.getRole(), profile.getDepartment(), profile.getAssignedZone(),
                profile.getStatus(), profile.getCreatedAt(), profile.getUpdatedAt());
    }
}
