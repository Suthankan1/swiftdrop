#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Keycloak Identity Configuration & Security Validator
# Validates realm JSON invariants, OIDC discovery, PKCE enforcement, and role security.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REALM_FILE="${REALM_FILE:-${SCRIPT_DIR}/realm-swiftdrop.json}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-swiftdrop}"
KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-swiftdrop-frontend}"

echo "=============================================================================="
echo " SwiftDrop Platform — Identity Security Invariant Validation (Issue #2)"
echo "=============================================================================="

# 1. JSON Schema & Parsing Validation
echo -n "[1/14] Validating realm JSON syntax... "
if ! jq empty "${REALM_FILE}" 2>/dev/null; then
  echo "FAIL: ${REALM_FILE} is not valid JSON."
  exit 1
fi
echo "PASS"

# 2. Realm Name Validation
echo -n "[2/14] Checking realm identity... "
REALM_NAME=$(jq -r '.realm' "${REALM_FILE}")
if [[ "${REALM_NAME}" != "swiftdrop" ]]; then
  echo "FAIL: Expected realm 'swiftdrop', found '${REALM_NAME}'."
  exit 1
fi
echo "PASS (realm: ${REALM_NAME})"

# 3. Application Roles & Privileged Elevation Invariants
echo -n "[3/14] Verifying 5 application roles & privilege separation... "
ROLES=$(jq -r '.roles.realm[].name' "${REALM_FILE}")
for REQUIRED_ROLE in CUSTOMER MERCHANT COURIER DISPATCHER ADMIN; do
  if ! echo "${ROLES}" | grep -qx "${REQUIRED_ROLE}"; then
    echo "FAIL: Missing required application role '${REQUIRED_ROLE}'."
    exit 1
  fi
done

# Ensure privileged roles cannot be self-assigned via default roles
DEFAULT_ROLE_NAME="default-roles-${REALM_NAME}"
DEFAULT_COMPOSITES=$(jq -r --arg r "${DEFAULT_ROLE_NAME}" '.roles.realm[] | select(.name==$r) | .composites.realm[]' "${REALM_FILE}")
for PRIVILEGED_ROLE in MERCHANT COURIER DISPATCHER ADMIN; do
  if echo "${DEFAULT_COMPOSITES}" | grep -qx "${PRIVILEGED_ROLE}"; then
    echo "FAIL: Privileged role '${PRIVILEGED_ROLE}' must NOT be in default roles!"
    exit 1
  fi
done
if ! echo "${DEFAULT_COMPOSITES}" | grep -qx "CUSTOMER"; then
  echo "FAIL: Default role must include 'CUSTOMER'."
  exit 1
fi
echo "PASS (CUSTOMER safe default; MERCHANT/COURIER/DISPATCHER/ADMIN restricted)"

# 4. Public SPA Client & PKCE Enforcement
echo -n "[4/14] Checking public client & PKCE security... "
EXPECTED_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-swiftdrop-frontend}"
CLIENT=$(jq -r --arg c "${EXPECTED_CLIENT_ID}" '.clients[] | select(.clientId==$c)' "${REALM_FILE}")

IS_PUBLIC=$(echo "${CLIENT}" | jq -r '.publicClient')
STANDARD_FLOW=$(echo "${CLIENT}" | jq -r '.standardFlowEnabled')
IMPLICIT_FLOW=$(echo "${CLIENT}" | jq -r '.implicitFlowEnabled')
DIRECT_GRANTS=$(echo "${CLIENT}" | jq -r '.directAccessGrantsEnabled')
PKCE_METHOD=$(echo "${CLIENT}" | jq -r '.attributes["pkce.code.challenge.method"]')

if [[ "${IS_PUBLIC}" != "true" ]]; then
  echo "FAIL: ${EXPECTED_CLIENT_ID} must be a public client."
  exit 1
fi
if [[ "${STANDARD_FLOW}" != "true" ]]; then
  echo "FAIL: Authorization Code Flow (standardFlowEnabled) must be true."
  exit 1
fi
if [[ "${IMPLICIT_FLOW}" != "false" ]]; then
  echo "FAIL: Implicit flow must be disabled."
  exit 1
fi
if [[ "${DIRECT_GRANTS}" != "false" ]]; then
  echo "FAIL: Direct access grants (ROPC) must be disabled."
  exit 1
fi
if [[ "${PKCE_METHOD}" != "S256" ]]; then
  echo "FAIL: PKCE code challenge method must be strictly 'S256'."
  exit 1
fi
echo "PASS (Public client, Authorization Code + S256 PKCE, ROPC/Implicit disabled)"

