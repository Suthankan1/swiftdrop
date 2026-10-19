# Security and privacy threat checklist (review before feature PR)

| Threat | Preventive requirement | Evidence to request |
|---|---|---|
| Public SPA OIDC misuse | PKCE/state/nonce, allowlisted redirect, no client secret | Redirect/callback negative tests |
| JWT forgery/expiry | Validate JWKS issuer, audience, signature, expiry at owner service | Invalid/expired token rejection |
| IDOR / tenant leakage | Resource owner, merchant membership, active courier assignment | Customer A/B and merchant X/Y tests |
| Privilege escalation | Only controlled staff provisioning, pending merchant approval | Signup cannot self-grant privileged role |
| Unsafe public tracking | Minimal redacted fields, random tracking number, throttle enumeration | Public response/privacy test |
| GraphQL data exfiltration | Field checks, query complexity/depth limits, pagination, capped fanout | Oversized/query abuse tests |
| API key compromise | 256-bit random, only salted/hashed/fingerprinted suitable storage, scoped, rotated/revoked | Missing/invalid/revoked key tests |
| Kafka/consumer replay | No PII in event, strict ACL, idempotent receipts and version checks | Duplicate/stale event tests |
| Forged webhooks | HMAC + timestamp, stable event ID, allowed HTTPS destination + SSRF protection | Signature/timeout/retry tests |
| Proof leakage | Authenticated upload/download, private object store, size/type limits, malware policy as applicable | Unauthorized read/upload tests |
| Secrets leakage | No secrets in repo/CI logs, safe env examples, credentials outside browser | Secrets review/CI scan |
| gRPC impersonation | Authenticate service and validate delegated actor/tenant context | Forged metadata/deadline tests |
| Email OTP abuse / fate sharing | Max 3 attempts, 60s cooldown, burn-on-read, retain TOTP for independent MFA, zero secrets in logs/React | Detailed threat model in [EMAIL_OTP_THREAT_MODEL.md](EMAIL_OTP_THREAT_MODEL.md) |

This is a starting checklist, not a formal penetration test or production security certification. Update it when requirements change.
