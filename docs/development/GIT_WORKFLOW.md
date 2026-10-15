# GitHub collaboration workflow — proposed

1. Create one **private or team-approved** GitHub repo `devarom`, extract/copy the starter *contents* into the clone root and commit docs (`docs: establish Devarom architecture baseline`). Do not upload the 13-MB report ZIP or generated PDFs to Git history.
2. Set default `main`, require PR review if account plan/settings allow; protect branches, disallow force push, enable issue tracking. Add the 9 members as collaborators with minimum necessary permissions.
3. For each slice create an issue and branch: `feat/login-keycloak-spa`, `feat/accounts-profile`, `test/auth-denials`, `docs/clarify-rest-contract`, etc. **One scope per PR**, ideally small and independently buildable.
4. Use conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`. A breaking shared contract needs owner approvals/ADR before code.
5. Reviewers: the relevant module pair **plus** Platform for auth/gateway/contract changes; avoid self-approval. Merge in integration-friendly order (contracts → provider → consumer/UI), validate actual tests.
6. Use GitHub issues/PR comments for handoff and evidence, not private chat-only decisions. Update `docs/project/STATUS.md` after a completed slice.
7. Never commit `.env` secrets, Keycloak provider client secrets, webhook HMAC keys, API keys, credentials, screenshots containing tokens, local databases, generated `target/`, `node_modules/`, `dist/`, or report PDF exports.
8. Before demo/report freeze: tag a milestone commit, verify clean clone with independent reviewer, capture passing/failing command results and limitations honestly.

**Initial repository does not include CI or Dockerfiles** intentionally. Owners will create actual workflows/configs only in approved tasks, not copy untested templates from an AI assistant.
