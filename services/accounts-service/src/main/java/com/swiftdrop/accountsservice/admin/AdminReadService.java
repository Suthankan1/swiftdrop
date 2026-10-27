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
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminReadService {
    private final AccountProfileRepository accounts;
    private final StaffProfileRepository staff;

    public AdminReadService(AccountProfileRepository accounts, StaffProfileRepository staff) {
        this.accounts = accounts;
        this.staff = staff;
    }

    public AdminPageResponse<AdminUserSummaryResponse> users(int page, int size) {
        Page<AccountProfile> result = accounts.findAll(pageable(page, size));
        return new AdminPageResponse<>(result.map(this::toUser).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
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
        return PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")
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
