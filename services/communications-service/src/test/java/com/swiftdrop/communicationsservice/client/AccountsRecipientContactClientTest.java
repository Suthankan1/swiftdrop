package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.accounts.v1.AccountsInternalServiceGrpc;
import com.swiftdrop.accounts.v1.RecipientContactRequest;
import com.swiftdrop.accounts.v1.RecipientContactResponse;
import com.swiftdrop.communicationsservice.notification.NotificationChannel;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountsRecipientContactClientTest {

    private Server inProcessServer;
    private ManagedChannel inProcessChannel;
    private GrpcAccountsRecipientContactClient client;

    private final AtomicReference<RecipientContactRequest> lastReceivedRequest = new AtomicReference<>();
    private final AtomicReference<Throwable> cannedException = new AtomicReference<>();
    private final AtomicReference<RecipientContactResponse> cannedResponse = new AtomicReference<>();

    private final String recipientSub = "sub-cust-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        AccountsInternalServiceGrpc.AccountsInternalServiceImplBase mockService =
                new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                    @Override
                    public void resolveRecipientContact(RecipientContactRequest request,
                                                        StreamObserver<RecipientContactResponse> responseObserver) {
                        lastReceivedRequest.set(request);
                        Throwable error = cannedException.get();
                        if (error != null) {
                            responseObserver.onError(error);
                            return;
                        }
                        RecipientContactResponse response = cannedResponse.get();
                        if (response != null) {
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        } else {
                            responseObserver.onError(Status.NOT_FOUND.withDescription("Not found").asRuntimeException());
                        }
                    }
                };

        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(mockService)
                .build()
                .start();

        inProcessChannel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        client = new GrpcAccountsRecipientContactClient(inProcessChannel, 2000L);
    }

    @AfterEach
    void tearDown() {
        if (inProcessChannel != null) {
            inProcessChannel.shutdownNow();
        }
        if (inProcessServer != null) {
            inProcessServer.shutdownNow();
        }
    }

    @Test
    @DisplayName("resolveContact successfully resolves EMAIL channel contact with phone excluded")
    void testResolveContact_EmailChannel_Success() {
        cannedResponse.set(RecipientContactResponse.newBuilder()
                .setRecipientSub(recipientSub)
                .setEmail("customer@example.com")
                .setPhoneNumber("") // Excluded by Accounts policy
                .setFullName("Alice Customer")
                .setIsEligible(true)
                .setEmailPermitted(true)
                .setSmsPermitted(false)
                .setStatus("ACTIVE")
                .setRejectionReason("ELIGIBLE")
                .build());

        Optional<PermittedRecipientContact> result = client.resolveContact(
                recipientSub, "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL
        );

        assertThat(result).isPresent();
        PermittedRecipientContact contact = result.get();
        assertThat(contact.recipientSub()).isEqualTo(recipientSub);
        assertThat(contact.email()).isEqualTo("customer@example.com");
        assertThat(contact.phoneNumber()).isEmpty();
        assertThat(contact.fullName()).isEqualTo("Alice Customer");
        assertThat(contact.isEligible()).isTrue();
        assertThat(contact.hasPermittedEmail()).isTrue();
        assertThat(contact.hasPermittedSms()).isFalse();
        assertThat(contact.status()).isEqualTo("ACTIVE");
        assertThat(contact.rejectionReason()).isEqualTo("ELIGIBLE");

        // Verify request payload sent to gRPC provider
        RecipientContactRequest sent = lastReceivedRequest.get();
        assertThat(sent.getRecipientSub()).isEqualTo(recipientSub);
        assertThat(sent.getPurpose()).isEqualTo("PARCEL_STATUS_UPDATE");
        assertThat(sent.getChannel()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("resolveContact successfully resolves SMS channel contact with email excluded")
    void testResolveContact_SmsChannel_Success() {
        cannedResponse.set(RecipientContactResponse.newBuilder()
                .setRecipientSub(recipientSub)
                .setEmail("") // Excluded by Accounts policy
                .setPhoneNumber("+94771234567")
                .setFullName("Alice Customer")
                .setIsEligible(true)
                .setEmailPermitted(false)
                .setSmsPermitted(true)
                .setStatus("ACTIVE")
                .setRejectionReason("ELIGIBLE")
                .build());

        Optional<PermittedRecipientContact> result = client.resolveContact(
                recipientSub, "DELIVERY_NOTIFICATION", NotificationChannel.SMS_MOCK
        );

        assertThat(result).isPresent();
        PermittedRecipientContact contact = result.get();
        assertThat(contact.recipientSub()).isEqualTo(recipientSub);
        assertThat(contact.email()).isEmpty();
        assertThat(contact.phoneNumber()).isEqualTo("+94771234567");
        assertThat(contact.isEligible()).isTrue();
        assertThat(contact.hasPermittedEmail()).isFalse();
        assertThat(contact.hasPermittedSms()).isTrue();

        RecipientContactRequest sent = lastReceivedRequest.get();
        assertThat(sent.getChannel()).isEqualTo("SMS_MOCK");
    }

    @Test
    @DisplayName("resolveContact safely returns empty Optional when recipient is not found")
    void testResolveContact_UnknownRecipient_ReturnsEmpty() {
        cannedException.set(Status.NOT_FOUND.withDescription("Recipient not found: " + recipientSub).asRuntimeException());

        Optional<PermittedRecipientContact> result = client.resolveContact(
                recipientSub, "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveContact returns ineligible status when recipient is disabled/suspended")
    void testResolveContact_DisabledRecipient_ReturnsIneligible() {
        cannedResponse.set(RecipientContactResponse.newBuilder()
                .setRecipientSub(recipientSub)
                .setEmail("")
                .setPhoneNumber("")
                .setFullName("")
                .setIsEligible(false)
                .setEmailPermitted(false)
                .setSmsPermitted(false)
                .setStatus("DISABLED")
                .setRejectionReason("STAFF_SUSPENDED")
                .build());

        Optional<PermittedRecipientContact> result = client.resolveContact(
                recipientSub, "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL
        );

        assertThat(result).isPresent();
        PermittedRecipientContact contact = result.get();
        assertThat(contact.isEligible()).isFalse();
        assertThat(contact.hasPermittedEmail()).isFalse();
        assertThat(contact.status()).isEqualTo("DISABLED");
        assertThat(contact.rejectionReason()).isEqualTo("STAFF_SUSPENDED");
        assertThat(contact.email()).isEmpty();
        assertThat(contact.phoneNumber()).isEmpty();
    }

    @Test
    @DisplayName("resolveContact throws retryable AccountsServiceUnavailableException when service is down")
    void testResolveContact_ServiceUnavailable_ThrowsRetryableException() {
        cannedException.set(Status.UNAVAILABLE.withDescription("Connection refused").asRuntimeException());

        assertThatThrownBy(() -> client.resolveContact(recipientSub, "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL))
                .isInstanceOf(AccountsServiceUnavailableException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    @DisplayName("resolveContact throws retryable AccountsServiceUnavailableException on deadline exceeded")
    void testResolveContact_DeadlineExceeded_ThrowsRetryableException() {
        cannedException.set(Status.DEADLINE_EXCEEDED.withDescription("Deadline exceeded").asRuntimeException());

        assertThatThrownBy(() -> client.resolveContact(recipientSub, "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL))
                .isInstanceOf(AccountsServiceUnavailableException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("resolveContact throws AccountsSecurityException when machine authorization is denied")
    void testResolveContact_PermissionDenied_ThrowsSecurityException() {
        cannedException.set(Status.PERMISSION_DENIED.withDescription("Caller unauthorized").asRuntimeException());

        assertThatThrownBy(() -> client.resolveContact(recipientSub, "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL))
                .isInstanceOf(AccountsSecurityException.class)
                .hasMessageContaining("PERMISSION_DENIED");
    }

    @Test
    @DisplayName("resolveContact throws IllegalArgumentException for blank or null recipientSub")
    void testResolveContact_BlankRecipientSub_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> client.resolveContact("   ", "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipientSub is required");

        assertThatThrownBy(() -> client.resolveContact(null, "PARCEL_STATUS_UPDATE", NotificationChannel.EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipientSub is required");
    }
}
