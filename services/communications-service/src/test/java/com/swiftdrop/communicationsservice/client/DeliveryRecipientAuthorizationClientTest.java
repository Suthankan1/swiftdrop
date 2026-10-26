package com.swiftdrop.communicationsservice.client;

import com.swiftdrop.delivery.v1.AuthorizeEventRecipientRequest;
import com.swiftdrop.delivery.v1.AuthorizeEventRecipientResponse;
import com.swiftdrop.delivery.v1.DeliveryInternalServiceGrpc;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryRecipientAuthorizationClientTest {

    private Server inProcessServer;
    private ManagedChannel inProcessChannel;
    private GrpcDeliveryRecipientAuthorizationClient client;

    private final AtomicReference<AuthorizeEventRecipientRequest> lastReceivedRequest = new AtomicReference<>();
    private final AtomicReference<Throwable> cannedException = new AtomicReference<>();
    private final AtomicReference<AuthorizeEventRecipientResponse> cannedResponse = new AtomicReference<>();

    private final UUID eventId = UUID.randomUUID();
    private final UUID parcelId = UUID.randomUUID();
    private final String recipientSub = "sub-cust-" + UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        String serverName = InProcessServerBuilder.generateName();

        DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase mockService =
                new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                    @Override
                    public void authorizeEventRecipient(AuthorizeEventRecipientRequest request,
                                                        StreamObserver<AuthorizeEventRecipientResponse> responseObserver) {
                        lastReceivedRequest.set(request);
                        Throwable error = cannedException.get();
                        if (error != null) {
                            responseObserver.onError(error);
                            return;
                        }
                        AuthorizeEventRecipientResponse response = cannedResponse.get();
                        if (response != null) {
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        } else {
                            responseObserver.onError(Status.NOT_FOUND.withDescription("Event or parcel not found").asRuntimeException());
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

        client = new GrpcDeliveryRecipientAuthorizationClient(inProcessChannel, 2000L);
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
    @DisplayName("authorizeEventRecipient successfully authorizes recipient and maps all fields")
    void testAuthorizeEventRecipient_Success() {
        cannedResponse.set(AuthorizeEventRecipientResponse.newBuilder()
                .setIsAuthorized(true)
                .setParcelId(parcelId.toString())
                .setEventId(eventId.toString())
                .setRecipientSub(recipientSub)
                .setMerchantId(merchantId.toString())
                .setTrackingNumber("SWD-20261026-X1Y2")
                .setCurrentStatus("IN_TRANSIT")
                .setEventType("parcel.status.changed.v1")
                .setEventVersion(3L)
                .setCurrentVersion(3L)
                .setIsTerminal(false)
                .setIsStale(false)
                .setAuthorizationReason("AUTHORIZED")
                .build());

        DeliveryEventAuthorization auth = client.authorizeEventRecipient(
                eventId, parcelId, recipientSub, merchantId, false
        );

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthorized()).isTrue();
        assertThat(auth.parcelId()).isEqualTo(parcelId);
        assertThat(auth.eventId()).isEqualTo(eventId);
        assertThat(auth.recipientSub()).isEqualTo(recipientSub);
        assertThat(auth.merchantId()).isEqualTo(merchantId);
        assertThat(auth.trackingNumber()).isEqualTo("SWD-20261026-X1Y2");
        assertThat(auth.currentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(auth.eventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(auth.eventVersion()).isEqualTo(3L);
        assertThat(auth.currentVersion()).isEqualTo(3L);
        assertThat(auth.isTerminal()).isFalse();
        assertThat(auth.isStale()).isFalse();
        assertThat(auth.authorizationReason()).isEqualTo("AUTHORIZED");

        AuthorizeEventRecipientRequest sent = lastReceivedRequest.get();
        assertThat(sent.getEventId()).isEqualTo(eventId.toString());
        assertThat(sent.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(sent.getRecipientSub()).isEqualTo(recipientSub);
        assertThat(sent.getMerchantId()).isEqualTo(merchantId.toString());
        assertThat(sent.getAllowStale()).isFalse();
    }

    @Test
    @DisplayName("authorizeEventRecipient maps authorized stale event decision")
    void testAuthorizeEventRecipient_AuthorizedStale() {
        cannedResponse.set(AuthorizeEventRecipientResponse.newBuilder()
                .setIsAuthorized(true)
                .setParcelId(parcelId.toString())
                .setEventId(eventId.toString())
                .setRecipientSub(recipientSub)
                .setMerchantId(merchantId.toString())
                .setTrackingNumber("SWD-20261026-X1Y2")
                .setCurrentStatus("DELIVERED")
                .setEventType("parcel.status.changed.v1")
                .setEventVersion(1L)
                .setCurrentVersion(4L)
                .setIsTerminal(true)
                .setIsStale(true)
                .setAuthorizationReason("AUTHORIZED_STALE")
                .build());

        DeliveryEventAuthorization auth = client.authorizeEventRecipient(
                eventId, parcelId, recipientSub, merchantId, true
        );

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthorized()).isTrue();
        assertThat(auth.isStale()).isTrue();
        assertThat(auth.isTerminal()).isTrue();
        assertThat(auth.authorizationReason()).isEqualTo("AUTHORIZED_STALE");

        AuthorizeEventRecipientRequest sent = lastReceivedRequest.get();
        assertThat(sent.getAllowStale()).isTrue();
    }

    @Test
    @DisplayName("authorizeEventRecipient returns negative authorization when recipient is not associated")
    void testAuthorizeEventRecipient_Unauthorized() {
        cannedResponse.set(AuthorizeEventRecipientResponse.newBuilder()
                .setIsAuthorized(false)
                .setParcelId(parcelId.toString())
                .setEventId(eventId.toString())
                .setRecipientSub("stranger-sub")
                .setMerchantId(merchantId.toString())
                .setTrackingNumber("SWD-20261026-X1Y2")
                .setCurrentStatus("IN_TRANSIT")
                .setEventType("parcel.status.changed.v1")
                .setEventVersion(2L)
                .setCurrentVersion(2L)
                .setIsTerminal(false)
                .setIsStale(false)
                .setAuthorizationReason("RECIPIENT_NOT_ASSOCIATED")
                .build());

        DeliveryEventAuthorization auth = client.authorizeEventRecipient(
                eventId, parcelId, "stranger-sub", merchantId, false
        );

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthorized()).isFalse();
        assertThat(auth.authorizationReason()).isEqualTo("RECIPIENT_NOT_ASSOCIATED");
    }

    @Test
    @DisplayName("authorizeEventRecipient returns fail-closed authorization when event not found in Delivery")
    void testAuthorizeEventRecipient_EventNotFound_FailClosed() {
        cannedResponse.set(AuthorizeEventRecipientResponse.newBuilder()
                .setIsAuthorized(false)
                .setParcelId(parcelId.toString())
                .setEventId(eventId.toString())
                .setRecipientSub(recipientSub)
                .setIsTerminal(false)
                .setIsStale(false)
                .setAuthorizationReason("EVENT_NOT_FOUND")
                .build());

        DeliveryEventAuthorization auth = client.authorizeEventRecipient(
                eventId, parcelId, recipientSub, merchantId, false
        );

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthorized()).isFalse();
        assertThat(auth.authorizationReason()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    @DisplayName("authorizeEventRecipient throws retryable DeliveryServiceUnavailableException on UNAVAILABLE")
    void testAuthorizeEventRecipient_ServiceUnavailable() {
        cannedException.set(Status.UNAVAILABLE.withDescription("Connection refused").asRuntimeException());

        assertThatThrownBy(() -> client.authorizeEventRecipient(eventId, parcelId, recipientSub, merchantId, false))
                .isInstanceOf(DeliveryServiceUnavailableException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    @DisplayName("authorizeEventRecipient throws retryable DeliveryServiceUnavailableException on DEADLINE_EXCEEDED")
    void testAuthorizeEventRecipient_DeadlineExceeded() {
        cannedException.set(Status.DEADLINE_EXCEEDED.withDescription("Deadline exceeded").asRuntimeException());

        assertThatThrownBy(() -> client.authorizeEventRecipient(eventId, parcelId, recipientSub, merchantId, false))
                .isInstanceOf(DeliveryServiceUnavailableException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("authorizeEventRecipient throws DeliverySecurityException on PERMISSION_DENIED")
    void testAuthorizeEventRecipient_PermissionDenied() {
        cannedException.set(Status.PERMISSION_DENIED.withDescription("Access denied").asRuntimeException());

        assertThatThrownBy(() -> client.authorizeEventRecipient(eventId, parcelId, recipientSub, merchantId, false))
                .isInstanceOf(DeliverySecurityException.class)
                .hasMessageContaining("PERMISSION_DENIED");
    }

    @Test
    @DisplayName("authorizeEventRecipient throws DeliverySecurityException on UNAUTHENTICATED")
    void testAuthorizeEventRecipient_Unauthenticated() {
        cannedException.set(Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException());

        assertThatThrownBy(() -> client.authorizeEventRecipient(eventId, parcelId, recipientSub, merchantId, false))
                .isInstanceOf(DeliverySecurityException.class)
                .hasMessageContaining("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("authorizeEventRecipient throws IllegalArgumentException on INVALID_ARGUMENT")
    void testAuthorizeEventRecipient_InvalidArgument() {
        cannedException.set(Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException());

        assertThatThrownBy(() -> client.authorizeEventRecipient(eventId, parcelId, recipientSub, merchantId, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid authorization request: Invalid UUID");
    }

    @Test
    @DisplayName("authorizeEventRecipient validates required arguments locally before gRPC call")
    void testAuthorizeEventRecipient_RequiredArgsValidation() {
        assertThatThrownBy(() -> client.authorizeEventRecipient(null, parcelId, recipientSub, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId is required");

        assertThatThrownBy(() -> client.authorizeEventRecipient(eventId, null, recipientSub, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parcelId is required");
    }
}
