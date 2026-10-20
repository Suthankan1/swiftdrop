# SwiftDrop Phase 2 technical migration proposal

**Status: PROPOSED — NOT EXECUTED OR APPROVED.** Prepared 2026-10-20 from the
tracked baseline in [PHASE_1_AUDIT.md](PHASE_1_AUDIT.md). Phase 1 changes public
wording; it retains working protocol and persistence identifiers. This document
does not authorize identity, schema, provider, storage or repository changes.

## Decisions required before execution

Platform/Security lead **Suthankan1**, Accounts steward **Sinthuha**, frontend
steward **Gajanthan** and an independent security reviewer must review the
identity contract change. Existing business ownership is unchanged. Obtain
separate owner signoff for service namespaces, infrastructure and GitHub
administration. Development PRs target `test`; release, push and deployment
actions require their applicable authorization.

Agree and record: the environments and maintenance window; approved origins and
callback/logout URLs; token/session expiry and user communication; private
backup custody and tested restoration; subject-continuity acceptance; independent
reviewer; monitoring/rollback owner and deadline; and external Google/GitHub
administrators. Do not infer production settings from local defaults.

The proposed identity contract is:

| Binding | Current | Proposed |
|---|---|---|
| Realm name | `devarom` | `swiftdrop` |
| Browser client ID | `devarom-frontend` | `swiftdrop-frontend` |
| JWT audience | `devarom-api` | `swiftdrop-api` |
| Local issuer | `http://localhost:8090/realms/devarom` | `http://localhost:8090/realms/swiftdrop` |
| Local broker callback | `http://localhost:8090/realms/devarom/broker/google/endpoint` | `http://localhost:8090/realms/swiftdrop/broker/google/endpoint` |

Preserve provider alias `google`, existing Google OAuth credentials, five
business roles and privilege policy, public-client PKCE S256, exact redirect
allowlists, token lifetimes and independent service enforcement. Authentication
policy changes are separate work. The target realm **name** does not require
replacing the internal realm ID or any user ID merely to match branding.

## Identity and data continuity

Accounts keys a profile by verified `sub`, not email, username or a browser
header. `keycloak_sub` is unique and protected by an immutable-subject trigger
in `V1__create_account_profiles.sql`. An unverified replacement subject would
either obtain a new JIT profile or fail to reference the existing one. Never
delete profiles, alter applied migration V1, drop the trigger, bulk-match by
email or silently rebind an account to make a rename succeed.

