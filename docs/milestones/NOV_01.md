# November 1 checkpoint — report + initial codebase with logins

**Deadline source:** the group provided November 1; the printed four-page assignment does not contain that milestone. **Year assumed 2026 from conversation context; confirm.** Document actual lecturer submission format separately.

## Required deliverables for this checkpoint

**A. Report:** use case diagrams for four role groups, logical system context/architecture and protocol justification, booking/assignment/status activities, key booking/merchant/status/OIDC sequence diagrams, logical ERDs, lifecycle, reliability/security rationale, design decisions, contribution matrix and references. The provided 20 Draw.io files are ready for review; the original external ZIP contains a report draft and PDFs, deliberately not copied into this software repo.

**B. Actual initial application with logins:** team-generated React/Vite SPA and one backend JWT-protected path through the Gateway + Keycloak. Show local/Google Keycloak login if real Google provider configuration is available, PKCE callback handling, role-appropriate navigation, login failure, logout, authorization denials and repeatable setup. Do not represent mock screens or unchecked boxes as working authentication.

## Priority order (avoid scope creep)

1. Group validates diagrams/ADRs and agrees final team ownership.
2. Platform + Customer pair obtain real Keycloak local/Google config, compile first independently generated SPA + Accounts/Gateway app and wire login.
3. Prove actual bearer JWT validation and **at least one** negative cross-role/owner test in backend; iterate role entry screens with other pairs.
4. Have another teammate follow setup instructions from a fresh machine.
5. Freeze submitted report from reviewed source figures; capture real test/demo evidence and contributions. Follow lecturer submission requirements.

**Not required as implemented by this specific login milestone:** booking/pricing operations, gRPC contract servers, GraphQL dashboards, Kafka/outbox, SSE fanout, proof/receipts, full Kubernetes/Helm and AI. These remain design diagrams and future implementation work.

## Exit checklist

- [ ] Lecturer group-size exception (9 vs 10) clarified
- [ ] Report text/figure captions reviewed against v1.3, current product name SwiftDrop
- [ ] All required diagrams are legible and correctly referenced (don't export duplicate gigantic PDFs to repo)
- [ ] Local login real and demonstrable; Google login real or explicitly recorded as blocked/not done
- [ ] Private token handling, JWT rejection and wrong-role/owner rejection proven in actual backend
- [ ] Clean clone/repro instructions tested by a second group member
- [ ] Contribution evidence, actual commit IDs and screenshot/demo links recorded privately/safely
- [ ] Report file format and submission destination confirmed with lecturer

For details see [readiness tracker](NOV_01_READINESS_TRACKER.md), [login acceptance evidence register](LOGIN_ACCEPTANCE.md) and [login integration contract](../contracts/LOGIN_CONTRACT.md).