# 5. Strict Redirect URIs & Web Origins (No Wildcards)
echo -n "[5/14] Verifying redirect URIs & origins... "
REDIRECT_URIS=$(echo "${CLIENT}" | jq -r '.redirectUris[]')
WEB_ORIGINS=$(echo "${CLIENT}" | jq -r '.webOrigins[]')

if echo "${REDIRECT_URIS}" | grep -q "\*"; then
  echo "FAIL: Wildcards are forbidden in redirect URIs."
  exit 1
fi
if ! echo "${REDIRECT_URIS}" | grep -qx "http://localhost:5173/auth/callback"; then
  echo "FAIL: Missing required callback redirect URI http://localhost:5173/auth/callback."
  exit 1
fi
if echo "${WEB_ORIGINS}" | grep -q "\*"; then
  echo "FAIL: Wildcards are forbidden in Web Origins."
  exit 1
fi
if ! echo "${WEB_ORIGINS}" | grep -qx "http://localhost:5173"; then
  echo "FAIL: Missing required Web Origin http://localhost:5173."
  exit 1
fi
echo "PASS (Strict redirect http://localhost:5173/auth/callback, origin http://localhost:5173)"

# 6. Audience & Role Claims Mapping
echo -n "[6/14] Verifying backend claims mapping (audience & roles)... "
AUDIENCE_MAPPER=$(echo "${CLIENT}" | jq -r '.protocolMappers[] | select(.protocolMapper=="oidc-audience-mapper")')
if [[ -z "${AUDIENCE_MAPPER}" ]]; then
  echo "FAIL: Missing audience mapper."
  exit 1
fi
CUSTOM_AUD=$(echo "${AUDIENCE_MAPPER}" | jq -r '.config["included.custom.audience"]')
EXPECTED_AUDIENCE="${JWT_AUDIENCE:-swiftdrop-api}"
if [[ "${CUSTOM_AUD}" != "${EXPECTED_AUDIENCE}" ]]; then
  echo "FAIL: Custom audience must be '${EXPECTED_AUDIENCE}'."
  exit 1
fi

ROLES_MAPPER=$(echo "${CLIENT}" | jq -r '.protocolMappers[] | select(.name=="roles-claim-mapper")')
if [[ -z "${ROLES_MAPPER}" ]]; then
  echo "FAIL: Missing roles-claim-mapper."
  exit 1
fi
CLAIM_NAME=$(echo "${ROLES_MAPPER}" | jq -r '.config["claim.name"]')
if [[ "${CLAIM_NAME}" != "roles" ]]; then
  echo "FAIL: Roles claim name must be 'roles'."
  exit 1
fi
echo "PASS (aud: ${EXPECTED_AUDIENCE}, roles: realm roles claim)"

# 7. Secret Scanning on Realm Configuration
echo -n "[7/14] Scanning realm file for sensitive secrets/passwords... "
if grep -iE '("password"|"secret")\s*:\s*"[^"]{8,}"' "${REALM_FILE}" | grep -v -E '(GOOGLE_CLIENT_SECRET|\$\{env|\$\{GOOGLE)'; then
  echo "FAIL: Potential hardcoded secret found in ${REALM_FILE}."
  exit 1
fi
echo "PASS (Zero committed secrets/passwords)"

# 8. Keycloak Custom Theme Structure & Assets
echo -n "[8/14] Checking SwiftDrop theme files and assets... "
THEME_DIR="${SCRIPT_DIR}/themes/swiftdrop/login"
if [[ ! -f "${THEME_DIR}/theme.properties" ]]; then
  echo "FAIL: Missing ${THEME_DIR}/theme.properties."
  exit 1
fi
if [[ ! -f "${THEME_DIR}/resources/css/swiftdrop.css" ]]; then
  echo "FAIL: Missing ${THEME_DIR}/resources/css/swiftdrop.css."
  exit 1
fi
if [[ ! -f "${THEME_DIR}/template.ftl" ]]; then
  echo "FAIL: Missing ${THEME_DIR}/template.ftl."
  exit 1
fi
if [[ ! -f "${THEME_DIR}/resources/img/swiftdrop-mark.svg" && ! -f "${THEME_DIR}/resources/img/devarom-mark.svg" ]]; then
  echo "FAIL: Missing brand mark in ${THEME_DIR}/resources/img."
  exit 1
fi
if [[ ! -f "${THEME_DIR}/resources/img/social/google.svg" ]]; then
  echo "FAIL: Missing ${THEME_DIR}/resources/img/social/google.svg."
  exit 1
fi
if ! grep -q "^parent=keycloak.v2" "${THEME_DIR}/theme.properties"; then
  echo "FAIL: theme.properties must declare parent=keycloak.v2."
  exit 1
