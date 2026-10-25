package com.swiftdrop.accountsservice.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.accountsservice.merchant.Merchant;
import com.swiftdrop.accountsservice.merchant.MerchantRepository;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.merchant.credential.MerchantApiKeyValidator;
import com.swiftdrop.accountsservice.merchant.credential.MerchantKeyValidationResult;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffProfile;
import com.swiftdrop.accountsservice.staff.StaffProfileRepository;
import com.swiftdrop.accountsservice.staff.StaffRole;
import com.swiftdrop.accountsservice.staff.StaffStatus;
import com.swiftdrop.security.grpc.DelegatedActorContext;
import com.swiftdrop.security.grpc.SwiftDropGrpcContext;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Authoritative gRPC provider implementing AccountsInternalService.
 * Enforces fail-closed lifecycle status, caller authorization, anti-spoofing,
 * and minimal server-side profile field scoping.
 *
 * Implements Issue #109 (N10-B05).
 */
@Service
@Transactional(readOnly = true)
public class AccountsInternalServiceImpl extends AccountsInternalServiceGrpc.AccountsInternalServiceImplBase {

    public static final int MAX_BATCH_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<String> VALID_SCOPES = Set.of("", "MINIMAL", "CUSTOMER", "MERCHANT", "STAFF", "COURIER");

    private final AccountProfileRepository accountProfiles;
    private final MerchantRepository merchantRepository;
    private final StaffProfileRepository staffProfiles;
    private final MerchantApiKeyValidator apiKeyValidator;

    public AccountsInternalServiceImpl(AccountProfileRepository accountProfiles,
                                      MerchantRepository merchantRepository,
                                      StaffProfileRepository staffProfiles,
                                      MerchantApiKeyValidator apiKeyValidator) {
        this.accountProfiles = Objects.requireNonNull(accountProfiles, "AccountProfileRepository is required");
        this.merchantRepository = Objects.requireNonNull(merchantRepository, "MerchantRepository is required");
        this.staffProfiles = Objects.requireNonNull(staffProfiles, "StaffProfileRepository is required");
        this.apiKeyValidator = Objects.requireNonNull(apiKeyValidator, "MerchantApiKeyValidator is required");
    }

    @Override
    public void checkCourierEligibility(CourierEligibilityRequest request,
                                        StreamObserver<CourierEligibilityResponse> responseObserver) {
        String courierSub = request.getCourierSub();
        if (courierSub == null || courierSub.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("courier_sub is required").asRuntimeException());
            return;
        }