The preferred candidate is a **supported in-place realm-name/client-ID change
that retains internal entities**, proven on an isolated complete restore of the
pinned Keycloak 26.8.1 installation. This is a proposal requiring rehearsal,
not a guarantee about subject/session preservation. Compare pre/post user IDs,
JWT subjects, client internal IDs, federated identities and business profile
UUIDs. The [26.8.1 Admin REST API](https://www.keycloak.org/docs-api/26.8.1/rest-api/index.html)
documents realm/client administration; it does not replace this continuity
test for the actual installation.

If that approach cannot preserve identity, stop the live cutover. Prepare a
separately reviewed controlled migration with a complete user/credential,
role, federation and business mapping, explicit reconciliation, measured
downtime and proven rollback. Any changed subject requires an approved
issuer/subject identity mapping or data migration design and security tests.
The current Accounts model stores only `sub`; accepting two issuers would need
its own design to avoid identity collisions. Do not add a permissive dual-issuer
or dual-audience validator as a shortcut.

The checked-in realm is a sanitized **fresh-install configuration**, not a user
backup. Keycloak startup import skips an existing realm; changing the JSON and
restarting does not migrate it. Official import/export guidance also warns
that exports omit sessions and other state and require a stopped server for
consistency. Use a storage backup plus a tested private recovery process, not
an Admin Console partial export. Never commit expanded exports or credential
material. See [Keycloak import/export guidance](https://www.keycloak.org/server/importExport).

Email verification status, native required actions, password credentials,
federated links and TOTP credentials must survive. Rehearse local verified and
unverified users, Google-linked users, an enrolled TOTP user and an unenrolled
customer. Do not reset passwords, mark users verified in bulk, unlink Google,
reenroll authenticators or generate new users as a migration technique. Keep
native verification-link handling and MFA policy unchanged.

Issuer changes affect existing access tokens, refresh/SSO flows and issued
email action links. Plan a controlled reauthentication boundary and a period
with no newly issued old-realm action links; allow the configured 300-second
user-generated verification links to expire, accounting for verified runtime
overrides. Test native resend/recovery after cutover. Do not promise old
sessions or links will survive the rename. Any intentional session revocation
must be explicitly approved and communicated.

## Ordered execution plan

1. **Approve and capture the contract.** Record the decisions above in a short
   ADR and reviewed login contract. Inventory effective values through supported
   admin/configuration reads without printing tokens, secrets, credentials or
   personal data. Confirm the exact image, identity storage, environment
   overrides, broker redirect and all consumers, including other clones.
2. **Prove recovery before changing names.** Schedule a consistent private
   identity-storage backup and Accounts backup; record a restricted integrity
   inventory of user IDs, profile UUID/subject bindings and federated links.
   The current local Keycloak stores identities in its container writable layer;
   Compose has no identity data volume. Preserve that container. Rehearse
   restoration into an isolated network with no real mail or provider callbacks.
   Backup/export files remain encrypted/restricted outside Git and logs.
3. **Rehearse on the complete isolated restore.** Validate the supported rename
   mechanism for 26.8.1, retaining user/realm/client internal IDs. Use an
   isolated test provider configuration approved for that environment. Compare
   all continuity invariants and exercise the rollback. If a single existing
   subject/profile/federation/TOTP binding changes unexpectedly, stop here.
4. **Prepare one coordinated identity PR into `test`.** Update only approved
   realm/client/audience and corresponding references described below; update
   tests to assert the new contract. Keep role membership, redirect paths,
   authorization behavior and data/schema unchanged. Review the effective
   configuration diff and run all offline/static/unit suites before scheduling
   the live change.
5. **Prepare the existing Google OAuth client.** Its authorized administrator
   adds the exact new callback for each approved origin while retaining the
   old callback for the bounded rollback window. Preserve Google client ID,
   secret, alias, scopes, consent/test-user policy and linked identities. Verify
   callback acceptance without exposing credentials. Provider consent-screen
   branding is a separate visible metadata update.
6. **Cut over inside the agreed maintenance window.** Stop new logins,
   registrations, profile writes and email-action issuance; finish or expire
   outstanding flows and take the final consistent backups. Apply the rehearsed
   supported rename to the existing entities. Switch the SPA's realm/client and
   both services' exact issuer/JWKS/audience settings as one coordinated release.
   Do not recreate a realm/container or wipe/import live state from the source
   JSON. Keep ingress closed until continuity/security probes pass.
7. **Verify before reopening.** Run the acceptance checks below with disposable
   synthetic identities and an authorized human Google login. Privately verify
   that established users return the same subject and Accounts profile UUID.
   Confirm issuer, audience, roles, broker callback, email/MFA continuity and
   logout; check startup/readiness plus direct-service denials. Reopen writes
   only after the reviewer accepts the evidence.
8. **Observe and retire compatibility deliberately.** Monitor sanitized login,
   callback, token-validation and profile-conflict errors. Keep recoverable
   backups and the approved rollback settings for the agreed window. Remove
   the old Google callback only after acceptance and rollback-window closure.
   A local hostname/domain change, production SMTP, or broader identity-policy
   change remains a separate task.

### Coordinated reference changes

| Surface | Required migration work |
|---|---|
| Keycloak entities | Rename supported realm/client names while preserving internal IDs and users; adjust audience mapper value. Review mapper label/default composite role separately because scripts and role filtering reference their legacy names. Keep default membership exactly `CUSTOMER` and existing staff grants. |
| SPA | `frontend/src/auth/session.ts` currently hardcodes realm/client; URL environment variables alone will not migrate them. Change adapter configuration and fixtures together; preserve `/login`, `/auth/callback`, `/logout`, `/customer`, state/nonce/PKCE behavior and in-memory storage. Registration is the existing `register()` action into native Keycloak, not a separate SPA signup route. |
| Gateway | Update `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWKS_URI`, `JWT_AUDIENCE` and matching defaults/tests in `services/api-gateway`. Maintain exact signature/issuer/audience/time validation, header stripping, CORS and denial behavior. |
| Accounts | Update the same three settings independently in `services/accounts-service`, signed-token fixtures and tests. Keep `keycloak_sub`, profile UUIDs, unique/immutable constraints and Flyway history. No DB update is needed when the verified subject remains identical. |
| Google broker | Change the exact realm-path callback registered on the existing OAuth client; maintain the broker alias and external identity links. Inventory actual production origins privately. A Google token remains unacceptable as a business API token. |
| Identity helpers | Update guarded realm/admin paths, client/audience expectations and role dependencies in apply-theme, email-verification/TOTP helpers, validator and identity smoke tests. Review private recovery snapshot applicability; never apply a stale old-realm snapshot blindly. |
| E2E / local scripts | Update discovery, auth/token/logout/broker URLs, expected claims and fixtures in `tests/security-e2e`, identity tests, readiness/startup scripts and runbooks together. Synthetic mailbox names need not migrate with the security contract. |
| Docs / diagrams | Update current runtime instructions only once implemented and verified. Preserve historical results/PRs/SHAs and the Phase 1 baseline inventory. Regenerate matching diagram SVGs after any reviewed source-label change. |

## Separate namespace and infrastructure slices

These can be scheduled after identity stability; combining all of them with an
issuer migration increases the recovery surface without helping identity
continuity.

| Identifier family | Proposed treatment and dependency |
|---|---|
| Theme | `themes/devarom`, CSS/asset filenames, CSS classes and `loginTheme=devarom` can remain legacy identifiers under SwiftDrop branding. A later rename must change file paths, templates, theme properties, mounts, helper guards and tests together; verify native error/email/MFA pages. |
| Java / Maven | A proposed `com.swiftdrop` namespace requires synchronized directories, package/import declarations, Maven group IDs, component/test discovery and configuration references in both services. Use a separate mechanical PR with full compilation/tests. No applied SQL migration is renamed. |
| Config keys / TypeScript package | `devarom.jwt`, `devarom.cors`, `DevaromRole` and `@devarom/frontend` are technical names. Inventory consumers before changing them; document any temporary compatibility explicitly. No secrets are introduced into `VITE_*`. |
| Docker | Container names `devarom-keycloak`, `devarom-mailpit`, `devarom-accounts-postgres` and Accounts volume `devarom-accounts-postgres-data` are referenced by scripts/runbooks. Inventory actual Compose project/network IDs privately. Never create a new empty volume and present it as renamed data. Preserve or explicitly migrate data with a restore test. |
| Databases | Accounts database is environment-backed (local `accounts_db`); `keycloak_db` is a documented target, while current local identity uses embedded storage. These neutral names do not need a branding change. Production database names/roles are unknown; do not rename or recreate them without a separate owner-reviewed plan. |
| SMTP | Sender display name is visible branding. A sender mailbox/domain migration needs approved ownership, delivery verification and provider configuration. Retain current local mailbox until that task; do not replace SMTP credentials or use local Mailpit in production. |
| CI / scripts / integrations | No workflows or submodules are tracked at the audited baseline. Still inventory hosted checks, required statuses, installed apps/webhooks, artifact registries, deployment configuration, scripts and external consumers before a namespace/repository rename. |

Google Cloud's project **display name** and OAuth consent/client labels are
different from its immutable project **ID**. Keep an existing legacy project ID;
do not replace cloud resources or OAuth credentials for naming consistency.
[Google's project documentation](https://docs.cloud.google.com/resource-manager/docs/creating-managing-projects)
states that the project ID is permanent after creation. The actual project ID
was not read for Phase 1.

## Acceptance and rollback

Run frontend `pnpm typecheck`, `pnpm test run`, `pnpm lint`, `pnpm build`;
`./platform/identity/validate-identity.sh`; Python identity unit discovery;
Gateway and Accounts `./mvnw test` from their module directories; Accounts
PostgreSQL integration verification when available; local readiness, the demo
smoke suite, native email/TOTP regressions and security E2E. Record exact
commands/results, skips and dependencies. Include these invariants:

- Existing local and Google-linked users retain the same `sub` and profile UUID;
  repeated/concurrent `/api/v1/users/me` calls do not create duplicate profiles.
- New registrations receive only `CUSTOMER`; email verification and existing
  verified status survive; enrolled TOTP challenges still work and unenrolled
  customers remain accessible according to existing policy.
- The new issuer/client/audience are correct; wrong/old issuer and wrong/old
  audience tokens fail at both Gateway and direct Accounts once cut over.
  Expired/untrusted/malformed tokens, wrong roles and spoofed ownership fail.
- Callback cancellation, invalid state/nonce/PKCE, strict redirects, refresh or
  reauthentication, registration and logout retain their security behavior.
  A human completes real Google sign-in; a broker URL probe alone is insufficient.
- User/profile counts and restricted integrity comparisons reconcile, no
  personal data or tokens enter logs, and the rehearsed rollback succeeds.

Abort before reopening if continuity, callback, verification/MFA or independent
JWT validation fails. During the write freeze, reverse the rehearsed in-place
realm/client changes, restore the old SPA/service configuration and old broker
callback, clear only approved caches and require safe reauthentication. Verify
local/Google login and the same existing profile mappings before reopening.
Do not delete users or restore an old database over newer live writes.

If writes have reopened, freeze them again and reconcile new registrations,
profile writes, email verification, password/MFA and federation changes before
any restoration. Prefer the proven supported reverse rename retaining current
data; a storage restore requires explicit owner approval and reconciliation of
all post-snapshot changes. Keep consistent identity and Accounts recovery
points together, with a restricted audit record. A source-code revert alone
does not undo identity/provider changes or restore data.

## GitHub repository rename checklist — separate reviewed operation

The target is **`Suthankan1/swiftdrop`**. Do not execute this checklist in the
Phase 1 PR. Obtain admin authorization and notify all nine teammates first.

- [ ] Record current repository identity, default branch, `main`/`test`
  protections/rulesets, required reviews/status checks, access and open PR bases.
  Recheck any open development PR remains based on `test` after the rename.
- [ ] Inventory GitHub Projects, issue/PR links, milestones, labels, repository
  references, branch policies, Actions settings/environments/secrets names,
  reusable workflows/actions, badges, releases and any Pages/custom domains.
  Preserve issue/PR numbers, commits and contribution records.
- [ ] Inventory GitHub Apps, webhooks, deployment/hosting links, OAuth callbacks,
  registries, external automation and submodule references. No tracked workflows
  or `.gitmodules` was found at the baseline; verify live/external settings too.
- [ ] In GitHub Settings, rename the existing repository after signoff. Do not
  create a replacement repository, rewrite history or transfer ownership.
- [ ] Verify repository/PR/issue navigation, Projects links, open PR targets,
  permissions, protections, checks and external integrations. GitHub redirects
  many old repository URLs/Git operations, but callers of an action hosted in a
  renamed repository are not redirected; inspect any such consumers explicitly.
- [ ] Update current README badges, clone instructions, active documentation,
  integration URLs and teammate remotes: `git remote set-url origin
  https://github.com/Suthankan1/swiftdrop.git`. Each teammate verifies `fetch`
  and branch tracking. Keep immutable historical links and PR evidence intact.
- [ ] Recheck submodule URLs and GitHub Pages separately if any exist. Do not
  reuse the old `devarom` repository name: doing so breaks GitHub's redirects.
- [ ] Record the verified rename date, responsible admin, before/after URLs and
  integration check results. If rollback is needed, freeze concurrent GitHub
  settings changes and have the admin restore the prior name/settings and
  reconcile remotes and integrations; do not erase new issues/PRs/history.

These redirect and action limitations are documented in
[GitHub's repository rename guide](https://docs.github.com/en/repositories/creating-and-managing-repositories/renaming-a-repository).
The checklist is preparatory; hosted settings and paid resources were not
changed or provisioned by Phase 1.

Next small task: approve a **non-production identity backup/restore and
subject-continuity rehearsal** before scheduling the identity rename.