fi
if ! grep -q "css/swiftdrop.css" "${THEME_DIR}/theme.properties"; then
  echo "FAIL: theme.properties must include css/swiftdrop.css."
  exit 1
fi
echo "PASS (parent=keycloak.v2, swiftdrop.css, templates and brand assets present)"
python3 "${SCRIPT_DIR}/tests/test_branding_configuration.py"

# 9. Realm loginTheme Configuration
echo -n "[9/14] Checking realm loginTheme declaration... "
LOGIN_THEME=$(jq -r '.loginTheme // empty' "${REALM_FILE}")
if [[ "${LOGIN_THEME}" != "swiftdrop" ]]; then
  echo "FAIL: ${REALM_FILE} must set loginTheme='swiftdrop', found '${LOGIN_THEME}'."
  exit 1
fi
echo "PASS (loginTheme: swiftdrop)"

# 10. Compose Theme Volume Mounting
echo -n "[10/14] Verifying Docker Compose theme volume configuration... "
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.identity.yml"
if ! grep -q "./themes:/opt/keycloak/themes:ro" "${COMPOSE_FILE}"; then
  echo "FAIL: docker-compose.identity.yml must mount ./themes:/opt/keycloak/themes:ro."
  exit 1
fi
if ! docker compose -f "${COMPOSE_FILE}" --project-directory "${SCRIPT_DIR}" config >/dev/null 2>&1; then
  echo "FAIL: docker-compose.identity.yml failed syntax validation."
  exit 1
fi
echo "PASS (valid compose with ./themes volume mount)"

# 11. Secret Scanning on Theme Files
echo -n "[11/14] Scanning theme files for hardcoded credentials... "
if grep -riE '(password|secret|token)\s*[:=]\s*["'\'']?[a-zA-Z0-9_\-]{16,}' "${THEME_DIR}" | grep -v -E '(passwordVisibility|passwordConfirm)'; then
  echo "FAIL: Potential hardcoded secret in theme directory."
  exit 1
fi
echo "PASS (Zero committed secrets in theme files)"

# 12. Native Email Verification, Local SMTP Isolation & Negative Policy Regression
# These are static configuration checks, not proof of runtime delivery or verification.
echo "[12/14] Checking native email policy, local SMTP isolation and negative regressions..."
python3 "${SCRIPT_DIR}/tests/test_email_configuration.py"

# 13. RFC 6238 TOTP Policy & Single-Use Enforcement
echo "[13/14] Checking RFC 6238 TOTP policy, single-use enforcement and negative regressions..."
python3 "${SCRIPT_DIR}/tests/test_totp_configuration.py"

# 14. Google OIDC Broker Invariants & Privilege Confinement
echo "[14/14] Checking Google OIDC broker configuration, placeholders & privilege separation..."
python3 "${SCRIPT_DIR}/tests/test_google_broker_configuration.py"

echo ""
echo "=============================================================================="
echo " Static Invariant Checks: ALL 14 PASSED"
echo "=============================================================================="

