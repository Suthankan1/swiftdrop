# SwiftDrop — Azure K3s Cloud Security & Defense-in-Depth Specification

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**Security Lead Reviewer:** Vibhushan (@Vibhushan-2003)  

---

## 1. Defense-in-Depth Layered Architecture

The security posture enforces multiple concentric rings of defense:

```
[ LAYER 1: AZURE PERIMETER (Network Security Group) ]
   - Strict deny-all inbound default (Priority 4000)
   - Allow Port 443 (HTTPS) from 0.0.0.0/0
   - Allow Port 22 (SSH) ONLY from designated operator /32 CIDRs
   - Zero access to K8s API (6443), databases (5432), Kafka, or MinIO
            |
[ LAYER 2: HOST OS SECURITY (Ubuntu 24.04 LTS) ]
   - Uncomplicated Firewall (UFW) active as host-level filter
   - Password authentication strictly disabled (SSH Ed25519 keys only)
   - Sudo restricted to non-root administrative account (swiftdropadmin)
   - Kubeconfig permissions set to 0640 (read-only to group)
            |
[ LAYER 3: INGRESS BOUNDARY (Embedded Traefik) ]
   - Enforced TLS 1.3 encryption terminating on port 443
   - Keycloak admin console (/admin/**) blocked externally (exposeAdmin: false)
   - CORS origin validation enforced at API Gateway edge
            |
[ LAYER 4: KUBERNETES WORKLOAD ISOLATION ]
   - Default-deny Kubernetes NetworkPolicies in all application namespaces
   - Container security context: runAsNonRoot, readOnlyRootFilesystem, drop ALL capabilities
   - Inter-service gRPC, database, and Kafka traffic confined to cluster overlay
            |
[ LAYER 5: DATA PROTECTION & ENCRYPTION ]
   - Managed disks encrypted at rest using platform-managed keys (PMK)
   - Storage volume mounted with noexec, nofail, discard flags
   - Application secrets injected at runtime; zero credentials in Git or Terraform state
```

---

## 2. Network Security Group Invariants

The NSG configuration in `security.tf` enforces strict invariants:

| Direction | Priority | Protocol | Port Range | Source Prefix | Access | Purpose |
|---|---|---|---|---|---|---|
| **Inbound** | 100 | TCP | 22 | Operator CIDR (`/32`) | **Allow** | Restricted operator SSH maintenance |
| **Inbound** | 110 | TCP | 443 | `0.0.0.0/0` | **Allow** | Public HTTPS application ingress |
| **Inbound** | 120 | TCP | 80 | `0.0.0.0/0` | **Allow / Opt** | Optional ACME HTTP-01 challenge |
| **Inbound** | 4000 | Any | Any | `0.0.0.0/0` | **DENY** | Explicit drop of all other ports |

### Strict Port Denials
The following critical ports are explicitly denied public ingress by NSG Rule Priority 4000:
- **Port 6443:** Kubernetes API Server (administered strictly via SSH tunnel or local proxy).
- **Port 5432:** PostgreSQL database instances (Accounts, Business, Keycloak).
- **Port 29092 / 9092:** Apache Kafka broker and KRaft controller.
- **Port 9000 / 9001:** MinIO S3 API and MinIO web console.
- **Port 9081 / 9082 / 9083:** Internal gRPC communication endpoints.
- **Port 8080 - 8085:** Internal Spring Boot HTTP microservice ports.
- **Port 3000 / 9090 / 9093:** Grafana, Prometheus, and Alertmanager monitoring interfaces.

---

## 3. SSH Access Control & Fail-Closed Policy

1. **No Public SSH:** Terraform variable validation strictly prevents `allowed_ssh_source_cidrs` from containing `0.0.0.0/0`, `::/0`, `*`, or `Internet`.
2. **Fail-Closed Default:** If `allowed_ssh_source_cidrs` is empty (the default), no SSH rule is generated in the NSG, rendering port 22 completely unreachable from the outside.
3. **No Passwords:** The Linux VM declares `disable_password_authentication = true`.
4. **Key Custody:** The private SSH key must never touch version control, CI logs, or Terraform state files.

---

## 4. Keycloak Identity & Browser OIDC Invariants

1. **Canonical HTTPS Issuer:**  
   The Keycloak issuer URL is configured as `https://auth.demo.swiftdrop.net/realms/swiftdrop`. Local development URLs (`localhost:8090`, `swiftdrop.local:18443`) are overridden in `environments/azure-k3s/swiftdrop-values.yaml`.
2. **Strict Valid Redirect URIs:**  
   The `swiftdrop-spa` Keycloak client requires HTTPS redirect URIs:
   - `https://app.demo.swiftdrop.net/*`
   - `https://demo.swiftdrop.net/*`
   Wildcard redirect URIs (`*`, `http://*`) are strictly prohibited.
3. **Admin Console Isolation:**  
   The Keycloak administrative console (`/admin/**`) is disabled from external ingress routing (`ingress.keycloak.exposeAdmin: false`). Administrative operations must be conducted via internal port-forwarding:
   ```bash
   kubectl port-forward -n swiftdrop-identity svc/keycloak 8080:8080
   ```

---

## 5. Private Kubernetes Administration Access Architecture

1. **Zero Public Exposure for Port 6443:**  
   The Kubernetes API server (port 6443) is never exposed to the public internet. Port 6443 is blocked by Azure NSG Rule 4000.
2. **Encrypted Operator Access Pattern:**  
   Authorized operators access the cluster via authenticated SSH local port-forwarding over port 22 (which is restricted to approved operator CIDRs):
   ```bash
   ./scripts/setup-kube-access.sh connect --key ~/.ssh/swiftdrop-azure-k3s
   ```
3. **Kubeconfig File Custody:**  
   The downloaded kubeconfig is immediately locked with `chmod 600` permissions (owner read/write only).
4. **Certificate Validation Integrity:**  
   The K3s server certificate contains TLS Subject Alternative Names (SANs) for `127.0.0.1` and `localhost`. Tunneling to `127.0.0.1` allows standard `kubectl` certificate validation without insecure flags (`--insecure-skip-tls-verify` is prohibited).
5. **Connection Lifecycle:**  
   When administrative tasks conclude, the operator terminates the tunnel cleanly:
   ```bash
   ./scripts/setup-kube-access.sh stop
   ```
