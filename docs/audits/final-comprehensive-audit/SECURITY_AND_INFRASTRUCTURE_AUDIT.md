# SwiftDrop System Audit — Security Posture & Infrastructure Verification

**Scope:** Keycloak Identity Broker, Spring Security Adapters, Multi-Tenancy Isolation, SSRF Defenses, Terraform Configurations, Helm Charts, and Observability.

---

## 1. Security Architecture & Threat Modeling

### 1.1 Identity & Access Management (IAM)
- **Central Broker:** Keycloak 24+ acting as an OpenID Connect (OIDC) identity provider brokering Google social login and local credentials.
- **Client Configuration:** Single-Page Application (SPA) client `swiftdrop-web` configured with:
  - Standard Authorization Code Flow with **PKCE (S256)** enabled.
  - Client Authentication disabled (public client; zero client secrets in browser code).
  - Short-lived Access Tokens: 5-minute expiry.
  - Refresh Tokens: 30-minute idle timeout with refresh token rotation enabled.
  - Redirect URIs strictly constrained to `http://localhost:5173/auth/callback` and production equivalents.
- **Shared Security Adapter (`shared/security-adapter`):**
  - Contains `JwtAuthenticationFilter`, `RoleHierarchy`, and `TenantContext`.
  - Extracts realm and resource roles from Keycloak JWT payload (`realm_access.roles`, `resource_access.*.roles`).
  - Verifies RSA-256 JWT signatures against Keycloak JWKS endpoint (`/protocol/openid-connect/certs`).
  - Propagates authenticated `SecurityContext` containing user ID (`sub`), email, and roles across Spring Security method guards (`@PreAuthorize`).

### 1.2 Multi-Tenant Isolation & IDOR Defenses
- **Customer Isolation:** Customers can only read and query parcels where `sender_id == sub` or `recipient_phone == user_phone`. Direct queries with arbitrary parcel UUIDs are verified in `DefaultParcelQueryService.java`.
- **Merchant Isolation:** In `MerchantParcelBookingController` and `DefaultParcelQueryService`, access is restricted to parcels matching the authenticated `merchant_id`.
  - **Defect Identified (`INT-005`):** Because Keycloak JWT tokens do not include `merchant_id` by default, browser-based merchants are locked out of their own tenancy unless accessing via an M2M API key.
- **Staff Access:** Staff roles (`ROLE_DISPATCHER`, `ROLE_ADMIN`, `ROLE_COURIER`) require explicit Keycloak realm role assignments.
  - Dispatchers can view parcels across all zones.
  - Couriers can only view parcels assigned to their `courier_id`.

### 1.3 Server-Side Request Forgery (SSRF) Audit (`SEC-001`)
- **Inspection of `WebhookDestinationValidator.java`:**
  - SwiftDrop includes an outbound HTTP webhook validator in `communications-service` designed to prevent merchants from targeting internal services.
  - The validator currently checks:
    - Scheme must be `http` or `https`.
    - Host cannot be `localhost`, `127.0.0.1`, or within standard private IPv4 CIDR blocks (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`).
  - **Vulnerabilities / Gaps Discovered (`SEC-001`):**
    1. **Cloud Metadata IP Missing:** Does not block `169.254.169.254` (AWS, GCP, and Azure IMDS instance metadata endpoint).
    2. **IPv6 Private Ranges Missing:** Does not validate against IPv6 loopback (`::1`), link-local (`fe80::/10`), or IPv4-mapped IPv6 (`::ffff:127.0.0.1`).
    3. **DNS Rebinding Vulnerability:** IP validation occurs during URL registration, but the HTTP client resolves DNS again during actual dispatch. An attacker can use a domain that resolves to a public IP initially and changes to `169.254.169.254` later.
  - **Remediation:** Enforce socket-level IP address verification on connection creation or resolve the address immediately before connection and connect directly to the verified IP with custom `Host` header.

### 1.4 Proof-of-Delivery Asset Security
- Proof-of-delivery photos and digital signatures are handled via `DeliveryProofStorageAdapter`:
  - Files are stored in private S3/MinIO buckets with zero public read permissions.
  - Downloads require a temporary pre-signed URL generated with a 15-minute expiration window.
  - File upload validates MIME types (permitting only `image/jpeg` and `image/png`) and limits payload size to 5 MB.

---

## 2. Infrastructure, DevOps & Zero-Cost Cloud Verification

### 2.1 Strict Zero-Cost Policy ($0 Policy)
- The project adheres to a strict $0 budget constraint documented in `docs/deployment/STRICT_ZERO_COST_HOSTING.md`.
- **Terraform Configuration (`platform/terraform/`):**
  - Syntactically validated and formatted (`terraform fmt -check -recursive`).
  - Implements an explicit Azure spending authorization gate (`issue-178-azure-spending-authorization-gate`).
  - Requires explicit boolean flag `allow_cloud_spending = true` before provisioning any billable cloud resources.
  - Default execution plan targets zero-cost local providers.

### 2.2 Local Container Orchestration (Docker Compose & k3d)
- **Docker Compose (`platform/docker/docker-compose.yml`):**
  - Configures PostgreSQL 16 (with 4 distinct databases: `accounts_db`, `pricing_db`, `delivery_db`, `communications_db`).
  - Configures Apache Kafka + Zookeeper for domain event transport.
  - Configures Keycloak with pre-loaded realm import (`realm-swiftdrop.json`).
  - Configures Redis for rate limiting and cache storage.
  - Configures MinIO for S3-compatible local object storage.
- **Kubernetes Helm Charts (`platform/kubernetes/helm/swiftdrop/`):**
  - Helm chart linted cleanly against `values-staging.yaml` with **0 errors**.
  - Includes Kubernetes Deployments, Services, ConfigMaps, Ingress, and HorizontalPodAutoscalers for all 6 microservices and frontend.
  - Resource requests and limits are explicitly declared on all containers, preventing "noisy neighbor" resource starvation.

### 2.3 Observability & Monitoring
- **Metrics:** Every Spring Boot service exposes Micrometer Prometheus metrics at `/actuator/prometheus`.
- **Health Checks:** Kubernetes liveness and readiness probes are bound to `/actuator/health/liveness` and `/actuator/health/readiness`.
- **Distributed Tracing:** The API Gateway injects `X-Correlation-ID` headers into all outbound requests. Services propagate this ID in SLF4J MDC, enabling centralized log tracing across microservice boundaries.
