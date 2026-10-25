# SwiftDrop Proto Contracts & Generated gRPC Stubs (`shared/proto-contracts`)

**Artifact:** `com.swiftdrop:proto-contracts:0.0.1-SNAPSHOT`<br/>
**Runtime:** Java 21 LTS, Spring Boot 4.1.1, `io.grpc:grpc-bom:1.68.1`<br/>
**Tooling:** `protobuf-maven-plugin:0.6.1`, `protoc:3.25.5`, `protoc-gen-grpc-java:1.68.1`<br/>
**Architecture Reference:** Architecture Baseline v1.3, `contracts/grpc/README.md`, Issue #92

---

## 1. Purpose & Architectural Boundaries

This module compiles canonical Protocol Buffers v3 contracts from `contracts/grpc/swiftdrop/` into strongly typed Java messages and gRPC service client/server stubs.

- **Wire Contracts Exclusively:** This module contains only generated wire DTOs and gRPC service stubs. It contains zero business entities, zero database models, and zero cross-service SQL logic.
- **Microservice Isolation:** Consuming services (`accounts-service`, `pricing-service`, `delivery-service`, `communications-service`, `graphql-query-service`) import this JAR to interact via private, authenticated internal gRPC calls without violating domain boundaries.
- **No Browser Access:** Internal gRPC services are private to backend microservices; browsers connect via Spring Cloud Gateway (`/api/v1` REST, `/graphql` composite read queries, `/api/v1/tracking/{trackingNumber}/events` SSE).

---

## 2. Generated Services & Packages

| Service | Protobuf Package | Java Package | Primary Responsibilities |
|---|---|---|---|
| **Accounts & Partners** | `swiftdrop.accounts.v1` | `com.swiftdrop.accounts.v1` | `CheckCourierEligibility`, `ValidateMerchantKeyDigest`, `ResolveRecipientContact`, `GetPendingMerchantsSummary` |
| **Pricing & Zones** | `swiftdrop.pricing.v1` | `com.swiftdrop.pricing.v1` | `ValidateQuote` |
| **Delivery Operations** | `swiftdrop.delivery.v1` | `com.swiftdrop.delivery.v1` | `GetParcelInternal`, `GetCourierWorkload` |

---

## 3. Build & Test Commands

Run within `shared/proto-contracts/`:

```bash
# Clean and compile (regenerates sources and compiles with javac)
./mvnw clean compile

# Execute unit and contract verification test suite (14 tests)
./mvnw test

# Package and install to local repository cache (~/.m2)
./mvnw clean install
```

On Windows:
```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd test
.\mvnw.cmd clean install
```

---

## 4. Consuming Services Usage

Consuming Spring Boot services can import the generated contracts by adding:

```xml
<dependency>
  <groupId>com.swiftdrop</groupId>
  <artifactId>proto-contracts</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

---

## 5. Security & Delegation Metadata

The helper class `com.swiftdrop.grpc.security.SwiftDropGrpcMetadata` provides constants for inter-service gRPC metadata propagation:

- `x-swiftdrop-service-token`: Service-to-service signed JWT
- `x-swiftdrop-actor-sub`: Delegating human user Keycloak subject
- `x-swiftdrop-actor-roles`: Comma-delimited roles (`ADMIN,DISPATCHER`)
- `x-swiftdrop-correlation-id`: Distributed trace identifier
- `DEFAULT_DEADLINE_MS`: 2000ms bounded timeout