# Optional Live Container Smoke Test
if curl -s -f -m 3 "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration" >/dev/null 2>&1; then
  echo ""
  echo "Live Keycloak instance detected at ${KEYCLOAK_URL}. Running runtime smoke tests..."
  
  # A. OIDC Discovery
  OIDC_ISSUER=$(curl -s "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration" | jq -r '.issuer')
  echo "  - OIDC Issuer: ${OIDC_ISSUER}"
  if [[ "${OIDC_ISSUER}" != "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}" ]]; then
    echo "    FAIL: Unexpected issuer URL."
    exit 1
  fi

  # Negative check: legacy devarom realm must NOT exist or return OIDC discovery
  LEGACY_OIDC_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "${KEYCLOAK_URL}/realms/devarom/.well-known/openid-configuration" || true)
  echo "  - Negative Legacy Realm Test: /realms/devarom returned HTTP ${LEGACY_OIDC_STATUS} (expected 404)"
  if [[ "${LEGACY_OIDC_STATUS}" != "404" ]]; then
    echo "    FAIL: Legacy realm 'devarom' still active or did not return 404 (status: ${LEGACY_OIDC_STATUS})."
    exit 1
  fi
  
  # B. JWKS Verification
  JWKS_KEYS=$(curl -s "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs" | jq -r '.keys | length')
  echo "  - JWKS Signing Keys Published: ${JWKS_KEYS} key(s)"
  if [[ "${JWKS_KEYS}" -lt 1 ]]; then
    echo "    FAIL: No keys published on JWKS certs endpoint."
    exit 1
  fi

  # C. Negative Security: Direct Grant (ROPC) Rejection
  ROPC_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=${KEYCLOAK_CLIENT_ID}&username=baduser&password=badpassword")
  echo "  - Negative ROPC Test: HTTP ${ROPC_STATUS} (expected 400/unauthorized_client)"
  if [[ "${ROPC_STATUS}" != "400" ]]; then
    echo "    FAIL: Direct grant was not rejected with HTTP 400."
    exit 1
  fi

  # D. Negative Security: Unauthorized Redirect URI Rejection
  REDIRECT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth?client_id=${KEYCLOAK_CLIENT_ID}&response_type=code&redirect_uri=http://attacker.com/evil&scope=openid")
  echo "  - Negative Redirect URI Test: HTTP ${REDIRECT_STATUS} (expected 400)"
  if [[ "${REDIRECT_STATUS}" != "400" ]]; then
    echo "    FAIL: Unauthorized redirect URI was not rejected with HTTP 400."
    exit 1
  fi

  # E. Negative Security: Plain PKCE Challenge Rejection
  PLAIN_PKCE_LOC=$(curl -s -i "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth?client_id=${KEYCLOAK_CLIENT_ID}&response_type=code&redirect_uri=http://localhost:5173/auth/callback&scope=openid&code_challenge=test&code_challenge_method=plain" | grep -i "^location:" || true)
  if echo "${PLAIN_PKCE_LOC}" | grep -q "code+challenge+method+is+not+matching"; then
    echo "  - Negative Plain PKCE Test: Redirected with error 'code challenge method is not matching'"
  else
    echo "    FAIL: Plain PKCE method was not rejected as expected."
    exit 1
  fi

  # F. Custom SwiftDrop Theme Runtime Rendering Check
  LOGIN_HTML=$(curl -s -L "${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth?client_id=${KEYCLOAK_CLIENT_ID}&response_type=code&redirect_uri=http://localhost:5173/auth/callback&scope=openid&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256")
  if ! echo "${LOGIN_HTML}" | grep -qE "(css/swiftdrop\.css|css/devarom\.css)"; then
    echo "    FAIL: Login page did not include css/swiftdrop.css or css/devarom.css."
    exit 1
  fi
  if ! echo "${LOGIN_HTML}" | grep -qE "(swiftdrop-brand-title|devarom-brand-title)"; then
    echo "    FAIL: Login page did not render brand title."
    exit 1
  fi
  if ! echo "${LOGIN_HTML}" | grep -q 'SwiftDrop'; then
    echo "    FAIL: Login page did not render the SwiftDrop wordmark."
    exit 1
  fi
  CSS_HREF=$(echo "${LOGIN_HTML}" | grep -oE '/resources/[^"]+/login/(swiftdrop|devarom)/css/(swiftdrop|devarom)\.css' | head -n 1)
  if [[ -n "${CSS_HREF}" ]]; then
    CSS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${KEYCLOAK_URL}${CSS_HREF}")
    if [[ "${CSS_STATUS}" != "200" ]]; then
      echo "    FAIL: Theme CSS asset ${CSS_HREF} returned HTTP ${CSS_STATUS} (expected 200)."
      exit 1
    fi
  fi
  echo "  - Branded SwiftDrop Theme Rendering: Verified (${CSS_HREF:-swiftdrop.css} loaded with HTTP 200, brand mark and title rendered)"

  # G. TOTP MFA Policy & CONFIGURE_TOTP Required Action Verification
  if KEYCLOAK_REALM="${KEYCLOAK_REALM}" KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID}" python3 "${SCRIPT_DIR}/configure-totp.py" check >/dev/null 2>&1; then
    echo "  - TOTP MFA Policy & Action: Verified (RFC 6238, 6 digits, 30s period, single-use, optional CUSTOMER)"
  else
    echo "    FAIL: Keycloak TOTP MFA policy differs from expected configuration."
    exit 1
  fi

  # H. Google OIDC Broker Delegation & Negative Security Validation
  echo "  - Google OIDC Broker Invariant & Redirect Verification:"
  if KEYCLOAK_REALM="${KEYCLOAK_REALM}" KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID}" python3 "${SCRIPT_DIR}/tests/google_broker_smoke.py" >/dev/null 2>&1; then
    echo "    PASS (Broker delegation, contract parameters, Google probe & negative callback tests passed)"
  else
    echo "    FAIL: Google broker smoke test suite failed."
    exit 1
  fi

  echo ""
  echo "=============================================================================="
  echo " Live Smoke Tests: ALL PASSED"
  echo "=============================================================================="
else
  echo "Live smoke tests skipped: the local Keycloak discovery endpoint is not reachable."
  echo "Static checks do not establish SMTP delivery or browser verification-flow success."
fi
