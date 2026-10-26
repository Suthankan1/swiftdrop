#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Local Development Secret Provisioner (Issue #149)
# Provisions external Kubernetes Secret objects for local cluster testing.
# Follows platform/compose/.env.example contracts without committing secrets to git.
# ==============================================================================
set -euo pipefail

CONTEXT_NAME="k3d-swiftdrop-local"

echo "================================================================================"
echo "          SWIFTDROP LOCAL KUBERNETES SECRETS PROVISIONER                        "
echo "================================================================================"

create_or_update_secret() {
  local ns="$1"
  local name="$2"
  shift 2
  if kubectl --context "${CONTEXT_NAME}" get secret "${name}" -n "${ns}" >/dev/null 2>&1; then
    kubectl --context "${CONTEXT_NAME}" delete secret "${name}" -n "${ns}" >/dev/null
  fi
  kubectl --context "${CONTEXT_NAME}" create secret generic "${name}" -n "${ns}" "$@" >/dev/null
  echo "✓ Secret '${name}' created in '${ns}'."
}

# --- swiftdrop-apps secrets ---
echo "--- 1. PROVISIONING SWIFTDROP-APPS SECRETS ---"
create_or_update_secret swiftdrop-apps accounts-db-secret \
  --from-literal=username="${ACCOUNTS_DB_USERNAME:-accounts_app}" \
  --from-literal=password="${ACCOUNTS_DB_PASSWORD:-accounts_dev_password}"

create_or_update_secret swiftdrop-apps pricing-db-secret \
  --from-literal=username="${PRICING_DB_USERNAME:-pricing_app}" \
  --from-literal=password="${PRICING_DB_PASSWORD:-pricing_dev_password}"

create_or_update_secret swiftdrop-apps delivery-db-secret \
  --from-literal=username="${DELIVERY_DB_USERNAME:-delivery_app}" \
  --from-literal=password="${DELIVERY_DB_PASSWORD:-delivery_dev_password}"

create_or_update_secret swiftdrop-apps communications-db-secret \
  --from-literal=username="${COMMUNICATIONS_DB_USERNAME:-communications_app}" \
  --from-literal=password="${COMMUNICATIONS_DB_PASSWORD:-communications_dev_password}"

create_or_update_secret swiftdrop-apps swiftdrop-minio-secret \
  --from-literal=accesskey="${MINIO_ROOT_USER:-swiftdrop_storage_admin}" \
  --from-literal=secretkey="${MINIO_ROOT_PASSWORD:-minio_dev_password}"

# --- swiftdrop-identity secrets ---
echo ""
echo "--- 2. PROVISIONING SWIFTDROP-IDENTITY SECRETS ---"
create_or_update_secret swiftdrop-identity keycloak-admin-secret \
  --from-literal=admin-username="${KEYCLOAK_ADMIN_USER:-admin}" \
  --from-literal=admin-password="${KEYCLOAK_ADMIN_PASSWORD:-admin_local_dev}"

create_or_update_secret swiftdrop-identity keycloak-db-secret \
  --from-literal=username="${KEYCLOAK_DB_USERNAME:-keycloak}" \
  --from-literal=password="${KEYCLOAK_DB_PASSWORD:-keycloak_dev_password}"

create_or_update_secret swiftdrop-identity swiftdrop-google-oidc-secret \
  --from-literal=client-id="${GOOGLE_CLIENT_ID:-dummy-client-id}" \
  --from-literal=client-secret="${GOOGLE_CLIENT_SECRET:-dummy-client-secret}"

echo ""
echo "================================================================================"
echo "✓ All required local development Kubernetes secrets provisioned successfully."
echo "================================================================================"
