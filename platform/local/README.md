# SwiftDrop — Local Development Environment & Readiness Tooling

This directory provides local infrastructure orchestration and readiness verification tools for the SwiftDrop November 1 initial-login milestone.

---

## 1. Local Architecture & Ports

The local development stack binds to dedicated host ports to prevent port conflicts:

| Service | Port | Protocol | Container / Process | Health Endpoint |
|---|---|---|---|---|
| **Keycloak IdP** | `8090` | HTTP | Container (`swiftdrop-keycloak` / `devarom-keycloak`) | `/realms/swiftdrop/.well-known/openid-configuration` |
| **Mailpit SMTP & UI** | `8025` (UI), `1025` (SMTP) | HTTP / SMTP | Container (`swiftdrop-mailpit` / `devarom-mailpit`) | `http://localhost:8025/` |
| **Accounts PostgreSQL** | `5438` (or `5432`) | TCP | Container (`swiftdrop-accounts-postgres` / `devarom-accounts-postgres`) | Port listen / connection |
| **Accounts Service** | `8081` | HTTP | Spring Boot MVC (`mvnw spring-boot:run`) | `http://localhost:8081/actuator/health` |
| **API Gateway** | `8080` | HTTP | Spring Cloud Gateway (`mvnw spring-boot:run`) | `http://localhost:8080/actuator/health` |
| **React Frontend SPA** | `5173` | HTTP | Vite Dev Server (`pnpm dev`) | `http://localhost:5173/` |

---

## 2. Startup Tooling

### Helper 1: Start Container Infrastructure
```bash
./platform/local/start-local.sh
```
What this script does:
1. Validates that `platform/identity/.env` exists and contains a non-empty `KC_BOOTSTRAP_ADMIN_PASSWORD`.
2. Starts Keycloak (`:8090`) and Mailpit (`:8025`) using `platform/identity/docker-compose.identity.yml`.
3. Starts (or non-destructively initializes) the dedicated PostgreSQL container `swiftdrop-accounts-postgres` (or legacy `devarom-accounts-postgres`) mapped to localhost port 5438 (or 5432).
4. Waits for Keycloak OIDC discovery to become responsive.
5. Verifies and applies runtime policies: branded theme (`apply-theme.sh`), native email verification with 60s cooldown (`configure-email-verification.py`), and RFC 6238 TOTP MFA policy (`configure-totp.py`).
6. Displays the exact commands to run the microservices and frontend in separate terminals.

### Helper 2: Verify Full-Stack Readiness
```bash
./platform/local/check-readiness.sh
```
Performs non-destructive preflight validation across 4 domains:
1. **Toolchain:** Git, Docker daemon, Java 21+, Node.js (20+ / 24 LTS), pnpm, Python 3.
2. **Container Infrastructure:** Keycloak :8090 OIDC discovery, Mailpit :8025 HTTP health, PostgreSQL container port detection.
3. **Application Services:** Accounts Service :8081 `/actuator/health` (including live DB connection), API Gateway :8080 `/actuator/health`, React SPA :5173 HTTP 200.
4. **Security Invariants:** Keycloak branded theme, native email verification required action, and TOTP MFA policy.

---

## 3. Business Microservices Dependencies (Issue #91)

For developers working on Pricing & Zones, Delivery Operations, or Communications services, start the Kafka KRaft, Business PostgreSQL, and Private S3 storage stack:
```bash
./platform/dependencies/start-dependencies.sh
```
See the complete [Business Dependencies Documentation](../dependencies/README.md).

---

## 4. Detailed Startup & Demonstration Runbook

For complete clean-clone instructions, manual browser verification, peer QA handoff, and troubleshooting, see:
- [Clean-Clone Runbook](../../docs/development/CLEAN_CLONE_RUNBOOK.md)
- [Platform November 1 Handoff](../../docs/team/PLATFORM_HANDOFF_NOV01.md)
- [Security E2E Automation Guide](../../tests/security-e2e/README.md)
