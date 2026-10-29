# Documentation map

| Area | Start with |
|---|---|
| Project scope and decisions | [Context](project/PROJECT_CONTEXT.md), [assignment scope](requirements/ASSIGNMENT_SCOPE.md), [status](project/STATUS.md) |
| Architecture | [Baseline](architecture/BASELINE.md), [protocols](architecture/PROTOCOL_MATRIX.md), [data ownership](architecture/DATA_OWNERSHIP.md), [ADRs](architecture/adr/README.md) |
| Contracts | [Contract review rules](contracts/README.md), [REST](contracts/REST.md), [GraphQL](contracts/GRAPHQL.md), [gRPC](contracts/GRPC.md), [Kafka](contracts/KAFKA.md) |
| Security | [Authentication](security/AUTHENTICATION.md), [authorization matrix](security/AUTHORIZATION_MATRIX.md), [threat review](security/THREAT_REVIEW.md) |
| People and timeline | [Owners](team/OWNERSHIP.md), [November 1](milestones/NOV_01.md), [roadmap](milestones/ROADMAP.md) |
| Diagrams | [20 editable diagrams and SVG previews](diagrams/README.md) |
| Dispatcher/Admin design review | [Issue #13 use cases, assignment workflow and report handoff](reviews/DISPATCHER_ADMIN_REVIEW.md) |
| SwiftDrop rebranding | [Phase 1 impact audit](branding/PHASE_1_AUDIT.md), [Phase 2 technical migration proposal](branding/PHASE_2_MIGRATION.md), [verification](branding/VALIDATION.md) |
| Starter generation | [Spring Initializr + Vite planning only](development/STARTER_GENERATION_GUIDE.md) |
| Hosting & $0 cloud policy | [Strict $0 hosting decision & Azure allowlist](architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md), [Zero-cost handoff](handoff/ISSUE_172_TO_173_178_ZERO_COST_HOSTING.md), [Operator handover](deployment/N10_ZC06_ZERO_COST_OPERATOR_HANDOVER.md), [Evidence index](handoff/N10_ZC06_RELEASE_EVIDENCE_INDEX.md), [Optional Azure K3s spending gate](deployment/OPTIONAL_AZURE_K3S_SPENDING_AUTHORIZATION.md) |
| Delivery | [PR workflow](development/GIT_WORKFLOW.md), [definition of done](development/DEFINITION_OF_DONE.md) |
| AI assistance | [Agent guardrails](../AGENTS.md), [task template](ai/TASK_TEMPLATE.md), [prompt playbook](ai/PROMPT_PLAYBOOK.md) |

**Authority:** human task + academic assignment > approved architecture decisions/contracts > current actual code/tests > examples. Diagrams describe **targets** until implementation is independently verified.
