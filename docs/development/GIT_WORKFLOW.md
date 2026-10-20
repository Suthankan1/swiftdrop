# GitHub collaboration workflow

**Team direction, 2026-10-17:** `main` is the stable upstream baseline;
`test` is the active integration branch. Create feature branches from the latest
`test` and target **all development PRs at `test`**, explicitly selecting the base.
Never automatically merge `test` into `main` or merge your own feature PR.

Before starting and before submitting a PR, fetch and compare
`git rev-list --left-right --count origin/main...origin/test` (main-only first,
test-only second). When test is strictly behind, synchronize with a validated
fast-forward and normal push. Preserve test-only commits when branches diverge;
use a reviewed synchronization PR targeting `test` if review or conflict
resolution is required. Respect protection rules. Never reset shared branches,
force-push, or rewrite their history. If synchronization is blocked, resolve it
before feature development. Before submission, verify
`git merge-base --is-ancestor origin/test HEAD` and confirm the PR base on GitHub.

1. Use the existing **private or team-approved** GitHub repo `Suthankan1/devarom`. The public company/product name is **SwiftDrop**; the repository target `Suthankan1/swiftdrop` requires the separate [reviewed rename checklist](../branding/PHASE_2_MIGRATION.md). Do not create a second repository or upload the 13-MB report ZIP or generated PDFs to Git history.
2. Set default `main`, require PR review if account plan/settings allow; protect branches, disallow force push, enable issue tracking. Add the 9 members as collaborators with minimum necessary permissions.
3. For each slice use its existing issue (create one only if needed), update local `test` with `git pull --ff-only origin test`, then branch from it: `feat/login-keycloak-spa`, `feat/accounts-profile`, `test/auth-denials`, `docs/clarify-rest-contract`, etc. **One scope per PR**, ideally small and independently buildable; base **test**.
4. Use conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`. A breaking shared contract needs owner approvals/ADR before code.
5. Reviewers: the relevant module pair **plus** Platform for auth/gateway/contract changes; avoid self-approval. Merge in integration-friendly order (contracts → provider → consumer/UI), validate actual tests.
6. Use GitHub issues/PR comments for handoff and evidence, not private chat-only decisions. Update `docs/project/STATUS.md` after a completed slice.
7. Never commit `.env` secrets, Keycloak provider client secrets, webhook HMAC keys, API keys, credentials, screenshots containing tokens, local databases, generated `target/`, `node_modules/`, `dist/`, or report PDF exports.
8. Before demo/report freeze: tag a milestone commit, verify clean clone with independent reviewer, capture passing/failing command results and limitations honestly.

**Initial repository does not include CI or Dockerfiles** intentionally. Owners will create actual workflows/configs only in approved tasks, not copy untested templates from an AI assistant.
