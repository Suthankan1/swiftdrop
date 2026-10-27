# SwiftDrop — Hosting Feasibility & Local Fallback Test Suite

**Governing Issue:** [#176](https://github.com/Suthankan1/swiftdrop/issues/176) — `[N10-ZC05] Prove $0 HTTPS/public-access feasibility with local hosting fallback`  
**Platform Owner:** Suthankan (@Suthankan1)  
**Architecture Baseline:** SwiftDrop v1.3 (`docs/architecture/BASELINE.md`, `ADR-0011`)  
**Target Cluster:** `swiftdrop-local` (Context `k3d-swiftdrop-local`, port `127.0.0.1:18443`)  

---

## 1. Overview & Test Scope

This test suite provides automated policy validation, static schema verification, and live cryptographic checks enforcing the hosting feasibility decisions under Issue #176:

1. **`test_endpoint_matrix.py`:**
   - Validates that SPA (`swiftdrop.local`), API Gateway (`api.swiftdrop.local`), and Keycloak (`auth.swiftdrop.local`) endpoints are explicitly declared in Helm values.
   - Enforces port contract alignment with Issue #175 (`127.0.0.1:18080`, `127.0.0.1:18443`, `127.0.0.1:16443`).
   - Ensures zero external cloud domains or unauthorized public endpoints are configured.
   - Confirms that all internal business microservices, databases, Kafka, MinIO, and gRPC ports are strictly private `ClusterIP`.

2. **`test_ingress_security.py`:**
   - Confirms that Ingress resources strictly use the embedded K3s Traefik ingress controller (`ingressClassName: traefik`).
   - Rejects the introduction of secondary ingress controllers (NGINX, HAProxy, Caddy, Envoy).
   - Validates TLS configuration referencing `swiftdrop-tls-cert`.
   - Audits route mappings to prevent direct public exposure of database or message broker services.
   - Audits and monitors the Keycloak `/admin` route exposure as a documented finding for Issue #150 handoff.

3. **`test_cloud_denial_policy.py`:**
   - Asserts mathematical strict $0 cost compliance: `cost_ceiling_usd: 0.00`.
   - Asserts that cloud deployment authorization is disabled: `azure.deployment_authorized: false`.
   - Asserts that the Azure resource allowlist is empty: `azure.allowed_resources: []`.
   - Disqualifies promotional credits: `azure.promotional_credit_permitted: false`.
   - Rejects candidate cloud resources (Azure VMs, disks, public IPs, NAT, LoadBalancers, AKS, Storage, Cloud Functions, and unauthorized tunnels).

4. **`test_doc_contracts.py`:**
   - Verifies the integrity and completeness of `docs/deployment/HTTPS_PUBLIC_ACCESS_FEASIBILITY.md`.
   - Enforces presence of all 14 mandatory sections, audience reachability evaluation, official Microsoft Learn citations, exact cryptographic fingerprints, and downstream handoffs (#150, #159, E2E).

5. **`test_runtime_feasibility.py`:**
   - Performs live operational validation against the local k3d cluster when ingress port 18443 is running.
   - Validates real TLS 1.3 handshakes using the local Root CA (`~/.swiftdrop/certs/swiftdrop-root-ca.crt`).
   - Executes negative TLS tests: verifies that missing CA and SAN mismatch fail closed.
   - Validates SPA static asset retrieval.
   - Honestly verifies that unready backend services return blocked HTTP status codes rather than fabricated responses.

---

## 2. Running the Test Suite

```bash
# Run the entire hosting feasibility test suite:
python3 -m pytest tests/hosting-feasibility -v

# Or run using standard Python unittest runner:
python3 -m unittest discover -s tests/hosting-feasibility -p "test_*.py" -v
```
