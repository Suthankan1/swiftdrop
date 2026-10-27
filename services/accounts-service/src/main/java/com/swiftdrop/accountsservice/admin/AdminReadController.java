package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminReadController {
    private static final int MAX_SIZE = 50;
    private final AdminReadService service;

    public AdminReadController(AdminReadService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public ResponseEntity<AdminPageResponse<AdminUserSummaryResponse>> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        return noStore(service.users(page, size));
    }

    @GetMapping("/merchants")
    public ResponseEntity<AdminPageResponse<AdminMerchantSummaryResponse>> merchants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) com.swiftdrop.accountsservice.merchant.MerchantStatus status) {
        validatePage(page, size);
        return noStore(service.merchants(page, size, status));
    }

    @GetMapping("/merchants/{merchantId}")
    public ResponseEntity<AdminMerchantDetailResponse> merchantDetail(@PathVariable UUID merchantId) {
        return noStore(service.merchantDetail(merchantId));
    }

    @GetMapping("/merchants/{merchantId}/audit")
    public ResponseEntity<java.util.List<AdminMerchantAuditResponse>> merchantAudit(@PathVariable UUID merchantId) {
        return noStore(service.merchantAudit(merchantId));
    }

    @GetMapping("/staff")
    public ResponseEntity<AdminPageResponse<AdminStaffSummaryResponse>> staff(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) StaffRole staffRole,
            @RequestParam(required = false) StaffStatus businessStatus,
            @RequestParam(required = false) String assignedZone) {
        validatePage(page, size);
        return noStore(service.staff(page, size, staffRole, businessStatus, assignedZone));
    }

    @GetMapping("/staff/{profileId}")
    public ResponseEntity<AdminStaffSummaryResponse> staff(@PathVariable UUID profileId) {
        return noStore(service.staff(profileId));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Page must be >= 0 and size must be between 1 and 50");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