        String sanitizedSub = courierSub.trim();
        var actorOpt = SwiftDropGrpcContext.currentDelegatedActor();
        if (actorOpt.isPresent()) {
            DelegatedActorContext actor = actorOpt.get();
            if (actor.isCourier() && !actor.isAdmin() && !actor.isDispatcher()) {
                if (!sanitizedSub.equals(actor.actorSub())) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Courier cannot query another courier's eligibility")
                            .asRuntimeException());
                    return;
                }
            }
        }

        Optional<StaffProfile> profileOpt = staffProfiles.findByKeycloakSub(sanitizedSub);
        if (profileOpt.isEmpty()) {
            responseObserver.onNext(CourierEligibilityResponse.newBuilder()
                    .setCourierSub(sanitizedSub)
                    .setIsEligible(false)
                    .setIsActive(false)
                    .setRejectionReason("COURIER_NOT_FOUND")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        StaffProfile profile = profileOpt.get();
        if (profile.getRole() != StaffRole.COURIER) {
            responseObserver.onNext(CourierEligibilityResponse.newBuilder()
                    .setCourierSub(sanitizedSub)
                    .setIsEligible(false)
                    .setIsActive(false)
                    .setRejectionReason("ROLE_MISMATCH")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        if (!profile.getStatus().isActive()) {
            responseObserver.onNext(CourierEligibilityResponse.newBuilder()
                    .setCourierSub(sanitizedSub)
                    .setIsEligible(false)
                    .setIsActive(false)
                    .setRejectionReason("COURIER_" + profile.getStatus().name())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String fullName = "";
        Optional<AccountProfile> ap = accountProfiles.findByKeycloakSub(sanitizedSub);
        if (ap.isPresent() && ap.get().getFullName() != null && !ap.get().getFullName().isBlank()) {
            fullName = ap.get().getFullName();
        }

        responseObserver.onNext(CourierEligibilityResponse.newBuilder()
                .setCourierSub(sanitizedSub)
                .setIsEligible(true)
                .setIsActive(true)
                .setFullName(fullName)
                .setRejectionReason("ELIGIBLE")
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void checkAccountEligibility(AccountEligibilityRequest request,
                                        StreamObserver<AccountEligibilityResponse> responseObserver) {
        String subject = request.getSubject();
        if (subject == null || subject.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("subject is required").asRuntimeException());
            return;
        }

        String sanitizedSub = subject.trim();
        var actorOpt = SwiftDropGrpcContext.currentDelegatedActor();
        if (actorOpt.isPresent()) {
            DelegatedActorContext actor = actorOpt.get();
            if (!actor.isAdmin() && !actor.isDispatcher()) {
                if (actor.isCustomer() || actor.isCourier() || actor.isMerchant()) {
                    if (!sanitizedSub.equals(actor.actorSub())) {
                        responseObserver.onError(Status.PERMISSION_DENIED
                                .withDescription("Caller is not authorized to query eligibility for another account")
                                .asRuntimeException());
                        return;
                    }
                }
            }
        }

        String requiredRole = request.getRequiredRole() != null ? request.getRequiredRole().trim().toUpperCase() : "";
        String merchantId = request.getMerchantId() != null ? request.getMerchantId().trim() : "";

        // Case 1: Merchant eligibility check
        if ("MERCHANT".equals(requiredRole) || !merchantId.isBlank()) {
            Optional<Merchant> merchantOpt;
            if (!merchantId.isBlank()) {
                try {
                    merchantOpt = merchantRepository.findById(UUID.fromString(merchantId));
                } catch (IllegalArgumentException ex) {
                    responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid merchant_id format").asRuntimeException());
                    return;
                }
            } else {
                merchantOpt = merchantRepository.findByKeycloakSub(sanitizedSub);
            }

            if (merchantOpt.isEmpty()) {
                responseObserver.onNext(AccountEligibilityResponse.newBuilder()
                        .setSubject(sanitizedSub)
                        .setIsEligible(false)
                        .setIsActive(false)
                        .setStatus("NOT_FOUND")
                        .setRejectionReason("MERCHANT_NOT_FOUND")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            Merchant merchant = merchantOpt.get();
            boolean isApproved = merchant.getStatus() == MerchantStatus.APPROVED;
            responseObserver.onNext(AccountEligibilityResponse.newBuilder()
                    .setSubject(sanitizedSub)
                    .setMerchantId(merchant.getId().toString())
                    .setBusinessName(merchant.getBusinessName())
                    .setStatus(merchant.getStatus().name())
                    .setIsEligible(isApproved)
                    .setIsActive(isApproved)
                    .setRejectionReason(isApproved ? "ELIGIBLE" : "MERCHANT_" + merchant.getStatus().name())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // Case 2: Staff eligibility check (COURIER, DISPATCHER, ADMIN)
        if ("COURIER".equals(requiredRole) || "DISPATCHER".equals(requiredRole) || "ADMIN".equals(requiredRole)) {
            Optional<StaffProfile> staffOpt = staffProfiles.findByKeycloakSub(sanitizedSub);
            if (staffOpt.isEmpty()) {
                responseObserver.onNext(AccountEligibilityResponse.newBuilder()
                        .setSubject(sanitizedSub)
                        .setIsEligible(false)
                        .setIsActive(false)
                        .setStatus("NOT_FOUND")
                        .setRejectionReason("STAFF_NOT_FOUND")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            StaffProfile staff = staffOpt.get();
            if (!staff.getRole().name().equals(requiredRole)) {
                responseObserver.onNext(AccountEligibilityResponse.newBuilder()
                        .setSubject(sanitizedSub)
                        .setIsEligible(false)
                        .setIsActive(false)
                        .setStatus(staff.getStatus().name())
                        .setRejectionReason("ROLE_MISMATCH")
                        .build());
                responseObserver.onCompleted();
                return;
            }

            boolean isActive = staff.getStatus().isActive();
            responseObserver.onNext(AccountEligibilityResponse.newBuilder()
                    .setSubject(sanitizedSub)
                    .setStatus(staff.getStatus().name())
                    .setIsEligible(isActive)
                    .setIsActive(isActive)
                    .setRejectionReason(isActive ? "ELIGIBLE" : "STAFF_" + staff.getStatus().name())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // Case 3: Customer / General Account eligibility check
        Optional<AccountProfile> profileOpt = accountProfiles.findByKeycloakSub(sanitizedSub);
        if (profileOpt.isEmpty()) {
            responseObserver.onNext(AccountEligibilityResponse.newBuilder()
                    .setSubject(sanitizedSub)
                    .setIsEligible(false)
                    .setIsActive(false)
                    .setStatus("NOT_FOUND")
                    .setRejectionReason("ACCOUNT_NOT_FOUND")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        responseObserver.onNext(AccountEligibilityResponse.newBuilder()
                .setSubject(sanitizedSub)
                .setIsEligible(true)
                .setIsActive(true)
                .setStatus("ACTIVE")
                .setRejectionReason("ELIGIBLE")
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getScopedProfileSummary(ScopedProfileSummaryRequest request,
                                        StreamObserver<ScopedProfileSummaryResponse> responseObserver) {
        String subject = request.getSubject();
        if (subject == null || subject.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("subject is required").asRuntimeException());
            return;
        }

        String sanitizedSub = subject.trim();
        String scope = request.getScope() != null ? request.getScope().trim().toUpperCase() : "";

        if (!VALID_SCOPES.contains(scope)) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Unsupported scope: " + request.getScope()).asRuntimeException());
            return;
        }

        var actorOpt = SwiftDropGrpcContext.currentDelegatedActor();
        boolean allowEmail = false;

        if (actorOpt.isPresent()) {
            DelegatedActorContext actor = actorOpt.get();
            if (!actor.isAdmin() && !actor.isDispatcher()) {
                if (!sanitizedSub.equals(actor.actorSub())) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Caller is not authorized to query other accounts")
                            .asRuntimeException());
                    return;
                }
            }
            if (!"MINIMAL".equalsIgnoreCase(scope) && (actor.isAdmin() || sanitizedSub.equals(actor.actorSub()))) {
                allowEmail = true;
            }
        }

        Optional<AccountProfile> profileOpt = accountProfiles.findByKeycloakSub(sanitizedSub);
        Optional<Merchant> merchantOpt = merchantRepository.findByKeycloakSub(sanitizedSub);
        Optional<StaffProfile> staffOpt = staffProfiles.findByKeycloakSub(sanitizedSub);

        if (profileOpt.isEmpty() && merchantOpt.isEmpty() && staffOpt.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Profile not found for subject: " + sanitizedSub).asRuntimeException());
            return;
        }

        ScopedProfileSummaryResponse.Builder builder = ScopedProfileSummaryResponse.newBuilder()
                .setSubject(sanitizedSub);

        String fullName = "";
        String email = "";

        if (profileOpt.isPresent()) {
            AccountProfile ap = profileOpt.get();
            fullName = ap.getFullName() != null ? ap.getFullName() : "";
            if (allowEmail && ap.getEmail() != null) {
                email = ap.getEmail();
            }
            builder.setStatus("ACTIVE");
            builder.setRole("CUSTOMER");
        }

        if (merchantOpt.isPresent()) {
            Merchant m = merchantOpt.get();
            builder.setMerchantId(m.getId().toString());
            builder.setBusinessName(m.getBusinessName());
            builder.setStatus(m.getStatus().name());
            builder.setRole("MERCHANT");
        }

        if (staffOpt.isPresent()) {
            StaffProfile sp = staffOpt.get();
            builder.setStaffId(sp.getStaffId());
            builder.setDepartment(sp.getDepartment());
            builder.setAssignedZone(sp.getAssignedZone() != null ? sp.getAssignedZone() : "");
            builder.setStatus(sp.getStatus().name());
            builder.setRole(sp.getRole().name());
        }

        builder.setFullName(fullName);
        builder.setEmail(email);

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getBatchProfileSummaries(BatchProfileSummariesRequest request,
                                         StreamObserver<BatchProfileSummariesResponse> responseObserver) {
        List<String> subjects = request.getSubjectsList();
        if (subjects == null || subjects.isEmpty()) {
            responseObserver.onNext(BatchProfileSummariesResponse.getDefaultInstance());
            responseObserver.onCompleted();
            return;
        }

        if (subjects.size() > MAX_BATCH_SIZE) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Batch size exceeds maximum limit of " + MAX_BATCH_SIZE + ": " + subjects.size())
                    .asRuntimeException());
            return;
        }

        var actorOpt = SwiftDropGrpcContext.currentDelegatedActor();
        if (actorOpt.isPresent()) {
            DelegatedActorContext actor = actorOpt.get();
            if (!actor.isAdmin() && !actor.isDispatcher()) {
                responseObserver.onError(Status.PERMISSION_DENIED
                        .withDescription("Batch profile queries require DISPATCHER or ADMIN role")
                        .asRuntimeException());
                return;
            }
        }

        BatchProfileSummariesResponse.Builder builder = BatchProfileSummariesResponse.newBuilder();

        for (String sub : subjects) {
            if (sub == null || sub.isBlank()) {
                continue;
            }
            String sanitized = sub.trim();
            Optional<AccountProfile> profileOpt = accountProfiles.findByKeycloakSub(sanitized);
            Optional<Merchant> merchantOpt = merchantRepository.findByKeycloakSub(sanitized);
            Optional<StaffProfile> staffOpt = staffProfiles.findByKeycloakSub(sanitized);

            if (profileOpt.isEmpty() && merchantOpt.isEmpty() && staffOpt.isEmpty()) {
                continue;
            }

            ScopedProfileSummaryResponse.Builder item = ScopedProfileSummaryResponse.newBuilder().setSubject(sanitized);
            if (profileOpt.isPresent()) {
                item.setFullName(profileOpt.get().getFullName() != null ? profileOpt.get().getFullName() : "");
                item.setStatus("ACTIVE");
                item.setRole("CUSTOMER");
            }
            if (merchantOpt.isPresent()) {
                item.setMerchantId(merchantOpt.get().getId().toString());
                item.setBusinessName(merchantOpt.get().getBusinessName());
                item.setStatus(merchantOpt.get().getStatus().name());
                item.setRole("MERCHANT");
            }
            if (staffOpt.isPresent()) {
                item.setStaffId(staffOpt.get().getStaffId());
                item.setDepartment(staffOpt.get().getDepartment());
                item.setAssignedZone(staffOpt.get().getAssignedZone() != null ? staffOpt.get().getAssignedZone() : "");
                item.setStatus(staffOpt.get().getStatus().name());
                item.setRole(staffOpt.get().getRole().name());
            }
            builder.addItems(item.build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getAvailableCouriersSummary(AvailableCouriersSummaryRequest request,
                                            StreamObserver<AvailableCouriersSummaryResponse> responseObserver) {
        int page = request.getPage();
        if (page < 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("page must be >= 0").asRuntimeException());
            return;
        }
        int pageSize = request.getPageSize() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(request.getPageSize(), MAX_PAGE_SIZE);

        String zoneFilter = request.getZoneFilter() != null ? request.getZoneFilter().trim() : "";

        List<StaffProfile> couriers;
        if (!zoneFilter.isBlank()) {
            couriers = staffProfiles.findByRoleAndAssignedZone(StaffRole.COURIER, zoneFilter);
        } else {
            couriers = staffProfiles.findByRole(StaffRole.COURIER);
        }

        int totalCount = couriers.size();
        int fromIndex = Math.min(page * pageSize, totalCount);
        int toIndex = Math.min(fromIndex + pageSize, totalCount);
        List<StaffProfile> pageCouriers = couriers.subList(fromIndex, toIndex);

        AvailableCouriersSummaryResponse.Builder builder = AvailableCouriersSummaryResponse.newBuilder()
                .setTotalCount(totalCount)
                .setPage(page)
                .setPageSize(pageSize);

        for (StaffProfile cp : pageCouriers) {
            String fullName = "";
            Optional<AccountProfile> ap = accountProfiles.findByKeycloakSub(cp.getKeycloakSub());
            if (ap.isPresent() && ap.get().getFullName() != null && !ap.get().getFullName().isBlank()) {
                fullName = ap.get().getFullName();
            } else {
                fullName = cp.getStaffId();
            }

            boolean isActive = cp.getStatus().isActive();
            boolean isEligible = isActive;

            builder.addItems(CourierSummaryItem.newBuilder()
                    .setCourierSub(cp.getKeycloakSub())
                    .setStaffId(cp.getStaffId())
                    .setFullName(fullName)
                    .setAssignedZone(cp.getAssignedZone() != null ? cp.getAssignedZone() : "")
                    .setIsActive(isActive)
                    .setIsEligible(isEligible)
                    .build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getPendingMerchantsSummary(PendingMerchantsSummaryRequest request,
                                           StreamObserver<PendingMerchantsSummaryResponse> responseObserver) {
        int page = request.getPage();
        if (page < 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("page must be >= 0").asRuntimeException());
            return;
        }
        int pageSize = request.getPageSize() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(request.getPageSize(), MAX_PAGE_SIZE);

        String filterStr = request.getStatusFilter() == null || request.getStatusFilter().isBlank()
                ? "PENDING"
                : request.getStatusFilter().trim().toUpperCase();

        MerchantStatus status;
        try {
            status = MerchantStatus.valueOf(filterStr);
        } catch (IllegalArgumentException ex) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid status_filter: " + filterStr).asRuntimeException());
            return;
        }

        List<Merchant> merchants = merchantRepository.findByStatusOrderByCreatedAtAsc(status);
        int totalCount = merchants.size();

        int fromIndex = Math.min(page * pageSize, totalCount);
        int toIndex = Math.min(fromIndex + pageSize, totalCount);
        List<Merchant> pageItems = merchants.subList(fromIndex, toIndex);

        PendingMerchantsSummaryResponse.Builder builder = PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(totalCount)
                .setPage(page)
                .setPageSize(pageSize);

        for (Merchant m : pageItems) {
            Instant created = m.getCreatedAt();
            Timestamp ts = created != null ? Timestamp.newBuilder()
                    .setSeconds(created.getEpochSecond())
                    .setNanos(created.getNano())
                    .build() : Timestamp.getDefaultInstance();

            builder.addItems(PendingMerchantItem.newBuilder()
                    .setMerchantId(m.getId().toString())
                    .setBusinessName(m.getBusinessName())
                    .setRegistrationNumber(m.getRegistrationNumber())
                    .setStatus(m.getStatus().name())
                    .setSubmittedAt(ts)
                    .build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void validateMerchantKeyDigest(MerchantKeyDigestRequest request,
                                          StreamObserver<MerchantKeyDigestResponse> responseObserver) {
        MerchantKeyValidationResult result = apiKeyValidator.validateKeyDigest(request.getKeyPrefix(), request.getKeyDigest());
        MerchantKeyDigestResponse.Builder builder = MerchantKeyDigestResponse.newBuilder()
                .setIsValid(result.isValid());

        if (result.merchantId() != null) {
            builder.setMerchantId(result.merchantId().toString());
        }
        if (result.businessName() != null) {
            builder.setBusinessName(result.businessName());
        }
        if (result.status() != null) {
            builder.setStatus(result.status().name());
        }
        if (result.scopes() != null) {
            builder.addAllScopes(result.scopes());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void resolveRecipientContact(RecipientContactRequest request,
                                        StreamObserver<RecipientContactResponse> responseObserver) {
        String recipientSub = request.getRecipientSub();
        if (recipientSub == null || recipientSub.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("recipient_sub is required").asRuntimeException());
            return;
        }

        Optional<AccountProfile> profileOpt = accountProfiles.findByKeycloakSub(recipientSub.trim());
        if (profileOpt.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Recipient not found: " + recipientSub).asRuntimeException());
            return;
        }

        AccountProfile profile = profileOpt.get();
        responseObserver.onNext(RecipientContactResponse.newBuilder()
                .setRecipientSub(recipientSub)
                .setEmail(profile.getEmail() != null ? profile.getEmail() : "")
                .setPhoneNumber(profile.getPhoneNumber() != null ? profile.getPhoneNumber() : "")
                .setFullName(profile.getFullName() != null ? profile.getFullName() : "")
                .build());
        responseObserver.onCompleted();
    }
}
