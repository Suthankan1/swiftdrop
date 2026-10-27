package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.accounts.v1.AccountEligibilityRequest;
import com.swiftdrop.accounts.v1.AccountEligibilityResponse;
import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotApprovedException;
import com.swiftdrop.communicationsservice.webhook.exception.MerchantNotFoundException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrpcAccountsMerchantEligibilityClientTest {

    private AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub mockStub;
    private GrpcAccountsMerchantEligibilityClient client;

    @BeforeEach
    void setUp() {
        mockStub = mock(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub.class);
        when(mockStub.withDeadlineAfter(anyLong(), any())).thenReturn(mockStub);
        client = new GrpcAccountsMerchantEligibilityClient(mockStub, 2000L);
    }

    @Test
    @DisplayName("Approved, active, and eligible merchant resolves successfully")
    void approvedMerchant_ResolvesSuccessfully() {
        UUID expectedMerchantId = UUID.randomUUID();
        AccountEligibilityResponse response = AccountEligibilityResponse.newBuilder()
                .setSubject("user-sub-123")
                .setMerchantId(expectedMerchantId.toString())
                .setBusinessName("Acme Logistics")
                .setStatus("APPROVED")
                .setIsEligible(true)
                .setIsActive(true)
                .build();

        when(mockStub.checkAccountEligibility(any(AccountEligibilityRequest.class))).thenReturn(response);

        ApprovedMerchantTenant tenant = client.resolveApprovedMerchant("user-sub-123");

        assertThat(tenant.merchantId()).isEqualTo(expectedMerchantId);
        assertThat(tenant.subject()).isEqualTo("user-sub-123");
        assertThat(tenant.businessName()).isEqualTo("Acme Logistics");
        assertThat(tenant.status()).isEqualTo("APPROVED");
        assertThat(tenant.isEligible()).isTrue();
        assertThat(tenant.isActive()).isTrue();
        assertThat(tenant.isApproved()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUSPENDED", "REJECTED"})
    @DisplayName("Unapproved merchant lifecycle statuses throw MerchantNotApprovedException")
    void unapprovedStatuses_ThrowMerchantNotApprovedException(String status) {
        AccountEligibilityResponse response = AccountEligibilityResponse.newBuilder()
                .setSubject("user-sub-123")
                .setMerchantId(UUID.randomUUID().toString())
                .setBusinessName("Acme Logistics")
                .setStatus(status)
                .setIsEligible(true)
                .setIsActive(true)
                .setRejectionReason("MERCHANT_" + status)
                .build();

        when(mockStub.checkAccountEligibility(any(AccountEligibilityRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> client.resolveApprovedMerchant("user-sub-123"))
                .isInstanceOf(MerchantNotApprovedException.class)
                .hasMessageContaining(status);
    }

    @Test
    @DisplayName("Inactive or ineligible merchant throws MerchantNotApprovedException")
    void inactiveOrIneligible_ThrowsMerchantNotApprovedException() {
        AccountEligibilityResponse response = AccountEligibilityResponse.newBuilder()
                .setSubject("user-sub-123")
                .setMerchantId(UUID.randomUUID().toString())
                .setStatus("APPROVED")
                .setIsEligible(false)
                .setIsActive(true)
                .build();

        when(mockStub.checkAccountEligibility(any(AccountEligibilityRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> client.resolveApprovedMerchant("user-sub-123"))
                .isInstanceOf(MerchantNotApprovedException.class);
    }

    @Test
    @DisplayName("Unknown merchant principal throws MerchantNotFoundException")
    void notFound_ThrowsMerchantNotFoundException() {
        AccountEligibilityResponse response = AccountEligibilityResponse.newBuilder()
                .setSubject("user-sub-123")
                .setStatus("NOT_FOUND")
                .setMerchantId("")
                .build();

        when(mockStub.checkAccountEligibility(any(AccountEligibilityRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> client.resolveApprovedMerchant("user-sub-123"))
                .isInstanceOf(MerchantNotFoundException.class)
                .hasMessageContaining("Merchant tenant not found");
    }

    @Test
    @DisplayName("gRPC NOT_FOUND status code throws MerchantNotFoundException")
    void grpcNotFound_ThrowsMerchantNotFoundException() {
        when(mockStub.checkAccountEligibility(any(AccountEligibilityRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.NOT_FOUND.withDescription("Account not found")));

        assertThatThrownBy(() -> client.resolveApprovedMerchant("user-sub-123"))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    @DisplayName("gRPC DEADLINE_EXCEEDED or UNAVAILABLE throws AccountsServiceUnavailableException")
    void grpcUnavailable_ThrowsServiceUnavailableException() {
        when(mockStub.checkAccountEligibility(any(AccountEligibilityRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Service down")));

        assertThatThrownBy(() -> client.resolveApprovedMerchant("user-sub-123"))
                .isInstanceOf(AccountsServiceUnavailableException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    @DisplayName("gRPC PERMISSION_DENIED throws AccountsSecurityException")
    void grpcPermissionDenied_ThrowsAccountsSecurityException() {
        when(mockStub.checkAccountEligibility(any(AccountEligibilityRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("Access denied")));

        assertThatThrownBy(() -> client.resolveApprovedMerchant("user-sub-123"))
                .isInstanceOf(AccountsSecurityException.class)
                .hasMessageContaining("denied");
    }

    @Test
    @DisplayName("Null or blank subject throws IllegalArgumentException")
    void nullOrBlankSubject_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> client.resolveApprovedMerchant(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> client.resolveApprovedMerchant("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
