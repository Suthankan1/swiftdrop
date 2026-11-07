# SwiftDrop — Azure K3s Public HTTPS, DNS, & Identity Specification

**Document Status:** PREPARATION COMPLETE — DEPLOYMENT STRICTLY BLOCKED  
**Governing ADR:** [`docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md`](../../../../docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md)  
**HTTPS Feasibility Reference:** [`docs/deployment/HTTPS_PUBLIC_ACCESS_FEASIBILITY.md`](../../../../docs/deployment/HTTPS_PUBLIC_ACCESS_FEASIBILITY.md)  

---

## 1. Public DNS Architecture & Record Mapping

For external web browsers and mobile clients to reach the Azure evaluation environment, public DNS records must delegate to the allocated Azure Standard Public IP:

```
[ DNS PROVIDER ]
   demo.swiftdrop.net       A      <Azure-Public-IP>
   app.demo.swiftdrop.net   CNAME  demo.swiftdrop.net
   api.demo.swiftdrop.net   CNAME  demo.swiftdrop.net
   auth.demo.swiftdrop.net  CNAME  demo.swiftdrop.net
            |
            v
   [ Azure Public IP: Port 443 ]
            |
            v
   [ Traefik Ingress Controller (Host Port 443) ]
     ├── Host: demo.swiftdrop.net      --> frontend-service:8080
     ├── Host: app.demo.swiftdrop.net  --> frontend-service:8080
     ├── Host: api.demo.swiftdrop.net  --> api-gateway:8080 (/api/**, /graphql)
     └── Host: auth.demo.swiftdrop.net --> keycloak:8080 (/realms/**)
```

---

## 2. TLS Certificate Lifecycle & Ingress Termination

1. **Ingress TLS Secret (`swiftdrop-tls-cert`):**  
   Embedded Traefik terminates TLS 1.3 on port 443 using the standard Kubernetes TLS secret in namespace `swiftdrop-apps`.
2. **ACME Automated Issuance (Let's Encrypt):**  
   If DNS is delegated to the Azure IP, Let's Encrypt HTTP-01 challenge operates over port 80 (via `enable_http_port_80 = true`), generating a multi-SAN certificate covering `demo.swiftdrop.net`, `app.demo.swiftdrop.net`, `api.demo.swiftdrop.net`, and `auth.demo.swiftdrop.net`.
3. **Bring-Your-Own-Certificate (BYOC) Option:**  
   Operators may inject pre-issued certificates directly:
   ```bash
   kubectl create secret tls swiftdrop-tls-cert \
     -n swiftdrop-apps \
     --cert=fullchain.pem \
     --key=privkey.pem
   ```

---

## 3. Keycloak Public Issuer & Internal Hairpinning Avoidance

A critical requirement in Kubernetes microservice architectures is avoiding public hairpinning:

### The Split-Horizon Problem:
If backend services (API Gateway, Accounts, Delivery) attempt to validate incoming JWT tokens by querying Keycloak over the public domain (`https://auth.demo.swiftdrop.net`), the requests leave the VM, hit the public IP, and often drop due to NAT hairpinning restrictions.

### SwiftDrop's Dual-URI Resolution:
1. **Public Issuer (`keycloakIssuerUri`):**  
   Configured as `https://auth.demo.swiftdrop.net/realms/swiftdrop`. Embedded in JWT `iss` claims and matched exactly by Spring Security resource server validators.
2. **Private JWKS URI (`keycloakJwksUri`):**  
   Configured as `http://keycloak.swiftdrop-identity.svc.cluster.local:8080/realms/swiftdrop/protocol/openid-connect/certs`. Backend microservices retrieve cryptographic public signing keys directly over the internal cluster DNS overlay, guaranteeing zero external network dependencies and sub-millisecond key lookups.

---

## 4. Server-Sent Events (SSE) & WebSocket Ingress Timeouts

SwiftDrop's live parcel tracking features rely on long-lived HTTP Server-Sent Events connections from courier mobile devices and customer tracking pages to the Delivery service via API Gateway:

- **Traefik Ingress Timeout Configuration:**  
  `environments/azure-k3s/swiftdrop-values.yaml` configures extended read timeouts on Ingress routes to prevent proxies from terminating idle SSE connections:
  ```yaml
  traefik.ingress.kubernetes.io/router.entrypoints: websecure
  traefik.ingress.kubernetes.io/router.tls: "true"
  ```
- **Connection Heartbeats:**  
  The Delivery Operations service emits periodic SSE comment pings (`:keepalive`) every 15 seconds to ensure intermediate cloud firewalls and NAT translation tables remain warm.
