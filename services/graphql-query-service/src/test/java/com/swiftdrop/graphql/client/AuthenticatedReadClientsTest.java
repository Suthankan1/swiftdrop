package com.swiftdrop.graphql.client;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.security.grpc.*;
import com.swiftdrop.security.jwt.*;
import com.swiftdrop.security.service.*;
import graphql.GraphQLContext;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class AuthenticatedReadClientsTest {
    static final RSAKey KEY = key();
    static RSAKey key() { try { return new RSAKeyGenerator(2048).generate(); } catch (Exception e) { throw new IllegalStateException(e); } }
    static String signed(String subject, String audience, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer("https://issuer.test").audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("service", subject).claim("realm_access", Map.of("roles", List.of(role))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(KEY)); return jwt.serialize();
    }
    static JwtDecoder decoder(String audience) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("https://issuer.test"), new AudienceValidator(audience)));
        return decoder;
    }
    GraphQLContext actor(boolean forged) throws Exception {
        var jwt = decoder("swiftdrop-api").decode(signed("caller", "swiftdrop-api", "ADMIN"));
        var auth = new KeycloakJwtAuthenticationConverter().convert(jwt);
        if (forged) auth = new JwtAuthenticationToken(jwt, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")), "imposter");
        return GraphQLContext.newContext().of("actor", auth, "correlationId", "request_123").build();
    }
    @Test void realStubVerifiesMachineAndSignedActorAndPropagatesDeadlineCorrelation() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        String name = InProcessServerBuilder.generateName();
        var guard = new SwiftDropGrpcServerSecurityInterceptor(new JwtServiceTokenValidator(decoder("swiftdrop-internal")),
                new ActorDelegationValidator(decoder("swiftdrop-api")), new GrpcSecurityPolicy());
        Server server = InProcessServerBuilder.forName(name).directExecutor().intercept(guard)
                .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                    @Override public void getPendingMerchantsSummary(PendingMerchantsSummaryRequest req, StreamObserver<PendingMerchantsSummaryResponse> out) {
                        assertThat(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
                        observed.set(SwiftDropGrpcContext.currentDelegatedActor().orElseThrow().actorSub() + ":" + SwiftDropGrpcContext.currentCorrelationId().orElseThrow());
                        out.onNext(PendingMerchantsSummaryResponse.getDefaultInstance()); out.onCompleted();
                    }
                }).build().start();
        try (var clients = new AuthenticatedReadClients(InProcessChannelBuilder.forName(name).directExecutor().build(),
                () -> { try { return signed("graphql-query-service", "swiftdrop-internal", "ADMIN"); } catch (Exception e) { throw new IllegalStateException(e); } })) {
            var previous = SecurityContextHolder.getContext();
            clients.accounts(actor(false), stub -> stub.getPendingMerchantsSummary(PendingMerchantsSummaryRequest.getDefaultInstance()));
            assertThat(observed.get()).isEqualTo("caller:request_123");
            assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
            assertThat(SwiftDropGrpcContext.currentCorrelationId()).isEmpty();
            var forged = actor(true);
            assertThatThrownBy(() -> clients.accounts(forged, stub -> stub.getPendingMerchantsSummary(PendingMerchantsSummaryRequest.getDefaultInstance())))
                    .isInstanceOf(StatusRuntimeException.class).extracting(e -> Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        } finally { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
    @Test void missingMachineIdentityFailsClosedAndUnavailableProviderIsBounded() throws Exception {
        var context = actor(false);
        try (var clients = new AuthenticatedReadClients(InProcessChannelBuilder.forName("absent-provider").directExecutor().build(), () -> "")) {
            assertThatThrownBy(() -> clients.accounts(context, stub -> stub.getPendingMerchantsSummary(PendingMerchantsSummaryRequest.getDefaultInstance())))
                    .isInstanceOf(StatusRuntimeException.class).extracting(e -> Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        }
        try (var clients = new AuthenticatedReadClients(InProcessChannelBuilder.forName("absent-provider").directExecutor().build(), () -> "test-service-token")) {
            assertThatThrownBy(() -> clients.accounts(context, stub -> stub.getPendingMerchantsSummary(PendingMerchantsSummaryRequest.getDefaultInstance())))
                    .isInstanceOf(StatusRuntimeException.class).extracting(e -> Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        }
    }
    @Test void shorterDeadlineCancelsTheProvider() throws Exception {
        String name = InProcessServerBuilder.generateName();
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        var guard = new SwiftDropGrpcServerSecurityInterceptor(new JwtServiceTokenValidator(decoder("swiftdrop-internal")),
                new ActorDelegationValidator(decoder("swiftdrop-api")), new GrpcSecurityPolicy());
        Server server = InProcessServerBuilder.forName(name).directExecutor().intercept(guard)
                .addService(new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                    @Override public void getPendingMerchantsSummary(PendingMerchantsSummaryRequest req, StreamObserver<PendingMerchantsSummaryResponse> out) {
                        Context.current().addListener(ctx -> cancelled.countDown(), Runnable::run);
                    }
                }).build().start();
        String machine = signed("graphql-query-service", "swiftdrop-internal", "ADMIN");
        try (var client = new AuthenticatedReadClients(InProcessChannelBuilder.forName(name).directExecutor().build(), () -> machine)) {
            var context = actor(false);
            assertThatThrownBy(() -> client.accounts(context, stub -> stub.withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                    .getPendingMerchantsSummary(PendingMerchantsSummaryRequest.getDefaultInstance())))
                    .isInstanceOf(StatusRuntimeException.class).extracting(e -> Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
}
