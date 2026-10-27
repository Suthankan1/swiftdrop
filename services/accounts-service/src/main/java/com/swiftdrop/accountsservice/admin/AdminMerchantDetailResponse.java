package com.swiftdrop.accountsservice.admin;

import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Detailed projection for administrative review of a single merchant tenant.
 * Conforms to the frontend MerchantReviewDetail presentation model.
 */
public record AdminMerchantDetailResponse(
        UUID id,
        String businessName,
        String registrationNumber,
        MerchantStatus status,
        Instant submittedAt,
        String reviewToken,
        List<AdminActionOption> actions,
        List<AdminMerchantAuditEntry> audit
) {
    public record AdminActionOption(
            String action,
            AdminReasonConfig reason
    ) {}

    public record AdminReasonConfig(
            boolean required,
            int maxLength
    ) {}

    public record AdminMerchantAuditEntry(
            String id,
            String actor,
            String action,
            String occurredAt,
            String reason
    ) {}

    public static AdminMerchantDetailResponse fromEntity(Merchant entity, List<AdminMerchantAuditResponse> audits) {
        String token = String.valueOf(entity.getVersion() != null ? entity.getVersion() : 0L);

        List<AdminActionOption> actions = new ArrayList<>();
        if (entity.getStatus() == MerchantStatus.PENDING) {
            actions.add(new AdminActionOption("approve", null));
        } else if (entity.getStatus() == MerchantStatus.APPROVED) {
            actions.add(new AdminActionOption("suspend", new AdminReasonConfig(true, 500)));
        }

        List<AdminMerchantAuditEntry> auditEntries = audits != null
                ? audits.stream()
                        .map(a -> new AdminMerchantAuditEntry(
                                a.id().toString(),
                                a.actor(),
                                a.action(),
                                a.occurredAt().toString(),
                                a.reason()
                        ))
                        .toList()
                : Collections.emptyList();

        return new AdminMerchantDetailResponse(
                entity.getId(),
                entity.getBusinessName(),
                entity.getRegistrationNumber(),
                entity.getStatus(),
                entity.getCreatedAt(),
                token,
                Collections.unmodifiableList(actions),
                auditEntries
        );
    }
}
