# Copy/paste template — one task for any AI coding agent

> Replace bracketed items. **Do not submit an unbounded request like “build SwiftDrop”**.

**Project context:** This is SwiftDrop, architecture baseline v1.3. Read `AGENTS.md`, `docs/project/PROJECT_CONTEXT.md`, `docs/architecture/BASELINE.md`, `docs/team/OWNERSHIP.md`, relevant module README, contract docs and current Git/test state first.

**Task ID / owning team / reviewer:** [issue], [name/pair], [reviewer]

**Single outcome:** [precisely one capability]

**Allowed paths:** [narrow folder/file list; don't change others]

**Relevant contract/ADR:** [links; if undecided propose contract first, don't improvise]

**Acceptance cases:** [positive], [negative/invalid input], [unauthorized/tenant], [failure condition]

**Out of scope:** [explicit exclusions, e.g. other services, UI, Docker, Kafka]

**Preferred workflow:**
1. Inspect current project files/status; identify blockers/conflicts (especially ungenerated code).
2. Give concise change plan with file list and tests, then implement **only if expressly requested**.
3. Make the smallest change with tests; don't scaffold the entire system.
4. Run relevant commands when available and report actual pass/fail; if unable, state why.
5. End with changed files, PR-ready summary, contract/security impact and one next step. **Do not push/merge/deploy without permission.**
