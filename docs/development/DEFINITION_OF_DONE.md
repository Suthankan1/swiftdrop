# Definition of done — per implementation slice

- [ ] Feature issue identifies owner, reviewer, module and acceptance criteria; no unapproved changes across role-pairs.
- [ ] Correct business service and data ownership, no duplicated rule, own migration only where relevant.
- [ ] Reviewed REST/GraphQL/gRPC/Kafka contract; compatible error/timeout/security behavior.
- [ ] Tests prove success **and** relevant invalid/missing-role/ownership/failure/retry cases; only state commands actually executed.
- [ ] Secrets/PII handled safely; logs/correlation IDs have no credentials/tokens.
- [ ] Build, targeted tests and appropriate integration tests succeed (or documented reproducible blocker).
- [ ] Reviewer validated from clear instructions; PR evidence and diagrams/docs updated when behavior/contracts changed.
- [ ] No hidden extra work, speculative infrastructure or unsolicited bonus implementation.
- [ ] Status updated with commit SHA/next small slice.

For November 1 **working login** specifically, see `docs/milestones/LOGIN_ACCEPTANCE.md` rather than treating architecture docs as proof.
