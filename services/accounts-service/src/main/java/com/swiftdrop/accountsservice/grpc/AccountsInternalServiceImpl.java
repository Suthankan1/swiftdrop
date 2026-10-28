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
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Authoritative gRPC provider implementing AccountsInternalService.
 * Enforces fail-closed lifecycle status, caller authorization, anti-spoofing,
 * minimal server-side profile field scoping, and permitted notification contact lookup.
 *
 * Implements Issue #109 (N10-B05) and Issue #110 (N10-B06).
 */
@Service
@Transactional(readOnly = true)
public class AccountsInternalServiceImpl extends AccountsInternalServiceGrpc.AccountsInternalServiceImplBase {

    public static final int MAX_BATCH_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final Set<String> VALID_SCOPES = Set.of("", "MINIMAL", "CUSTOMER", "MERCHANT", "STAFF", "COURIER");
    private static final Set<String> VALID_NOTIFICATION_PURPOSES = Set.of(
            "", "TRANSACTIONAL", "PARCEL_STATUS_UPDATE", "DELIVERY_NOTIFICATION", "ACCOUNT_ALERT", "NOTIFICATION"
    );
    private static final Set<String> VALID_NOTIFICATION_CHANNELS = Set.of(
            "", "ANY", "EMAIL", "SMS", "SMS_MOCK"
    );

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
        // 1. Calling service verification (defense-in-depth)
        var callingServiceOpt = SwiftDropGrpcContext.currentCallingService();
        if (callingServiceOpt.isPresent() && callingServiceOpt.get() != SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Calling service is not authorized to invoke GetPendingMerchantsSummary: " + callingServiceOpt.get().getServiceName())
                    .asRuntimeException());
            return;
        }

        // 2. Delegated human actor verification: must be present and must hold ADMIN role
        var actorOpt = SwiftDropGrpcContext.currentDelegatedActor();
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin()) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("GetPendingMerchantsSummary requires delegated ADMIN actor context")
                    .asRuntimeException());
            return;
        }

        // 3. Validate pagination parameters
        int page = request.getPage();
        if (page < 0) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("page must be >= 0").asRuntimeException());
            return;
        }
        if (request.getPageSize() < 0 || request.getPageSize() > MAX_PAGE_SIZE) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("page_size must be between 1 and " + MAX_PAGE_SIZE)
                    .asRuntimeException());
            return;
        }
        int pageSize = request.getPageSize() <= 0 ? DEFAULT_PAGE_SIZE : request.getPageSize();

        // 4. Validate status filter
        String filterStr = request.getStatusFilter() == null || request.getStatusFilter().isBlank()
                ? "PENDING"
                : request.getStatusFilter().trim().toUpperCase();

        MerchantStatus status;
        try {
            status = MerchantStatus.valueOf(filterStr);
        } catch (IllegalArgumentException ex) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Unsupported status_filter: " + filterStr).asRuntimeException());
            return;
        }

        // 5. Database bounded pagination with deterministic sorting
        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.ASC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "id")));
        Page<Merchant> pageResult = merchantRepository.findByStatus(status, pageable);
        int totalCount = (int) Math.min(pageResult.getTotalElements(), Integer.MAX_VALUE);

        PendingMerchantsSummaryResponse.Builder builder = PendingMerchantsSummaryResponse.newBuilder()
                .setTotalPendingCount(totalCount)
                .setPage(page)
                .setPageSize(pageSize);

        for (Merchant m : pageResult.getContent()) {
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
        // 1. Caller machine identity verification (defense-in-depth)
        var callingServiceOpt = SwiftDropGrpcContext.currentCallingService();
        if (callingServiceOpt.isPresent() && callingServiceOpt.get() != SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Calling service is not authorized to resolve recipient contact: " + callingServiceOpt.get().getServiceName())
                    .asRuntimeException());
            return;
        }

        // 2. Reject human actor delegation on service-only background RPC
        var actorOpt = SwiftDropGrpcContext.currentDelegatedActor();
        if (actorOpt.isPresent()) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("Actor delegation is forbidden on service-only operation ResolveRecipientContact")
                    .asRuntimeException());
            return;
        }

        // 3. Validate recipient identifier
        String recipientSub = request.getRecipientSub();
        if (recipientSub == null || recipientSub.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("recipient_sub is required").asRuntimeException());
            return;
        }
        String sanitizedSub = recipientSub.trim();
        if (sanitizedSub.length() > 255 || sanitizedSub.contains("\n") || sanitizedSub.contains("\r")) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid recipient_sub format").asRuntimeException());
            return;
        }

        // 4. Validate notification purpose / scope
        String rawPurpose = request.getPurpose() != null ? request.getPurpose().trim().toUpperCase() : "";
        if (!VALID_NOTIFICATION_PURPOSES.contains(rawPurpose)) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Unsupported or unauthorized notification purpose: " + request.getPurpose())
                    .asRuntimeException());
            return;
        }

        // 5. Validate notification contact channel
        String rawChannel = request.getChannel() != null ? request.getChannel().trim().toUpperCase() : "";
        if (!VALID_NOTIFICATION_CHANNELS.contains(rawChannel)) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Unsupported notification channel: " + request.getChannel())
                    .asRuntimeException());
            return;
        }
        String channel = rawChannel.isBlank() ? "ANY" : rawChannel;

        // 6. Check if recipient is a Staff profile (e.g. Courier or Staff)
        Optional<StaffProfile> staffOpt = staffProfiles.findByKeycloakSub(sanitizedSub);
        if (staffOpt.isPresent()) {
            StaffProfile staff = staffOpt.get();
            if (!staff.getStatus().isActive()) {
                // Disabled staff recipient: omit contact details and return fail-closed response
                responseObserver.onNext(RecipientContactResponse.newBuilder()
                        .setRecipientSub(sanitizedSub)
                        .setIsEligible(false)
                        .setEmailPermitted(false)
                        .setSmsPermitted(false)
                        .setStatus("DISABLED")
                        .setRejectionReason("STAFF_" + staff.getStatus().name())
                        .build());
                responseObserver.onCompleted();
                return;
            }
        }

        // 7. Check if recipient is a Merchant
        Optional<Merchant> merchantOpt = merchantRepository.findByKeycloakSub(sanitizedSub);
        if (merchantOpt.isPresent()) {
            Merchant merchant = merchantOpt.get();
            if (merchant.getStatus() != MerchantStatus.APPROVED) {
                // Ineligible/disabled merchant: fail closed
                responseObserver.onNext(RecipientContactResponse.newBuilder()
                        .setRecipientSub(sanitizedSub)
                        .setIsEligible(false)
                        .setEmailPermitted(false)
                        .setSmsPermitted(false)
                        .setStatus("DISABLED")
                        .setRejectionReason("MERCHANT_" + merchant.getStatus().name())
                        .build());
                responseObserver.onCompleted();
                return;
            }
        }

        // 8. Retrieve primary account profile
        Optional<AccountProfile> profileOpt = accountProfiles.findByKeycloakSub(sanitizedSub);
        if (profileOpt.isEmpty()) {
            // Check if sanitizedSub was a merchant ID UUID
            if (merchantOpt.isEmpty() && staffOpt.isEmpty()) {
                try {
                    UUID maybeMerchantId = UUID.fromString(sanitizedSub);
                    merchantOpt = merchantRepository.findById(maybeMerchantId);
                    if (merchantOpt.isPresent()) {
                        Merchant m = merchantOpt.get();
                        if (m.getStatus() != MerchantStatus.APPROVED) {
                            responseObserver.onNext(RecipientContactResponse.newBuilder()
                                    .setRecipientSub(sanitizedSub)
                                    .setIsEligible(false)
                                    .setEmailPermitted(false)
                                    .setSmsPermitted(false)
                                    .setStatus("DISABLED")
                                    .setRejectionReason("MERCHANT_" + m.getStatus().name())
                                    .build());
                            responseObserver.onCompleted();
                            return;
                        }
                        profileOpt = accountProfiles.findByKeycloakSub(m.getKeycloakSub());
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a UUID, continue
                }
            }
        }

        if (profileOpt.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Recipient not found: " + sanitizedSub)
                    .asRuntimeException());
            return;
        }

        AccountProfile profile = profileOpt.get();
        String email = profile.getEmail() != null ? profile.getEmail().trim() : "";
        String phone = profile.getPhoneNumber() != null ? profile.getPhoneNumber().trim() : "";
        String fullName = profile.getFullName() != null ? profile.getFullName().trim() : "";

        boolean hasEmail = !email.isBlank();
        boolean hasSms = !phone.isBlank();

        // 9. Check channel requirements and availability
        boolean emailPermitted = false;
        boolean smsPermitted = false;
        boolean isEligible = false;
        String status = "ACTIVE";
        String rejectionReason = "ELIGIBLE";

        if ("EMAIL".equals(channel)) {
            if (hasEmail) {
                emailPermitted = true;
                isEligible = true;
            } else {
                status = "MISSING_CONTACT";
                rejectionReason = "MISSING_EMAIL_CONTACT";
            }
        } else if ("SMS".equals(channel) || "SMS_MOCK".equals(channel)) {
            if (hasSms) {
                smsPermitted = true;
                isEligible = true;
            } else {
                status = "MISSING_CONTACT";
                rejectionReason = "MISSING_PHONE_CONTACT";
            }
        } else { // "ANY"
            if (hasEmail || hasSms) {
                emailPermitted = hasEmail;
                smsPermitted = hasSms;
                isEligible = true;
            } else {
                status = "MISSING_CONTACT";
                rejectionReason = "NO_CONTACT_METHODS";
            }
        }

        // 10. Channel projection (exclude fields not permitted for this notification channel)
        String exposedEmail = emailPermitted ? email : "";
        String exposedPhone = smsPermitted ? phone : "";

        responseObserver.onNext(RecipientContactResponse.newBuilder()
                .setRecipientSub(sanitizedSub)
                .setEmail(exposedEmail)
                .setPhoneNumber(exposedPhone)
                .setFullName(fullName)
                .setIsEligible(isEligible)
                .setEmailPermitted(emailPermitted)
                .setSmsPermitted(smsPermitted)
                .setStatus(status)
                .setRejectionReason(rejectionReason)
                .build());
        responseObserver.onCompleted();
    }
}
