package com.swiftdrop.deliveryservice.grpc;

import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignment;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.outbox.OutboxEvent;
import com.swiftdrop.deliveryservice.outbox.OutboxEventRepository;
import com.swiftdrop.deliveryservice.outbox.OutboxStatus;
import com.swiftdrop.grpc.security.SwiftDropGrpcMetadata;
import com.swiftdrop.security.grpc.ActorDelegationValidator;
import com.swiftdrop.security.grpc.GrpcSecurityPolicy;
import com.swiftdrop.security.grpc.SwiftDropGrpcServerSecurityInterceptor;
import com.swiftdrop.security.service.JwtServiceTokenValidator;
import com.swiftdrop.security.service.ServiceTokenValidator;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DeliveryInternalServiceGrpcTest {

    private final JwtTestHelper jwtHelper = new JwtTestHelper();

    private ParcelRepository parcelRepository;
    private OutboxEventRepository outboxEventRepository;
    private DeliveryAssignmentRepository deliveryAssignmentRepository;

    private Server server;
    private ManagedChannel channel;

    private final UUID parcelId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID merchantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String customerSub = "cust-alice-123";
    private final String courierSub = "courier-bob-456";

    @BeforeEach
    void setUp() throws Exception {
        parcelRepository = mock(ParcelRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        deliveryAssignmentRepository = mock(DeliveryAssignmentRepository.class);

        DeliveryInternalServiceImpl serviceImpl = new DeliveryInternalServiceImpl(
                parcelRepository,
                outboxEventRepository,
                deliveryAssignmentRepository
        );

        ServiceTokenValidator serviceValidator = new JwtServiceTokenValidator(
                jwtHelper.createServiceJwtDecoder(),
                JwtTestHelper.DEFAULT_SERVICE_AUDIENCE,
                JwtTestHelper.DEFAULT_ISSUER
        );

        ActorDelegationValidator actorValidator = new ActorDelegationValidator(
                jwtHelper.createUserJwtDecoder()
        );

        SwiftDropGrpcServerSecurityInterceptor securityInterceptor =
                new SwiftDropGrpcServerSecurityInterceptor(serviceValidator, actorValidator, new GrpcSecurityPolicy());

        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(securityInterceptor)
                .addService(serviceImpl)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
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

    private DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub createStubWithServiceToken(String serviceName) {
        String token = jwtHelper.createSignedServiceToken(serviceName);
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, token);
        return DeliveryInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub createStubWithActorDelegation(
            String serviceName, String actorSub, String actorRole) {
        String serviceToken = jwtHelper.createSignedServiceToken(serviceName);
        String actorToken = jwtHelper.createSignedUserToken(actorSub, List.of(actorRole));
        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, actorSub);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, actorRole);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, actorToken);
        return DeliveryInternalServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub createCommsStub() {
        return createStubWithServiceToken(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE.getServiceName());
    }

    private Parcel createParcel(ParcelStatus status, Long version, UUID merchant) {
        Parcel p = new Parcel(
                parcelId,
                "SWD-20261023-A1B2",
                customerSub,
                merchant,
                UUID.randomUUID(),
                "2026.1",
                250000L,
                "LKR",
                new BigDecimal("1.500")
        );
        p.setStatus(status);
        p.setVersion(version);
        return p;
    }

    private OutboxEvent createOutboxEvent(UUID evtId, UUID aggId, Long aggVersion, String eventType) {
        return new OutboxEvent(
                evtId,
                "PARCEL",
                aggId,
                aggVersion,
                eventType,
                "1.0.0",
                "{}",
                OutboxStatus.PENDING,
                Instant.now(),
                0,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                "parcel.lifecycle.v1",
                aggId.toString()
        );
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Resolves primary customer recipient via Delivery-owned state")
    void testAuthorizeEventRecipient_PrimaryCustomer_Success() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 2L, merchantId);
        OutboxEvent event = createOutboxEvent(eventId, parcelId, 2L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isTrue();
        assertThat(response.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(response.getEventId()).isEqualTo(eventId.toString());
        assertThat(response.getRecipientSub()).isEqualTo(customerSub);
        assertThat(response.getMerchantId()).isEqualTo(merchantId.toString());
        assertThat(response.getTrackingNumber()).isEqualTo("SWD-20261023-A1B2");
        assertThat(response.getCurrentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(response.getEventType()).isEqualTo("parcel.status.changed.v1");
        assertThat(response.getEventVersion()).isEqualTo(2L);
        assertThat(response.getCurrentVersion()).isEqualTo(2L);
        assertThat(response.getIsTerminal()).isFalse();
        assertThat(response.getIsStale()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Explicit candidate recipient matching customer is authorized")
    void testAuthorizeEventRecipient_ExplicitMatchingCustomer_Success() {
        Parcel parcel = createParcel(ParcelStatus.PICKED_UP, 1L, null);
        OutboxEvent event = createOutboxEvent(eventId, parcelId, 1L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setRecipientSub(customerSub)
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isTrue();
        assertThat(response.getRecipientSub()).isEqualTo(customerSub);
        assertThat(response.getMerchantId()).isEmpty();
        assertThat(response.getAuthorizationReason()).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Active courier recipient is authorized")
    void testAuthorizeEventRecipient_ActiveCourierRecipient_Success() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 2L, null);
        OutboxEvent event = createOutboxEvent(eventId, parcelId, 2L, "parcel.status.changed.v1");

        DeliveryAssignment assignment = new DeliveryAssignment(
                UUID.randomUUID(),
                parcelId,
                courierSub,
                "dispatcher-1",
                Instant.now(),
                null,
                true
        );

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.of(assignment));

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setRecipientSub(courierSub)
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isTrue();
        assertThat(response.getRecipientSub()).isEqualTo(courierSub);
        assertThat(response.getAuthorizationReason()).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Missing event_id rejected with INVALID_ARGUMENT")
    void testAuthorizeEventRecipient_MissingEventId_Fails() {
        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setParcelId(parcelId.toString())
                .build();

        assertThatThrownBy(() -> createCommsStub().authorizeEventRecipient(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Invalid event_id format rejected with INVALID_ARGUMENT")
    void testAuthorizeEventRecipient_InvalidEventId_Fails() {
        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId("not-a-valid-uuid")
                .setParcelId(parcelId.toString())
                .build();

        assertThatThrownBy(() -> createCommsStub().authorizeEventRecipient(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Missing parcel_id rejected with INVALID_ARGUMENT")
    void testAuthorizeEventRecipient_MissingParcelId_Fails() {
        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .build();

        assertThatThrownBy(() -> createCommsStub().authorizeEventRecipient(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Unknown event fails closed with EVENT_NOT_FOUND")
    void testAuthorizeEventRecipient_EventNotFound_FailClosed() {
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Forged pairing (event aggregateId != parcelId) fails closed with PARCEL_MISMATCH")
    void testAuthorizeEventRecipient_ParcelMismatch_ForgedPairing_FailClosed() {
        UUID otherParcelId = UUID.randomUUID();
        OutboxEvent event = createOutboxEvent(eventId, otherParcelId, 1L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("PARCEL_MISMATCH");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Parcel not found fails closed with PARCEL_NOT_FOUND")
    void testAuthorizeEventRecipient_ParcelNotFound_FailClosed() {
        OutboxEvent event = createOutboxEvent(eventId, parcelId, 1L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("PARCEL_NOT_FOUND");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Cross-tenant merchant mismatch fails closed with MERCHANT_MISMATCH")
    void testAuthorizeEventRecipient_MerchantTenantMismatch_FailClosed() {
        UUID requestedWrongMerchant = UUID.randomUUID();
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 2L, merchantId);
        OutboxEvent event = createOutboxEvent(eventId, parcelId, 2L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setMerchantId(requestedWrongMerchant.toString())
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("MERCHANT_MISMATCH");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Stranger recipient has no authority, fails closed with RECIPIENT_NOT_AUTHORIZED")
    void testAuthorizeEventRecipient_StrangerRecipient_FailClosed() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 2L, merchantId);
        OutboxEvent event = createOutboxEvent(eventId, parcelId, 2L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setRecipientSub("stranger-sub-999")
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("RECIPIENT_NOT_AUTHORIZED");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Stale event with allow_stale=false fails closed with STALE_EVENT")
    void testAuthorizeEventRecipient_StaleEvent_Disallowed_FailClosed() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 4L, merchantId);
        OutboxEvent staleEvent = createOutboxEvent(eventId, parcelId, 2L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(staleEvent));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setAllowStale(false)
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isFalse();
        assertThat(response.getIsStale()).isTrue();
        assertThat(response.getEventVersion()).isEqualTo(2L);
        assertThat(response.getCurrentVersion()).isEqualTo(4L);
        assertThat(response.getAuthorizationReason()).isEqualTo("STALE_EVENT");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Stale event with allow_stale=true succeeds with AUTHORIZED_STALE")
    void testAuthorizeEventRecipient_StaleEvent_Allowed_Success() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 4L, merchantId);
        OutboxEvent staleEvent = createOutboxEvent(eventId, parcelId, 2L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(staleEvent));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setAllowStale(true)
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isTrue();
        assertThat(response.getIsStale()).isTrue();
        assertThat(response.getAuthorizationReason()).isEqualTo("AUTHORIZED_STALE");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Terminal DELIVERED transition event is authorized with AUTHORIZED_TERMINAL")
    void testAuthorizeEventRecipient_TerminalEvent_Delivered_Success() {
        Parcel parcel = createParcel(ParcelStatus.DELIVERED, 4L, merchantId);
        OutboxEvent terminalEvent = createOutboxEvent(eventId, parcelId, 4L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(terminalEvent));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isTrue();
        assertThat(response.getIsTerminal()).isTrue();
        assertThat(response.getIsStale()).isFalse();
        assertThat(response.getAuthorizationReason()).isEqualTo("AUTHORIZED_TERMINAL");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Stale event on terminal parcel is rejected with STALE_TERMINAL_EVENT")
    void testAuthorizeEventRecipient_StaleEvent_OnTerminalParcel_FailClosed() {
        Parcel parcel = createParcel(ParcelStatus.DELIVERED, 4L, merchantId);
        OutboxEvent earlierEvent = createOutboxEvent(eventId, parcelId, 2L, "parcel.status.changed.v1");

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(earlierEvent));
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .setAllowStale(true)
                .build();

        AuthorizeEventRecipientResponse response = createCommsStub().authorizeEventRecipient(request);

        assertThat(response.getIsAuthorized()).isFalse();
        assertThat(response.getIsTerminal()).isTrue();
        assertThat(response.getIsStale()).isTrue();
        assertThat(response.getAuthorizationReason()).isEqualTo("STALE_TERMINAL_EVENT");
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Unauthorized service identity rejected with PERMISSION_DENIED")
    void testAuthorizeEventRecipient_CallerNotCommunications_PermissionDenied() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStubWithServiceToken(SwiftDropServiceIdentity.DELIVERY_SERVICE.getServiceName());

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        assertThatThrownBy(() -> stub.authorizeEventRecipient(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Human actor delegation is FORBIDDEN and rejected with PERMISSION_DENIED")
    void testAuthorizeEventRecipient_ActorDelegationHeaders_PermissionDenied() {
        String serviceToken = jwtHelper.createSignedServiceToken(SwiftDropServiceIdentity.COMMUNICATIONS_SERVICE.getServiceName());
        String actorToken = jwtHelper.createSignedUserToken("courier-1", List.of("COURIER"));

        Metadata headers = new Metadata();
        headers.put(SwiftDropGrpcMetadata.KEY_SERVICE_TOKEN, serviceToken);
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_SUB, "courier-1");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_ROLES, "COURIER");
        headers.put(SwiftDropGrpcMetadata.KEY_ACTOR_TOKEN, actorToken);

        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                DeliveryInternalServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        assertThatThrownBy(() -> stub.authorizeEventRecipient(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("AuthorizeEventRecipient: Unauthenticated caller rejected with UNAUTHENTICATED")
    void testAuthorizeEventRecipient_MissingServiceToken_Unauthenticated() {
        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub unauthStub =
                DeliveryInternalServiceGrpc.newBlockingStub(channel);

        AuthorizeEventRecipientRequest request = AuthorizeEventRecipientRequest.newBuilder()
                .setEventId(eventId.toString())
                .setParcelId(parcelId.toString())
                .build();

        assertThatThrownBy(() -> unauthStub.authorizeEventRecipient(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> assertThat(((StatusRuntimeException) ex).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("GetParcelInternal: returns internal projection for GraphQL service")
    void testGetParcelInternal_Success() {
        Parcel parcel = createParcel(ParcelStatus.IN_TRANSIT, 2L, merchantId);
        when(parcelRepository.findById(parcelId)).thenReturn(Optional.of(parcel));
        when(deliveryAssignmentRepository.findByParcelIdAndActiveTrue(parcelId)).thenReturn(Optional.empty());

        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStubWithActorDelegation(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), customerSub, "CUSTOMER");

        GetParcelInternalRequest request = GetParcelInternalRequest.newBuilder()
                .setParcelId(parcelId.toString())
                .build();

        GetParcelInternalResponse response = stub.getParcelInternal(request);

        assertThat(response.getParcelId()).isEqualTo(parcelId.toString());
        assertThat(response.getTrackingNumber()).isEqualTo("SWD-20261023-A1B2");
        assertThat(response.getCurrentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(response.getVersion()).isEqualTo(2L);
        assertThat(response.getCreatedBySub()).isEqualTo(customerSub);
        assertThat(response.getMerchantId()).isEqualTo(merchantId.toString());
    }

    @Test
    @DisplayName("GetCourierWorkload: returns active assignment count for dispatcher")
    void testGetCourierWorkload_Success() {
        when(deliveryAssignmentRepository.countByCourierSubAndActiveTrue(courierSub)).thenReturn(3);

        DeliveryInternalServiceGrpc.DeliveryInternalServiceBlockingStub stub =
                createStubWithActorDelegation(SwiftDropServiceIdentity.GRAPHQL_QUERY_SERVICE.getServiceName(), "dispatcher-sub", "DISPATCHER");

        CourierWorkloadRequest request = CourierWorkloadRequest.newBuilder()
                .setCourierSub(courierSub)
                .build();

        CourierWorkloadResponse response = stub.getCourierWorkload(request);

        assertThat(response.getCourierSub()).isEqualTo(courierSub);
        assertThat(response.getActiveAssignmentsCount()).isEqualTo(3);
    }
}
