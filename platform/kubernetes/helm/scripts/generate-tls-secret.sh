#!/usr/bin/env bash
# ==============================================================================
# SwiftDrop Platform — Local Development TLS Certificate Provisioner (Issue #149)
# Generates a dedicated local development Certificate Authority (CA) and leaf cert
# with SANs matching 'swiftdrop.local', '*.swiftdrop.local', 'localhost', and '127.0.0.1'.
# Secrets are stored securely in ~/.swiftdrop/certs (outside the repository).
# Provisions Kubernetes TLS secret 'swiftdrop-tls-cert' in swiftdrop-apps & swiftdrop-identity.
# ==============================================================================
set -euo pipefail

CONTEXT_NAME="k3d-swiftdrop-local"
CERT_DIR="${HOME}/.swiftdrop/certs"
DAYS_VALID=365

echo "================================================================================"
echo "          SWIFTDROP LOCAL TLS CERTIFICATE PROVISIONER                           "
echo "================================================================================"

mkdir -p "${CERT_DIR}"
chmod 0700 "${CERT_DIR}"

CA_KEY="${CERT_DIR}/swiftdrop-root-ca.key"
CA_CERT="${CERT_DIR}/swiftdrop-root-ca.crt"
SERVER_KEY="${CERT_DIR}/swiftdrop-tls.key"
SERVER_CSR="${CERT_DIR}/swiftdrop-tls.csr"
SERVER_CERT="${CERT_DIR}/swiftdrop-tls.crt"
EXT_FILE="${CERT_DIR}/openssl-san.cnf"

# 1. Generate Root CA if not already present
if [[ ! -f "${CA_CERT}" || ! -f "${CA_KEY}" ]]; then
  echo "--- 1. GENERATING LOCAL SWIFTDROP ROOT CA ---"
  openssl req -x509 -new -nodes \
    -newkey rsa:2048 \
    -keyout "${CA_KEY}" \
    -out "${CA_CERT}" \
    -days "${DAYS_VALID}" \
    -subj "/C=LK/ST=Western/L=Colombo/O=SwiftDrop/OU=Platform/CN=SwiftDrop Local Root CA"
  chmod 0600 "${CA_KEY}"
  chmod 0644 "${CA_CERT}"
  echo "✓ Root CA created: ${CA_CERT}"
else
  echo "✓ Reusing existing Root CA: ${CA_CERT}"
fi

# 2. Generate Server Private Key & CSR
echo ""
echo "--- 2. GENERATING LEAF TLS CERTIFICATE FOR LOCAL INGRESS ---"
openssl genrsa -out "${SERVER_KEY}" 2048
chmod 0600 "${SERVER_KEY}"

cat << 'EOF' > "${EXT_FILE}"
[req]
default_bits = 2048
prompt = no
default_md = sha256
req_extensions = req_ext
distinguished_name = dn

[dn]
C = LK
ST = Western
L = Colombo
O = SwiftDrop
OU = Platform
CN = swiftdrop.local

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = swiftdrop.local
DNS.2 = *.swiftdrop.local
DNS.3 = app.swiftdrop.local
DNS.4 = api.swiftdrop.local
DNS.5 = auth.swiftdrop.local
DNS.6 = localhost
IP.1 = 127.0.0.1
EOF

openssl req -new -key "${SERVER_KEY}" -out "${SERVER_CSR}" -config "${EXT_FILE}"

cat << 'EOF' > "${CERT_DIR}/v3.ext"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = swiftdrop.local
DNS.2 = *.swiftdrop.local
DNS.3 = app.swiftdrop.local
DNS.4 = api.swiftdrop.local
DNS.5 = auth.swiftdrop.local
DNS.6 = localhost
IP.1 = 127.0.0.1
EOF

openssl x509 -req \
  -in "${SERVER_CSR}" \
  -CA "${CA_CERT}" \
  -CAkey "${CA_KEY}" \
  -CAcreateserial \
  -out "${SERVER_CERT}" \
  -days "${DAYS_VALID}" \
  -sha256 \
  -extfile "${CERT_DIR}/v3.ext"
chmod 0644 "${SERVER_CERT}"

echo "✓ Leaf certificate generated with SANs: swiftdrop.local, *.swiftdrop.local, localhost, 127.0.0.1"

# 3. Create or Update Kubernetes TLS Secrets
echo ""
echo "--- 3. APPLYING KUBERNETES TLS SECRETS ---"
for ns in swiftdrop-apps swiftdrop-identity; do
  if kubectl --context "${CONTEXT_NAME}" get secret swiftdrop-tls-cert -n "${ns}" >/dev/null 2>&1; then
    kubectl --context "${CONTEXT_NAME}" delete secret swiftdrop-tls-cert -n "${ns}" >/dev/null
  fi
  kubectl --context "${CONTEXT_NAME}" create secret tls swiftdrop-tls-cert \
    --cert="${SERVER_CERT}" \
    --key="${SERVER_KEY}" \
    -n "${ns}" >/dev/null
  echo "✓ TLS secret 'swiftdrop-tls-cert' configured in namespace '${ns}'."
done

echo ""
echo "================================================================================"
echo "✓ Local TLS certificates and Kubernetes secrets configured successfully."
echo "CA Certificate:     ${CA_CERT}"
echo "Server Certificate: ${SERVER_CERT}"
echo "Server Key:         ${SERVER_KEY} (protected 0600)"
echo "To trust this CA in curl: curl --cacert ${CA_CERT} https://swiftdrop.local:18443/"
echo "================================================================================"
