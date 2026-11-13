# SwiftDrop — November 15, 2026 final-submission evidence and acceptance checklist

**Status:** Review candidate; not independently certified as final-submission ready.

**Evidence cut-off:** November 13, 2026.  
**Repository:** https://github.com/Suthankan1/swiftdrop  
**Reviewed branch:** `test`  
**Reviewed head:** `97687263a69655b841dbd659cd68ae34dcfaeb84` (merge of PR #325).

## Submission requirements

The November 1 milestone required a report containing use case diagrams, architecture, flowcharts and justifications, and an initial codebase with logins. The November 15 milestone is the final submission. The existing November 1 DOCX/PDF supplied by the group is a **104-page historical baseline**, not evidence that the November 15 acceptance checks have been freshly performed.

For the final pack retain the prior technical diagrams and rationale, but distinguish original design scope from features verified in the final implementation. Never silently treat a target architecture as a running environment.

## Repository evidence verified November 13

| Area | Dated evidence | Status / limitation |
|---|---|---|
| Design diagrams | [20 editable Draw.io sources](../diagrams/editable/) and [20 SVG previews](../diagrams/rendered/) | Present; verify image orientation and legibility in each exported PDF |
| Source and integrations | `test` commit `9768726`, containing PR #325 for shared Supabase | Present; verify the final branch/commit used for submission |
| Shared team databases | [Supabase guide](../../platform/compose/SUPABASE.md) | Opt-in 3-project/5-database development configuration; local PostgreSQL remains default |
| Audit remediation | [Updated remediation findings](../audits/final-comprehensive-audit/remediation-progress/REMAINING_FINDINGS.md) | 17 reviewed items classified as remediated, fixed, accurate, or approved for deferment |
| Historic release checklist | [November 5 checklist](../audits/final-comprehensive-audit/RELEASE_READINESS_CHECKLIST.md) | Earlier blockers; not a current unmodified verdict after PRs #302–#305 |
| Current CI | GitHub Actions `SwiftDrop CI` run IDs `43369431149` and `43369541081`, for SHA `9768726`, November 13 | Both completed successfully |
| Older test evidence | [Recorded test executions](../audits/final-comprehensive-audit/remediation-progress/TEST_RESULTS.md) | Gateway 87, Delivery 485, Accounts 311, GraphQL 81, targeted Communications 13 and frontend 1,261 tests with 0 reported failures, at their respective earlier execution dates |
| Screenshot audit | Original report Figures 6.1–6.16 | Earlier-stage authentication evidence; final role-specific authenticated screenshots not independently inspected at this evidence cut-off |

**Interpretation:** Successful CI does not prove an authenticated UI workflow, the latest full on-device test suite, a working public domain or live cloud deployment. The remediation register includes the GraphQL `deliveryStats` feature as an **approved deferment**; describe it as such.

## Five-database invariant

The shared developer environment uses three Supabase PostgreSQL projects and **five independent logical PostgreSQL databases**:

| Supabase project | Database | Consumer |
|---|---|---|
| `swiftdrop-accounts` | `accounts_db` | Accounts |
| `swiftdrop-business` | `pricing_db` | Pricing |
| `swiftdrop-business` | `delivery_db` | Delivery |
| `swiftdrop-business` | `communications_db` | Communications |
| `swiftdrop-identity` | `keycloak_db` | Keycloak |

Do not describe these as five schemas in a single database, and do not replace Keycloak with Supabase Auth. Use dedicated database roles and ignored local environment files. Do **not** commit connection strings containing passwords, database credentials or other secrets.

## Final screenshot acceptance

Capture on an **isolated local test database**, not the shared Supabase instance. Use real local browser sign-ins, actual test workflows and synthetic demonstration data. **Reject screenshots of the login page presented as evidence of authenticated roles.** Do not fabricate, composite, or relabel functionality. Exclude AI assistant UI, secrets and personally identifiable information.

- [ ] Customer: authenticated dashboard; quotation; booking/history and tracking.
- [ ] Merchant: approved merchant workspace; saved orders and webhook configuration.
- [ ] Courier: own assignment list; authorized parcel actions and status/proof flow.
- [ ] Dispatcher: unassigned queue; assignment result after authorized action.
- [ ] Administrator: merchant review and staff or pricing management.
- [ ] Public tracking: redacted anonymous result, with no protected addresses.
- [ ] Each figure has a truthful role, date, caption, reproducible path and image-level visual review.

If sign-in redirects prevent reaching a protected dashboard, report the blocker and omit the claimed screenshot; do not substitute a signin-only page.

## Final code/test/infra acceptance

- [ ] Preserve prior source and any other developers' uncommitted work.
- [ ] Capture the commit SHA and full CI URLs in the final report.
- [ ] Re-run relevant service integration tests in a supported environment; distinguish pass, fail, skip and environment errors.
- [ ] Test all five user journeys and negative authorization boundaries against isolated demo records.
- [ ] Verify Kafka event/outbox and notification/webhook behavior as applicable.
- [ ] Verify Keycloak OIDC flows, dashboard GraphQL/gRPC reads and SSE tracking.
- [ ] Validate Docker Compose and optional K3s/Helm manifests. Do not infer cloud deployment from Terraform files.
- [ ] Review changed files for secrets and ensure all real `.env` files remain ignored.
- [ ] Generate professionally formatted DOCX and PDF from one maintained editable source; verify rendered pages, ToC, captions, cross-references, legibility, figures, and tables.
- [ ] Review nine team contributions against actual Git commits/PRs; keep identity spellings consistent (including the group's requested `Praveen APE` label where applicable).
- [ ] Confirm lecturer-required final formats, filenames and destination before submission.

## Documents and repository policy

The [diagram guide](../diagrams/README.md) intentionally excludes bulky exported PDF report files from the code repository. Keep this Markdown evidence/checklist in Git; deliver final signed-off DOCX/PDF as separately validated **submission artifacts** unless the team explicitly revises that repository convention. The user-uploaded November 1 report is not assumed present in the Git checkout.

## Outstanding acceptance blockers at this evidence cut-off

1. The authentic final dashboard screenshots have not been verified and several earlier candidates reportedly captured only the sign-in page.
2. Full fresh test logs and current integration run evidence have not been attached to this checklist; CI has been verified, not recreated locally.
3. Any production/hosted Azure deployment claim needs separately verified public URLs, TLS identity and governance sign-offs; the repository's optional Terraform/Helm code is not that proof.
4. The submitted final DOCX/PDF should be inspected on actual output pages after diagrams and screenshots have been included. Do not mark this gate complete based on document-generation success alone.

**Go/no-go:** A complete code repository with passing CI is promising, but the final evidence package should only be marked *submission ready* once the above screenshots, document layout and fresh test evidence have been reviewed.
