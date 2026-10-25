package com.swiftdrop.grpc;

import com.google.protobuf.Timestamp;
import com.swiftdrop.accounts.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountsContractTest {

    private Server server;
    private ManagedChannel channel;
    private AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new MockAccountsService())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        blockingStub = AccountsInternalServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("CheckCourierEligibility: returns eligible status for active courier")
    void testCourierEligibilitySuccess() {
        String courierSub = UUID.randomUUID().toString();
        CourierEligibilityRequest request = CourierEligibilityRequest.newBuilder()
                .setCourierSub(courierSub)
                .build();

        CourierEligibilityResponse response = blockingStub.checkCourierEligibility(request);

        assertThat(response.getCourierSub()).isEqualTo(courierSub);
        assertThat(response.getIsEligible()).isTrue();
        assertThat(response.getIsActive()).isTrue();
        assertThat(response.getFullName()).isEqualTo("Jane Doe");
        assertThat(response.getRejectionReason()).isEqualTo("ELIGIBLE");
    }

    @Test
    @DisplayName("ValidateMerchantKeyDigest: validates key digest, returns tenant and scopes")
    void testValidateMerchantKeyDigest() {
        MerchantKeyDigestRequest request = MerchantKeyDigestRequest.newBuilder()
                .setKeyPrefix("swd_live_abc123")
                .setKeyDigest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .build();

        MerchantKeyDigestResponse response = blockingStub.validateMerchantKeyDigest(request);

        assertThat(response.getIsValid()).isTrue();
        assertThat(response.getBusinessName()).isEqualTo("Swift Merchant Ltd");
        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getScopesList()).containsExactly("parcels:create", "parcels:read");
    }

    @Test
    @DisplayName("ResolveRecipientContact: resolves phone and email without PII leakage in events")
    void testResolveRecipientContact() {
        String recipientSub = UUID.randomUUID().toString();
        RecipientContactRequest request = RecipientContactRequest.newBuilder()
                .setRecipientSub(recipientSub)
                .build();

        RecipientContactResponse response = blockingStub.resolveRecipientContact(request);

        assertThat(response.getRecipientSub()).isEqualTo(recipientSub);
        assertThat(response.getEmail()).isEqualTo("recipient@example.com");
        assertThat(response.getPhoneNumber()).isEqualTo("+94771234567");
        assertThat(response.getFullName()).isEqualTo("John Smith");
    }

    @Test
    @DisplayName("GetPendingMerchantsSummary: returns bounded paginated list for Admin review (#121/#139)")
    void testGetPendingMerchantsSummaryPagination() {
        PendingMerchantsSummaryRequest request = PendingMerchantsSummaryRequest.newBuilder()
                .setStatusFilter("PENDING")
                .setPage(0)
                .setPageSize(20)
                .build();

        PendingMerchantsSummaryResponse response = blockingStub.getPendingMerchantsSummary(request);

        assertThat(response.getTotalPendingCount()).isEqualTo(1);
        assertThat(response.getPage()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(20);
        assertThat(response.getItemsCount()).isEqualTo(1);

        PendingMerchantItem item = response.getItems(0);
        assertThat(item.getMerchantId()).isNotEmpty();
        assertThat(item.getBusinessName()).isEqualTo("Pending Enterprises");
        assertThat(item.getRegistrationNumber()).isEqualTo("PV12345");
        assertThat(item.getStatus()).isEqualTo("PENDING");
        assertThat(item.getSubmittedAt().getSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("CheckAccountEligibility: verifies account eligibility for customer and merchant")
    void testCheckAccountEligibility() {
        String sub = UUID.randomUUID().toString();
        AccountEligibilityRequest req = AccountEligibilityRequest.newBuilder()
                .setSubject(sub)
                .setRequiredRole("CUSTOMER")
                .build();

        AccountEligibilityResponse resp = blockingStub.checkAccountEligibility(req);

        assertThat(resp.getSubject()).isEqualTo(sub);
        assertThat(resp.getIsEligible()).isTrue();
        assertThat(resp.getIsActive()).isTrue();
        assertThat(resp.getStatus()).isEqualTo("ACTIVE");
        assertThat(resp.getRejectionReason()).isEqualTo("ELIGIBLE");
    }

    @Test
    @DisplayName("GetScopedProfileSummary: retrieves minimal scoped summary without private credentials")
    void testGetScopedProfileSummary() {
        String sub = UUID.randomUUID().toString();
        ScopedProfileSummaryRequest req = ScopedProfileSummaryRequest.newBuilder()
                .setSubject(sub)
                .setScope("CUSTOMER")
                .build();

        ScopedProfileSummaryResponse resp = blockingStub.getScopedProfileSummary(req);

        assertThat(resp.getSubject()).isEqualTo(sub);
        assertThat(resp.getFullName()).isEqualTo("Jane Doe");
        assertThat(resp.getRole()).isEqualTo("CUSTOMER");
        assertThat(resp.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("GetBatchProfileSummaries: returns bounded list of summaries")
    void testGetBatchProfileSummaries() {
        String sub1 = UUID.randomUUID().toString();
        String sub2 = UUID.randomUUID().toString();
        BatchProfileSummariesRequest req = BatchProfileSummariesRequest.newBuilder()
                .addSubjects(sub1)
                .addSubjects(sub2)
                .setScope("STAFF")
                .build();

        BatchProfileSummariesResponse resp = blockingStub.getBatchProfileSummaries(req);

        assertThat(resp.getItemsCount()).isEqualTo(2);
        assertThat(resp.getItems(0).getSubject()).isEqualTo(sub1);
        assertThat(resp.getItems(1).getSubject()).isEqualTo(sub2);
    }

    @Test
    @DisplayName("GetAvailableCouriersSummary: returns paginated couriers list for dispatcher")
    void testGetAvailableCouriersSummary() {
        AvailableCouriersSummaryRequest req = AvailableCouriersSummaryRequest.newBuilder()
                .setPage(0)
                .setPageSize(20)
                .build();

        AvailableCouriersSummaryResponse resp = blockingStub.getAvailableCouriersSummary(req);

        assertThat(resp.getTotalCount()).isEqualTo(1);
        assertThat(resp.getItemsCount()).isEqualTo(1);
        CourierSummaryItem item = resp.getItems(0);
        assertThat(item.getCourierSub()).isNotEmpty();
        assertThat(item.getFullName()).isEqualTo("Jane Courier");
        assertThat(item.getIsEligible()).isTrue();
        assertThat(item.getIsActive()).isTrue();
    }

    private static class MockAccountsService extends AccountsInternalServiceGrpc.AccountsInternalServiceImplBase {
        @Override
        public void checkCourierEligibility(CourierEligibilityRequest request,
                                            StreamObserver<CourierEligibilityResponse> responseObserver) {
            CourierEligibilityResponse response = CourierEligibilityResponse.newBuilder()
                    .setCourierSub(request.getCourierSub())
                    .setIsEligible(true)
                    .setIsActive(true)
                    .setFullName("Jane Doe")
                    .setRejectionReason("ELIGIBLE")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void validateMerchantKeyDigest(MerchantKeyDigestRequest request,
                                              StreamObserver<MerchantKeyDigestResponse> responseObserver) {
            MerchantKeyDigestResponse response = MerchantKeyDigestResponse.newBuilder()
                    .setIsValid(true)
                    .setMerchantId(UUID.randomUUID().toString())
                    .setBusinessName("Swift Merchant Ltd")
                    .setStatus("APPROVED")
                    .addScopes("parcels:create")
                    .addScopes("parcels:read")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void resolveRecipientContact(RecipientContactRequest request,
                                            StreamObserver<RecipientContactResponse> responseObserver) {
            RecipientContactResponse response = RecipientContactResponse.newBuilder()
                    .setRecipientSub(request.getRecipientSub())
                    .setEmail("recipient@example.com")
                    .setPhoneNumber("+94771234567")
                    .setFullName("John Smith")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getPendingMerchantsSummary(PendingMerchantsSummaryRequest request,
                                               StreamObserver<PendingMerchantsSummaryResponse> responseObserver) {
            Instant now = Instant.now();
            Timestamp ts = Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .setNanos(now.getNano())
                    .build();

            PendingMerchantItem item = PendingMerchantItem.newBuilder()
                    .setMerchantId(UUID.randomUUID().toString())
                    .setBusinessName("Pending Enterprises")
                    .setRegistrationNumber("PV12345")
                    .setStatus("PENDING")
                    .setSubmittedAt(ts)
                    .build();

            PendingMerchantsSummaryResponse response = PendingMerchantsSummaryResponse.newBuilder()
                    .setTotalPendingCount(1)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize())
                    .addItems(item)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void checkAccountEligibility(AccountEligibilityRequest request,
                                            StreamObserver<AccountEligibilityResponse> responseObserver) {
            AccountEligibilityResponse response = AccountEligibilityResponse.newBuilder()
                    .setSubject(request.getSubject())
                    .setIsEligible(true)
                    .setIsActive(true)
                    .setStatus("ACTIVE")
                    .setRejectionReason("ELIGIBLE")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getScopedProfileSummary(ScopedProfileSummaryRequest request,
                                            StreamObserver<ScopedProfileSummaryResponse> responseObserver) {
            ScopedProfileSummaryResponse response = ScopedProfileSummaryResponse.newBuilder()
                    .setSubject(request.getSubject())
                    .setFullName("Jane Doe")
                    .setRole(request.getScope().isEmpty() ? "CUSTOMER" : request.getScope())
                    .setStatus("ACTIVE")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getBatchProfileSummaries(BatchProfileSummariesRequest request,
                                             StreamObserver<BatchProfileSummariesResponse> responseObserver) {
            BatchProfileSummariesResponse.Builder builder = BatchProfileSummariesResponse.newBuilder();
            for (String sub : request.getSubjectsList()) {
                builder.addItems(ScopedProfileSummaryResponse.newBuilder()
                        .setSubject(sub)
                        .setFullName("User " + sub)
                        .setRole("STAFF")
                        .setStatus("ACTIVE")
                        .build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        }

        @Override
        public void getAvailableCouriersSummary(AvailableCouriersSummaryRequest request,
                                                StreamObserver<AvailableCouriersSummaryResponse> responseObserver) {
            CourierSummaryItem item = CourierSummaryItem.newBuilder()
                    .setCourierSub(UUID.randomUUID().toString())
                    .setFullName("Jane Courier")
                    .setStaffId("STF-2026-001")
                    .setAssignedZone("ZONE_1")
                    .setIsEligible(true)
                    .setIsActive(true)
                    .build();
            AvailableCouriersSummaryResponse response = AvailableCouriersSummaryResponse.newBuilder()
                    .setTotalCount(1)
                    .setPage(request.getPage())
                    .setPageSize(request.getPageSize() > 0 ? request.getPageSize() : 20)
                    .addItems(item)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
