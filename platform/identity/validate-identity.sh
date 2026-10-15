#!/usr/bin/env bash
# ==============================================================================
# Devarom Platform — Keycloak Identity Configuration & Security Validator
# Validates realm JSON invariants, OIDC discovery, PKCE enforcement, and role security.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REALM_FILE="${SCRIPT_DIR}/realm-devarom.json"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"

echo "=============================================================================="
echo " Devarom Platform — Identity Security Invariant Validation (Issue #2)"
echo "=============================================================================="

# 1. JSON Schema & Parsing Validation
echo -n "[1/7] Validating realm JSON syntax... "
if ! jq empty "${REALM_FILE}" 2>/dev/null; then
  echo "FAIL: ${REALM_FILE} is not valid JSON."
  exit 1
fi
echo "PASS"

# 2. Realm Name Validation
echo -n "[2/7] Checking realm identity... "
REALM_NAME=$(jq -r '.realm' "${REALM_FILE}")
if [[ "${REALM_NAME}" != "devarom" ]]; then
  echo "FAIL: Expected realm 'devarom', found '${REALM_NAME}'."
  exit 1
fi
echo "PASS (realm: ${REALM_NAME})"

# 3. Application Roles & Privileged Elevation Invariants
echo -n "[3/7] Verifying 5 application roles & privilege separation... "
ROLES=$(jq -r '.roles.realm[].name' "${REALM_FILE}")
for REQUIRED_ROLE in CUSTOMER MERCHANT COURIER DISPATCHER ADMIN; do
  if ! echo "${ROLES}" | grep -qx "${REQUIRED_ROLE}"; then
    echo "FAIL: Missing required application role '${REQUIRED_ROLE}'."
    exit 1
  fi
done

# Ensure privileged roles cannot be self-assigned via default roles
DEFAULT_COMPOSITES=$(jq -r '.roles.realm[] | select(.name=="default-roles-devarom") | .composites.realm[]' "${REALM_FILE}")
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
echo -n "[4/7] Checking public client & PKCE security... "
CLIENT=$(jq -r '.clients[] | select(.clientId=="devarom-frontend")' "${REALM_FILE}")

IS_PUBLIC=$(echo "${CLIENT}" | jq -r '.publicClient')
STANDARD_FLOW=$(echo "${CLIENT}" | jq -r '.standardFlowEnabled')
IMPLICIT_FLOW=$(echo "${CLIENT}" | jq -r '.implicitFlowEnabled')
DIRECT_GRANTS=$(echo "${CLIENT}" | jq -r '.directAccessGrantsEnabled')
PKCE_METHOD=$(echo "${CLIENT}" | jq -r '.attributes["pkce.code.challenge.method"]')

if [[ "${IS_PUBLIC}" != "true" ]]; then
  echo "FAIL: devarom-frontend must be a public client."
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
echo -n "[5/7] Verifying redirect URIs & origins... "
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
echo -n "[6/7] Verifying backend claims mapping (audience & roles)... "
AUDIENCE_MAPPER=$(echo "${CLIENT}" | jq -r '.protocolMappers[] | select(.name=="devarom-audience-mapper")')
if [[ -z "${AUDIENCE_MAPPER}" ]]; then
  echo "FAIL: Missing devarom-audience-mapper."
  exit 1
fi
CUSTOM_AUD=$(echo "${AUDIENCE_MAPPER}" | jq -r '.config["included.custom.audience"]')
if [[ "${CUSTOM_AUD}" != "devarom-api" ]]; then
  echo "FAIL: Custom audience must be 'devarom-api'."
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
echo "PASS (aud: devarom-api, roles: realm roles claim)"

# 7. Secret Scanning on Realm Configuration
echo -n "[7/7] Scanning realm file for sensitive secrets/passwords... "
if grep -iE '("password"|"secret")\s*:\s*"[^"]{8,}"' "${REALM_FILE}" | grep -v -E '(GOOGLE_CLIENT_SECRET|\$\{env|\$\{GOOGLE)'; then
  echo "FAIL: Potential hardcoded secret found in ${REALM_FILE}."
  exit 1
fi
echo "PASS (Zero committed secrets/passwords)"

echo ""
echo "=============================================================================="
echo " Static Invariant Checks: ALL 7 PASSED"
echo "=============================================================================="

# Optional Live Container Smoke Test
if curl -s -f -m 3 "${KEYCLOAK_URL}/realms/devarom/.well-known/openid-configuration" >/dev/null 2>&1; then
  echo ""
  echo "Live Keycloak instance detected at ${KEYCLOAK_URL}. Running runtime smoke tests..."
  
  # A. OIDC Discovery
  OIDC_ISSUER=$(curl -s "${KEYCLOAK_URL}/realms/devarom/.well-known/openid-configuration" | jq -r '.issuer')
  echo "  - OIDC Issuer: ${OIDC_ISSUER}"
  if [[ "${OIDC_ISSUER}" != "${KEYCLOAK_URL}/realms/devarom" ]]; then
    echo "    FAIL: Unexpected issuer URL."
    exit 1
  fi
  
  # B. JWKS Verification
  JWKS_KEYS=$(curl -s "${KEYCLOAK_URL}/realms/devarom/protocol/openid-connect/certs" | jq -r '.keys | length')
  echo "  - JWKS Signing Keys Published: ${JWKS_KEYS} key(s)"
  if [[ "${JWKS_KEYS}" -lt 1 ]]; then
    echo "    FAIL: No keys published on JWKS certs endpoint."
    exit 1
  fi

  # C. Negative Security: Direct Grant (ROPC) Rejection
  ROPC_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${KEYCLOAK_URL}/realms/devarom/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=devarom-frontend&username=baduser&password=badpassword")
  echo "  - Negative ROPC Test: HTTP ${ROPC_STATUS} (expected 400/unauthorized_client)"
  if [[ "${ROPC_STATUS}" != "400" ]]; then
    echo "    FAIL: Direct grant was not rejected with HTTP 400."
    exit 1
  fi

  # D. Negative Security: Unauthorized Redirect URI Rejection
  REDIRECT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/realms/devarom/protocol/openid-connect/auth?client_id=devarom-frontend&response_type=code&redirect_uri=http://attacker.com/evil&scope=openid")
  echo "  - Negative Redirect URI Test: HTTP ${REDIRECT_STATUS} (expected 400)"
  if [[ "${REDIRECT_STATUS}" != "400" ]]; then
    echo "    FAIL: Unauthorized redirect URI was not rejected with HTTP 400."
    exit 1
  fi

  # E. Negative Security: Plain PKCE Challenge Rejection
  PLAIN_PKCE_LOC=$(curl -s -i "${KEYCLOAK_URL}/realms/devarom/protocol/openid-connect/auth?client_id=devarom-frontend&response_type=code&redirect_uri=http://localhost:5173/auth/callback&scope=openid&code_challenge=test&code_challenge_method=plain" | grep -i "^location:" || true)
  if echo "${PLAIN_PKCE_LOC}" | grep -q "code+challenge+method+is+not+matching"; then
    echo "  - Negative Plain PKCE Test: Redirected with error 'code challenge method is not matching'"
  else
    echo "    FAIL: Plain PKCE method was not rejected as expected."
    exit 1
  fi

  echo ""
  echo "=============================================================================="
  echo " Live Smoke Tests: ALL PASSED"
  echo "=============================================================================="
fi
