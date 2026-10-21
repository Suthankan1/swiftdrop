# SwiftDrop — Phase 3C Complete Technical Rebranding Audit
**Document:** Technical Namespace, Package, Maven, Keycloak Theme, Container & Configuration Audit  
**Author:** Suthankan1 (Principal Software Architect & Platform Lead)  
**Date:** 2026-10-21  
**Milestone:** Phase 3C Technical Rebrand Finalization  
**Repository:** [https://github.com/Suthankan1/swiftdrop](https://github.com/Suthankan1/swiftdrop)  
**Target Integration Branch:** `test`  

---

## 1. Executive Summary

Phase 3C completes the technical naming migration from **Devarom** to **SwiftDrop** across all active codebases, Maven coordinates, Java packages, Keycloak login themes, container naming conventions, application metadata, test fixtures, and developer runbooks prior to parallel team development.

### Canonical Technical Architecture (SwiftDrop Baseline v1.3)
- **Official Product Name:** `SwiftDrop`
- **GitHub Repository:** `Suthankan1/swiftdrop` (`https://github.com/Suthankan1/swiftdrop.git`)
- **Root Java Group:** `com.swiftdrop`
- **Java Package Roots:** `com.swiftdrop.apigateway`, `com.swiftdrop.accountsservice`
- **Keycloak Realm Name:** `swiftdrop` (canonical public OIDC realm)
- **OIDC Client ID:** `swiftdrop-frontend`
- **JWT Audience:** `swiftdrop-api`
- **Keycloak Login Theme:** `swiftdrop` (`platform/identity/themes/swiftdrop`)
- **Canonical Fresh-Install Containers:** `swiftdrop-keycloak`, `swiftdrop-mailpit`, `swiftdrop-accounts-postgres`
- **Frontend Package Name:** `@swiftdrop/frontend`

---

## 2. Classified Rename Inventory

Every occurrence of the legacy name across tracked files has been audited and classified according to the approved classification framework:

| Category Code | Classification | Description | Action Taken |
|---|---|---|---|
| **A** | Must rename: active technical identifier | Java packages, imports, Maven groupId, package.json | Renamed to `com.swiftdrop`, `@swiftdrop/frontend` |
| **B** | Must rename: current visible branding | Login theme, UI labels, brand references | Created `swiftdrop` theme, updated UI copy |
| **C** | Must rename: fresh installation name | Canonical container names, fresh realm config | `swiftdrop-*` containers, `realm-swiftdrop.json` |
| **D** | Retain: existing persistent-data identifier | Keycloak internal realm ID, existing container names | Preserved to guarantee zero subject UUID or profile loss |
| **E** | Retain: historical Git/provenance evidence | Phase 1/2 audit logs, ADR history, CSV records | Preserved as immutable audit trail |
| **F** | Retain: external provider-controlled value | Google OAuth project ID / historical client name | Preserved outside Git in external console |
| **G** | Retain: security rejection test data | Malformed/legacy token rejection fixtures | Preserved in security negative test suites |

---

## 3. Java Package Refactoring

All Java source and test files were migrated from namespace `com.devarom.*` to `com.swiftdrop.*` using Git filesystem moves (`git mv`) without duplicating packages:

### 3.1 API Gateway Service (`services/api-gateway`)
- **Moved Directory:** `src/main/java/com/devarom/apigateway` ➔ `src/main/java/com/swiftdrop/apigateway`
- **Moved Directory:** `src/test/java/com/devarom/apigateway` ➔ `src/test/java/com/swiftdrop/apigateway`
- **Files Migrated (11 files):**
  - `ApiGatewayApplication.java`
  - `config/GatewayRoutesConfig.java`
  - `filter/GatewayErrorWebExceptionHandler.java`
  - `filter/StripUntrustedHeadersGatewayFilter.java`
  - `security/AudienceValidator.java`
  - `security/ProblemDetailAccessDeniedHandler.java`
  - `security/ProblemDetailAuthenticationEntryPoint.java`
  - `security/SecurityConfig.java`
  - `ApiGatewayApplicationTests.java`
  - `ApiGatewayDownstreamUnavailableTest.java`
  - `ApiGatewaySecurityAndRoutingTest.java`
  - `SwiftDropTargetContractGatewayTest.java`
- **Verification:** All 19 Spring Cloud Gateway tests PASS.

### 3.2 Accounts & Partners Service (`services/accounts-service`)
- **Moved Directory:** `src/main/java/com/devarom/accountsservice` ➔ `src/main/java/com/swiftdrop/accountsservice`
- **Moved Directory:** `src/test/java/com/devarom/accountsservice` ➔ `src/test/java/com/swiftdrop/accountsservice`
- **Files Migrated (23 files):**
  - `AccountsServiceApplication.java`
  - `identity/CurrentIdentityProvider.java`
  - `identity/TrustedIdentity.java`
  - `profile/AccountProfile.java`
  - `profile/AccountProfileRepository.java`
  - `profile/CurrentUserProfile.java`
  - `profile/CurrentUserProfileController.java`
  - `profile/CurrentUserProfileService.java`
  - `profile/ProfileProblemHandler.java`
  - `security/AudienceValidator.java`
  - `security/KeycloakJwtAuthenticationConverter.java`
  - `security/KeycloakRoleExtractor.java` (defines `SWIFTDROP_ROLES` as canonical, `DEVAROM_ROLES` alias)
  - `security/ProfileSecurityConfiguration.java`
  - `security/SecurityContextCurrentIdentityProvider.java`
  - `security/SubjectValidator.java`
  - `AccountProfilePostgresIT.java`
  - `AccountsServiceApplicationTest.java`
  - `CurrentUserProfileContractTest.java`
  - `CurrentUserProfileServiceTest.java`
  - `ProfileHttpSecurityTest.java`
  - `SwiftDropTargetContractAccountsTest.java`
  - `TrustedIdentityTest.java`
- **Verification:** All 44 Accounts unit, MVC, and PostgreSQL integration tests PASS (63 total with slice variations).

---

## 4. Maven Coordinates & Project Metadata

Both Spring Boot service POMs were updated:
- `<groupId>com.swiftdrop</groupId>` (migrated from `com.devarom`)
- `<name>` updated to official service names (`api-gateway`, `accounts-service`)
- `<description>` updated to SwiftDrop platform descriptions
- `<scm><url>` and `<developerConnection>` updated to `https://github.com/Suthankan1/swiftdrop.git`
- Artifact IDs (`api-gateway`, `accounts-service`) retained per specification.

---

## 5. Keycloak Login Theme Migration

### 5.1 Theme Directory & Assets
- Created `platform/identity/themes/swiftdrop` containing:
  - `theme.properties` (`styles=css/styles.css css/swiftdrop.css`)
  - `css/swiftdrop.css` (custom brand stylesheet with dual `.swiftdrop-*` and `.devarom-*` class selectors for pixel-perfect backward compatibility)
  - `img/swiftdrop-logo.svg`, `img/swiftdrop-mark.svg`, `img/favicon.ico`, `img/favicon.svg`
  - `template.ftl`, `social-providers.ftl`, `footer.ftl`
  - `messages/messages_en.properties`
- Retained `platform/identity/themes/devarom` for live container mounting and emergency rollback.

### 5.2 Canonical Configuration & Live Theme Switch
- Updated canonical `platform/identity/realm-swiftdrop.json` to `"loginTheme": "swiftdrop"`.
- Updated `platform/identity/apply-theme.sh` with dual container detection and dynamic theme sync.
- Executed `apply-theme.sh` against running Keycloak container: verified live realm `swiftdrop` updated its `loginTheme` attribute to `swiftdrop`.
- Tested isolated fresh-install Keycloak container on port `8099`: verified `swiftdrop` theme loads cleanly on fresh import.

---

## 6. Docker Container & Storage Safety Strategy

### 6.1 Two Operational Scenarios
- **Scenario A: Existing Migrated Local Environment**
  - Running Keycloak container: `devarom-keycloak` (H2 embedded storage in writable layer)
  - Running Mailpit container: `devarom-mailpit`
  - Running Postgres container: `devarom-accounts-postgres` (volume: `devarom-accounts-postgres-data`)
  - **Invariant:** Existing containers are NEVER removed (`docker rm`), down-wiped (`docker compose down -v`), or recreated. Parameterized environment variables (`${KEYCLOAK_CONTAINER_NAME:-swiftdrop-keycloak}`) in `docker-compose.identity.yml` read local `.env` overrides, preserving running containers without downtime or identity loss.
- **Scenario B: Brand-New Fresh Installation**
  - Fresh installs default to canonical names: `swiftdrop-keycloak`, `swiftdrop-mailpit`, `swiftdrop-accounts-postgres`, and volume `swiftdrop-accounts-postgres-data`.
  - Tooling (`start-local.sh`, `check-readiness.sh`, `run-security-e2e.sh`, `backup-restore-identity.py`) automatically discovers both `swiftdrop-*` and `devarom-*` containers.

---

## 7. Residual Legacy Reference Inventory

Every remaining reference to `devarom` in the repository has been audited and cataloged below:

| Location / File Pattern | Occurrence Role | Justification | Residual Category |
|---|---|---|---|
| `docs/branding/NAME_OCCURRENCES.csv` | Historical audit artifact | Comprehensive line-by-line record of pre-migration state; immutable audit evidence | **E** (Provenance) |
| `docs/branding/PHASE_1_AUDIT.md`, `PHASE_2_*.md` | Milestone signoff docs | Historical migration records tracking execution phases | **E** (Provenance) |
| `docs/architecture/adr/ADR-000*.md` | Architecture Decision Records | Formal records of historical team decisions made prior to rename | **E** (Provenance) |
| `platform/identity/realm-devarom.json` | Historical realm import | Fallback and migration proof artifact; clearly labeled as legacy | **E** (Provenance) |
| `platform/identity/themes/devarom/` | Legacy theme folder | Kept mounted in Docker for rollback safety and running container compatibility | **D** / **E** (Rollback) |
| `platform/identity/rehearse-identity-migration.py` | Migration test script | Rehearsal engine that validates migration from devarom to swiftdrop | **E** (Migration tool) |
| `platform/identity/cutover-identity-migration.py` | Migration execution script | Cutover script that executes migration from devarom to swiftdrop | **E** (Migration tool) |
| `services/**/SwiftDropTargetContract*Test.java` | Security rejection tests | Tests asserting strict 401 rejection of legacy `devarom` issuer and `devarom-api` audience | **G** (Rejection test) |
| `services/**/ApiGatewaySecurityAndRoutingTest.java` | Security rejection tests | Negative tests validating rejection of untrusted issuers | **G** (Rejection test) |
| `services/**/ProfileHttpSecurityTest.java` | Negative security tests | Negative tests asserting rejection of legacy test tokens | **G** (Rejection test) |
| `services/**/application.yml` (`devarom.jwt.*`) | Configuration fallback | Backward-compatibility configuration property fallback | **D** (Rollback config) |
| `platform/local/*.sh`, `tests/**/*.sh` | Container discovery | Dual-container checks (`swiftdrop-*` with fallback to `devarom-*`) | **D** (Data continuity) |
| `platform/identity/backup-restore-identity.py` | Disaster recovery tool | Checks active container (`swiftdrop-keycloak` or `devarom-keycloak`) and protects both | **D** (Safety gate) |
| `frontend/src/auth/contracts.ts` (`DevaromRole`) | Type alias | Backward-compatibility alias pointing to `SwiftDropRole` | **D** (Compatibility) |
| `services/.../KeycloakRoleExtractor.java` (`DEVAROM_ROLES`) | Constant alias | Backward-compatibility alias pointing to `SWIFTDROP_ROLES` | **D** (Compatibility) |

---

## 8. Unacceptable Reference Verification (Zero Remaining)

The following unacceptable residual categories were verified to have **ZERO active occurrences**:
1. Active frontend brand text: **0 matches** (All user-facing views render SwiftDrop)
2. Current Maven groupId: **0 matches** (`com.swiftdrop` in all active POMs)
3. Current Java package namespace: **0 active classes under `com.devarom`** (all 34 files moved to `com.swiftdrop.*`)
4. Current default OIDC realm: **`swiftdrop`** (all active services and scripts target `swiftdrop`)
5. Current default client ID: **`swiftdrop-frontend`**
6. Current default JWT audience: **`swiftdrop-api`**
7. Current fresh-install theme identifier: **`swiftdrop`**
8. Active documentation pointing developers to the wrong GitHub repository: **0 matches** (`Suthankan1/swiftdrop` everywhere)
9. Newly scaffolded code using `com.devarom`: **0 matches**
10. Incorrect current application metadata: **0 matches**

---

## 9. Final Verification Status

- **Code-level rename status:** `COMPLETE`
- **Runtime/storage rename status:** `COMPLETE` (Dual-mode: canonical fresh installs + non-destructive legacy container continuity)
- **Phase 3 technical alignment status:** `COMPLETE`
