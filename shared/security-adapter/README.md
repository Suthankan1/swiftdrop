# SwiftDrop Shared Security Adapter (`shared/security-adapter`)

**Status:** REUSABLE SECURITY ADAPTERS IMPLEMENTED & VERIFIED<br/>
**Task Ref:** Issue [#100 (N10-P02)](https://github.com/Suthankan1/swiftdrop/issues/100)<br/>
**Module Owner:** Platform / Backend (@Suthankan1)<br/>
**Target Runtime:** Java 21 LTS, Spring Boot 4.1.1, Spring Security 6.x, gRPC Java 1.68.1

---

## 1. Overview & Purpose

The `shared/security-adapter` module provides production-grade, reusable security infrastructure for SwiftDrop's distributed microservices:

1. **Reusable OAuth2 Resource Server Configuration (`com.swiftdrop.security.jwt`):**
   - Cryptographic JWT verification against Keycloak OpenID Connect public keys (JWKS).
   - Independent verification of signature, issuer, audience (`swiftdrop-api`), timestamps (`exp`, `nbf`), and structural validity at every service boundary.
   - Strict extraction of approved SwiftDrop business roles (`CUSTOMER`, `MERCHANT`, `COURIER`, `DISPATCHER`, `ADMIN`).
   - Rejection of unapproved, default, or internal Keycloak roles.
   - RFC 7807 Problem Detail responses for 401 Unauthorized and 403 Forbidden.
   - Extensible domain policy hook via `ServiceSecurityCustomizer`.
   - Default-deny security boundary (`anyRequest().denyAll()`).

2. **Machine-to-Machine gRPC Security & Trust Boundaries (`com.swiftdrop.security.service`):**
   - Independent cryptographic service authentication (`ServiceTokenValidator`).
   - Validates `x-swiftdrop-service-token` JWT against internal audience (`swiftdrop-internal`) and trusted issuer.
   - Strongly typed `SwiftDropServiceIdentity` for registered microservices (`DELIVERY_SERVICE`, `PRICING_SERVICE`, `ACCOUNTS_SERVICE`, `COMMUNICATIONS_SERVICE`, `GRAPHQL_QUERY_SERVICE`, `API_GATEWAY`).
   - Enforces per-RPC caller authorization per `docs/contracts/SERVICE_INTERFACE_MATRIX.md`.

3. **Cryptographically Verified Actor Delegation (`com.swiftdrop.security.grpc`):**
   - Prohibits blind trust of caller-forwarded strings (`x-swiftdrop-actor-sub`, `x-swiftdrop-actor-roles`).
   - Enforces cryptographic delegation proof: validates that forwarded actor context matches an accompanying cryptographically signed actor JWT (`x-swiftdrop-actor-token`) or signed delegation claims in the service token.
   - Rejects forged actor subjects, forged roles, and privilege elevation attempts.
   - Strict distinction between service-only background jobs (e.g. `ResolveRecipientContact`) and actor-delegated operations (e.g. `CheckCourierEligibility`, `GetPendingMerchantsSummary`).
   - Fails closed on missing or unverified delegation.

4. **Deadlines & Distributed Tracing:**
   - Client interceptor (`SwiftDropGrpcClientInterceptor`) enforces bounded timeouts (default 2000ms), respecting shorter upstream deadlines.
   - Sanitizes and propagates request correlation IDs (`x-swiftdrop-correlation-id`).
   - Thread-isolated `io.grpc.Context` storage (`SwiftDropGrpcContext`) with automatic cleanup.

---

## 2. Quick Integration Guide for Service Owners

### 2.1 Adding Dependency to Service `pom.xml`

```xml
<dependency>
  <groupId>com.swiftdrop</groupId>
  <artifactId>security-adapter</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 2.2 Enabling Resource Server in a Spring Boot Service

Import `SwiftDropSecurityConfiguration` and provide a `ServiceSecurityCustomizer` bean:

```java
@Configuration
@Import(SwiftDropSecurityConfiguration.class)
public class DeliverySecurityConfig {

    @Bean
    public ServiceSecurityCustomizer deliverySecurityCustomizer() {
        return auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/v1/parcels").hasRole("CUSTOMER")
            .requestMatchers(HttpMethod.GET, "/api/v1/parcels/{id}").hasAnyRole("CUSTOMER", "COURIER", "DISPATCHER", "ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/v1/assignments").hasAnyRole("DISPATCHER", "ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/api/v1/parcels/{id}/status").hasRole("COURIER");
    }
}
```

### 2.3 Securing Internal gRPC Servers

Register `SwiftDropGrpcServerSecurityInterceptor` on your gRPC service builder:

```java
ServiceTokenValidator serviceValidator = new JwtServiceTokenValidator(serviceJwtDecoder);
ActorDelegationValidator actorValidator = new ActorDelegationValidator(userJwtDecoder);

Server server = ServerBuilder.forPort(port)
    .intercept(new SwiftDropGrpcServerSecurityInterceptor(serviceValidator, actorValidator, new GrpcSecurityPolicy()))
    .addService(new AccountsInternalServiceImpl())
    .build()
    .start();
```

### 2.4 Calling Outbound gRPC Services with Verified Delegation

Attach `SwiftDropGrpcClientInterceptor` to your client channel/stub:

```java
AccountsInternalServiceGrpc.AccountsInternalServiceBlockingStub stub =
    AccountsInternalServiceGrpc.newBlockingStub(channel)
        .withInterceptors(new SwiftDropGrpcClientInterceptor(serviceTokenProvider));
```

The client interceptor automatically forwards the authenticated user's credentials from `SecurityContextHolder` (or `SwiftDropGrpcContext`) and applies the bounded 2000ms deadline.

---

## 3. Verification & Test Suite

Run all security adapter unit and integration tests:

```bash
cd shared/security-adapter
./mvnw clean test
```

### Test Coverage Summary:
- `JwtValidationTest` (11 tests): Valid tokens, missing tokens, malformed tokens, untrusted signatures, invalid issuer, wrong audience, expired tokens, not-yet-valid tokens, missing roles, unapproved role filtering, forged forwarding headers rejection.
- `GrpcServiceAuthenticationTest` (7 tests): Valid signed service identity, missing service token, forged service token, expired service token, wrong audience, unauthorized caller service, metadata forwarding without service token.
- `GrpcDelegationSpoofingTest` (10 tests): Valid delegation, missing actor when required, unverified actor forwarding, forged actor subject, forged role elevation, non-admin denied admin RPC, admin allowed admin RPC, service-only rejects synthetic actor, background job without synthetic actor, signed delegation claims.
- `GrpcDeadlineAndCorrelationTest` (6 tests): Default 2000ms deadline, shorter upstream deadline preservation, timeout fail-closed, correlation ID propagation, malformed correlation sanitization, concurrent thread isolation.
- `DirectServiceSecurityTest` (4 tests): Direct service access without token fails 401, forged gateway headers fail 401, valid token succeeds independently, insufficient role fails 403.
