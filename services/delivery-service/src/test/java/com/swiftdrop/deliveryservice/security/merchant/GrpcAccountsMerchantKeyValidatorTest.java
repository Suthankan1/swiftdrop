package com.swiftdrop.deliveryservice.security.merchant;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.MerchantKeyDigestRequest;
import com.swiftdrop.accounts.v1.MerchantKeyDigestResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcAccountsMerchantKeyValidatorTest {

    private AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub;
    private GrpcAccountsMerchantKeyValidator validator;

    @BeforeEach
    void setUp() {
        stub = mock(AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub.class);
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        validator = new GrpcAccountsMerchantKeyValidator(stub, 1500L);
    }

    @Test
    @DisplayName("Valid API key syntax and active status returns successful validation result")
    void validApiKeyReturnsSuccess() throws Exception {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        UUID merchantId = UUID.randomUUID();

        MerchantKeyDigestResponse response = MerchantKeyDigestResponse.newBuilder()
                .setIsValid(true)
                .setMerchantId(merchantId.toString())
                .setBusinessName("Acme Logistics")
                .setStatus("APPROVED")
                .addAllScopes(List.of("parcels:create", "parcels:read"))
                .build();

        when(stub.validateMerchantKeyDigest(any(MerchantKeyDigestRequest.class))).thenReturn(response);

        MerchantKeyValidationResult result = validator.validateApiKey(apiKey);

        assertThat(result.isValid()).isTrue();
        assertThat(result.merchantId()).isEqualTo(merchantId);
        assertThat(result.businessName()).isEqualTo("Acme Logistics");
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.isApproved()).isTrue();
        assertThat(result.hasScope("parcels:create")).isTrue();
        assertThat(result.hasScope("admin:all")).isFalse();

        ArgumentCaptor<MerchantKeyDigestRequest> captor = ArgumentCaptor.forClass(MerchantKeyDigestRequest.class);
        verify(stub).validateMerchantKeyDigest(captor.capture());
        MerchantKeyDigestRequest sentRequest = captor.getValue();

        assertThat(sentRequest.getKeyPrefix()).isEqualTo(apiKey.substring(0, 12));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String expectedHash = HexFormat.of().formatHex(md.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        assertThat(sentRequest.getKeyDigest()).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("Malformed API key format returns invalid result without invoking gRPC")
    void malformedApiKeyFailsValidationImmediately() {
        assertThat(validator.validateApiKey(null).isValid()).isFalse();
        assertThat(validator.validateApiKey("").isValid()).isFalse();
        assertThat(validator.validateApiKey("invalid_prefix_0123456789abcdef").isValid()).isFalse();
        assertThat(validator.validateApiKey("swd_live_short").isValid()).isFalse();
        assertThat(validator.validateApiKey("swd_live_0123456789abcdef0123456789abcdef_too_long").isValid()).isFalse();
    }

    @Test
    @DisplayName("Accounts returns isValid=false returns invalid result")
    void accountsReturnsNotValid() {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        when(stub.validateMerchantKeyDigest(any(MerchantKeyDigestRequest.class)))
                .thenReturn(MerchantKeyDigestResponse.newBuilder().setIsValid(false).build());

        MerchantKeyValidationResult result = validator.validateApiKey(apiKey);
        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("INVALID_KEY");
    }

    @Test
    @DisplayName("Accounts returns malformed merchantId returns invalid result")
    void accountsReturnsMalformedMerchantId() {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        when(stub.validateMerchantKeyDigest(any(MerchantKeyDigestRequest.class)))
                .thenReturn(MerchantKeyDigestResponse.newBuilder()
                        .setIsValid(true)
                        .setMerchantId("not-a-uuid")
                        .build());

        MerchantKeyValidationResult result = validator.validateApiKey(apiKey);
        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("ACCOUNTS_MALFORMED_RESPONSE");
    }

    @Test
    @DisplayName("DEADLINE_EXCEEDED throws AccountsUnavailableException")
    void deadlineExceededThrowsAccountsUnavailableException() {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        when(stub.validateMerchantKeyDigest(any(MerchantKeyDigestRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Timeout")));

        assertThatThrownBy(() -> validator.validateApiKey(apiKey))
                .isInstanceOf(AccountsUnavailableException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("UNAVAILABLE throws AccountsUnavailableException")
    void unavailableThrowsAccountsUnavailableException() {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        when(stub.validateMerchantKeyDigest(any(MerchantKeyDigestRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Connection refused")));

        assertThatThrownBy(() -> validator.validateApiKey(apiKey))
                .isInstanceOf(AccountsUnavailableException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    @DisplayName("Other gRPC errors return invalid result")
    void otherGrpcErrorsReturnInvalidResult() {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        when(stub.validateMerchantKeyDigest(any(MerchantKeyDigestRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.INTERNAL.withDescription("Internal error")));

        MerchantKeyValidationResult result = validator.validateApiKey(apiKey);
        assertThat(result.isValid()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("ACCOUNTS_ERROR");
    }
}
