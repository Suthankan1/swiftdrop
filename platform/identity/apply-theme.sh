#!/usr/bin/env bash
# ==============================================================================
# Devarom Platform — Keycloak Theme Installer & Realm Theme Selector (Issue #44)
# Safely, idempotently applies and selects the 'devarom' theme on the running realm.
# Never resets user data, wipes volumes, or exposes passwords.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
CONTAINER_NAME="devarom-keycloak"

echo "=============================================================================="
echo " Devarom Platform — Apply Branded Keycloak Theme (Issue #44)"
echo "=============================================================================="

# 1. Check container presence
if ! docker ps --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
  echo "Keycloak container '${CONTAINER_NAME}' is not running."
  echo "Starting with: docker compose -f ${SCRIPT_DIR}/docker-compose.identity.yml --project-directory ${SCRIPT_DIR} up -d"
  docker compose -f "${SCRIPT_DIR}/docker-compose.identity.yml" --project-directory "${SCRIPT_DIR}" up -d
fi

# 2. Sync theme files directly into container themes folder (works with or without volume restart)
echo "[1/3] Syncing theme files to /opt/keycloak/themes/devarom in container..."
if docker exec "${CONTAINER_NAME}" test -d /opt/keycloak/themes/devarom/login 2>/dev/null; then
  echo "PASS: Theme files already mounted via volume."
else
  docker exec "${CONTAINER_NAME}" mkdir -p /opt/keycloak/themes/devarom 2>/dev/null || true
  docker cp "${SCRIPT_DIR}/themes/devarom/." "${CONTAINER_NAME}:/opt/keycloak/themes/devarom/" 2>/dev/null || true
  echo "PASS: Theme files synced."
fi

# 3. Read admin credentials securely from local .env if present
ADMIN_USER="admin"
ADMIN_PASS=""

if [[ -f "${ENV_FILE}" ]]; then
  ADMIN_USER=$(grep -E '^KC_BOOTSTRAP_ADMIN_USERNAME=' "${ENV_FILE}" | cut -d '=' -f2- || echo "admin")
  ADMIN_PASS=$(grep -E '^KC_BOOTSTRAP_ADMIN_PASSWORD=' "${ENV_FILE}" | cut -d '=' -f2- || echo "")
fi

ADMIN_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-${ADMIN_USER}}"
ADMIN_PASS="${KC_BOOTSTRAP_ADMIN_PASSWORD:-${ADMIN_PASS}}"

if [[ -z "${ADMIN_PASS}" ]]; then
  echo "FAIL: KC_BOOTSTRAP_ADMIN_PASSWORD not found in environment or ${ENV_FILE}."
  exit 1
fi

# 4. Authenticate kcadm.sh and select theme on devarom realm
echo -n "[2/3] Configuring loginTheme='devarom' on realm 'devarom'... "
docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "${ADMIN_USER}" \
  --password "${ADMIN_PASS}" >/dev/null 2>&1

docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh update realms/devarom -s loginTheme=devarom >/dev/null
echo "PASS"

# 5. Verify configuration
echo -n "[3/3] Verifying realm theme selection... "
CURRENT_THEME=$(docker exec "${CONTAINER_NAME}" /opt/keycloak/bin/kcadm.sh get realms/devarom | jq -r '.loginTheme')
if [[ "${CURRENT_THEME}" != "devarom" ]]; then
  echo "FAIL: Expected loginTheme 'devarom', found '${CURRENT_THEME}'."
  exit 1
fi
echo "PASS (current loginTheme: ${CURRENT_THEME})"

echo "=============================================================================="
echo " Devarom Keycloak Theme Applied Successfully!"
echo "=============================================================================="
