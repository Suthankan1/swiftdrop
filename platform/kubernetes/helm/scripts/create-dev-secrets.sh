#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Local Development Secret Provisioner (Issue #149 / #154)
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

# --- swiftdrop-data secrets ---
echo "--- 1. PROVISIONING SWIFTDROP-DATA SECRETS ---"
create_or_update_secret swiftdrop-data accounts-db-credentials \
  --from-literal=POSTGRES_USER="${ACCOUNTS_DB_USERNAME:-accounts_app}" \
  --from-literal=POSTGRES_PASSWORD="${ACCOUNTS_DB_PASSWORD:-accounts_dev_password}" \
  --from-literal=POSTGRES_DB="accounts_db"

create_or_update_secret swiftdrop-data business-db-credentials \
  --from-literal=POSTGRES_USER="${POSTGRES_USER:-postgres}" \
  --from-literal=POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres_dev_password}" \
  --from-literal=POSTGRES_DB="${POSTGRES_DB:-postgres}" \
  --from-literal=PRICING_DB_PASSWORD="${PRICING_DB_PASSWORD:-pricing_dev_password}" \
  --from-literal=DELIVERY_DB_PASSWORD="${DELIVERY_DB_PASSWORD:-delivery_dev_password}" \
  --from-literal=COMMUNICATIONS_DB_PASSWORD="${COMMUNICATIONS_DB_PASSWORD:-communications_dev_password}"

create_or_update_secret swiftdrop-data keycloak-db-credentials \
  --from-literal=POSTGRES_USER="${KEYCLOAK_DB_USERNAME:-keycloak}" \
  --from-literal=POSTGRES_PASSWORD="${KEYCLOAK_DB_PASSWORD:-keycloak_dev_password}" \
  --from-literal=POSTGRES_DB="keycloak_db"

# --- swiftdrop-messaging secrets ---
echo ""
echo "--- 2. PROVISIONING SWIFTDROP-MESSAGING SECRETS ---"
create_or_update_secret swiftdrop-messaging swiftdrop-minio-secret \
  --from-literal=accesskey="${MINIO_ROOT_USER:-swiftdrop_storage_admin}" \
  --from-literal=secretkey="${MINIO_ROOT_PASSWORD:-minio_dev_password}"

# --- swiftdrop-apps secrets ---
echo ""
echo "--- 3. PROVISIONING SWIFTDROP-APPS SECRETS ---"
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
echo "--- 4. PROVISIONING SWIFTDROP-IDENTITY SECRETS ---"
create_or_update_secret swiftdrop-identity keycloak-admin-secret \
  --from-literal=admin-username="${KEYCLOAK_ADMIN_USER:-admin}" \
  --from-literal=admin-password="${KEYCLOAK_ADMIN_PASSWORD:-admin_local_dev}"

create_or_update_secret swiftdrop-identity keycloak-db-secret \
  --from-literal=username="${KEYCLOAK_DB_USERNAME:-keycloak}" \
  --from-literal=password="${KEYCLOAK_DB_PASSWORD:-keycloak_dev_password}"

create_or_update_secret swiftdrop-identity swiftdrop-google-oidc-secret \
  --from-literal=client-id="${GOOGLE_CLIENT_ID:-dummy-client-id}" \
  --from-literal=client-secret="${GOOGLE_CLIENT_SECRET:-dummy-client-secret}"

create_or_update_secret swiftdrop-identity swiftdrop-smtp-secret \
  --from-literal=host="${SMTP_HOST:-mailpit.swiftdrop-messaging.svc.cluster.local}" \
  --from-literal=port="${SMTP_PORT:-1025}" \
  --from-literal=username="${SMTP_USER:-}" \
  --from-literal=password="${SMTP_PASSWORD:-}" \
  --from-literal=from="${SMTP_FROM:-no-reply@swiftdrop.test}" \
  --from-literal=auth="${SMTP_AUTH:-false}" \
  --from-literal=ssl="${SMTP_SSL:-false}" \
  --from-literal=starttls="${SMTP_STARTTLS:-false}"

echo ""
echo "================================================================================"
echo "✓ All required local development Kubernetes secrets provisioned successfully."
echo "================================================================================"
