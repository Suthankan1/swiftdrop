# SwiftDrop Phase 1 verification — 2026-10-20

Owner: **Suthankan1** (Platform implementation and self-verification; independent
peer review remains pending). Scope: source branding and regression checks on
`chore/swiftdrop-branding`, based on `origin/test` at
`eff629af085299a5560e0b3c912e46ea4d056d42`.

## Git and scope

- `git fetch origin --prune`: **PASS** (required sandbox escalation to write Git
  refs and access the remote). The starting working tree was clean.
- `origin/main`: `69e70b36344463ed6981760b6fb3378e201f9d37`.
- `origin/test`: `eff629af085299a5560e0b3c912e46ea4d056d42`.
- `git rev-list --left-right --count origin/main...origin/test`: **`1 0`**;
  `git diff origin/main origin/test` was empty. The single main-only commit is
  release merge PR [#58](https://github.com/Suthankan1/devarom/pull/58), merged
  2026-10-20. Latest development merge was
  [#57](https://github.com/Suthankan1/devarom/pull/57). No open PRs were found at
  the initial remote check.
- Branch created directly from `origin/test`; no changes to `main` or `test`.
  The branding PR targets **`test`** and is not automatically merged.
- No business Java source, JWT configuration, SQL migration, package, realm ID,
  client ID, audience, provider credential, storage name or user identity changed.
  The sanitized realm diff contains exactly four presentation values:
  `displayName`, `displayNameHtml`, client `name`, SMTP `fromDisplayName`.

## Commands and actual results

| Working directory | Command | Result |
|---|---|---|
| `frontend` | `pnpm typecheck` | **PASS** |
| `frontend` | `pnpm test run` | **PASS — 64 tests in 7 files** |
| `frontend` | `pnpm lint` | **PASS** |
| `frontend` | `pnpm build` | **PASS** |
| Repository root | `./platform/identity/validate-identity.sh` | **PASS — all 14 static checks, added branding guards, and live checks**; discovery/JWKS, ROPC/redirect/plain-PKCE rejection, SwiftDrop theme, TOTP policy, Google broker/probe/negative callback checks |
| Repository root | `python3 -m unittest discover -s platform/identity/tests -p 'test_*.py'` | **PASS — 62 tests** |
| `services/api-gateway` | `./mvnw test` | **PASS — 13 tests, zero failures/errors/skips** |
| `services/accounts-service` | `./mvnw test` | **PASS — 37 tests, zero failures/errors/skips** |
| Repository root | `./platform/local/check-readiness.sh` | **PASS — 13 checks, zero warnings/failures after starting the application servers** |
| Repository root | `/tmp/swiftdrop-qa-venv/bin/python tests/security-e2e/demo_smoke.py` | **PASS — scenarios A–D**: registration/email verification/PKCE/profile/logout; verified local login; TOTP smoke; JWT and ownership controls |
| Repository root | `/tmp/swiftdrop-qa-venv/bin/python tests/security-e2e/security_e2e_runner.py` | **36 PASS, 0 FAIL, 4 BLOCKED** out of 40; exit 0, with missing provider endpoints still explicitly blocked |
| Repository root | Draw.io Desktop exports documented in [diagram README](../diagrams/README.md) | **PASS — all 20 SVGs regenerated from updated sources**; 30 visible label changes, source structure/geometry/connectors unchanged |
| Repository root | `git diff --check` | **PASS** |

Initial sandbox pnpm attempts stalled and were cancelled; final pnpm commands
passed outside those restrictions. Python socket tests initially failed because
the sandbox blocked loopback binding, then all passed with escalation. The first
readiness run also could not reach Docker/HTTP through the sandbox. The next
unrestricted run correctly found identity containers healthy and Accounts,
Gateway and SPA stopped (10 passing checks, 3 failing readiness prerequisites).
Those application servers were started against the existing containers, and the
final readiness run passed 13/13. No container or realm was recreated.

The system `python3 tests/security-e2e/demo_smoke.py` initially failed on its
missing `cryptography` dependency. A temporary isolated venv was created outside
the repository, with `cryptography` 50.0.2 installed; both harnesses then passed
as reported above. No dependency manifest was changed. Generated reports, builds
and temporary QA images are excluded from commits.

Accounts PostgreSQL integration verification (`./mvnw verify`) was
**NOT RUN** in this branding task. The 37-test result above is the requested
unit/MVC/security command, not the earlier 53-test historical aggregate. Live
profile persistence was exercised by the existing demo and security harnesses.

The four E2E blocked cases are existing missing providers: admin operations
(Issue #14), courier operations (Issue #15), a cross-user profile lookup route
that is deliberately absent, and merchant tenant endpoints (Issue #8). These
unmapped requests were denied with 404; the suite does not relabel them as
completed endpoint authorization tests.

## Browser and end-to-end boundaries

The story checked was: SwiftDrop entry page → existing Keycloak PKCE login →
Gateway → subject-bound Accounts profile → logout, with existing denials intact.

| Boundary | Evidence and limit |
|---|---|
| SPA branding/navigation | Browser home and `/login` showed SwiftDrop document title, wordmark, footer and login heading. Existing Sign in, Continue with Google and Create account controls remained present. |
| Native login/registration | Browser Sign in opened the existing `devarom` realm/client with PKCE S256. Login title was `Sign In • SwiftDrop`; native registration title was `Create Account • SwiftDrop`, with `Get started with SwiftDrop` copy. Wordmarks fit without clipping in the observed desktop browser. |
| Protected browser route | Anonymous `/customer` redirected to `/login`. Existing callback, session and role unit tests passed. |
| API/data/response | Demo and E2E harnesses used real local PKCE tokens; profile requests passed through Gateway to Accounts, returned the correct synthetic subject/profile, and repeated reads retained the same profile ID. |
| Denial boundaries | Live E2E independently checked Gateway and direct Accounts rejection, wrong roles, spoofed headers/query parameters and Customer A/B profile isolation. |
| Email/MFA/logout | Synthetic registration, Mailpit verification, TOTP smoke and session termination passed; disposable identities/messages were cleaned up by the harness. Existing accounts were retained. |
| Google | Live broker construction, provider redirect probe and negative callback checks passed. **Interactive Google sign-in was NOT RERUN**. The prior real L03 PASS on 2026-10-20 remains dated historical evidence, with independent teammate review pending. |

All 20 diagram renders were visually inspected using temporary PNG contact
sheets; dense figures 06, 13 and 15 were also inspected at full resolution.
Native SVGs use HTML labels with SVG text fallbacks; report converters must
support that format or export via Draw.io. Architecture targets remain targets.

## Remaining installed/external branding

Source-visible UI branding is SwiftDrop. Legacy names remain deliberately in
runtime paths/types/configuration, fixtures, accepted ADRs, historical evidence
and immutable links; the [impact inventory](PHASE_1_AUDIT.md) explains them.

Existing persisted realm/client labels, realm-provided native email text/sender
display name and local environment overrides require an owner-applied
presentation-only update. Editing the sanitized import does not update an
existing realm. The login/registration theme was observed with SwiftDrop via
the existing theme mount, but no live admin metadata update was performed.
Google consent-screen branding also requires its authorized administrator.
Original report/PDF/design-package copies and other external documents were
not supplied as editable repository sources and were not updated. The GitHub
repository, Projects/settings, integrations and teammates' remotes retain their
existing names until the reviewed rename operation.

The November 1 local login slice passed the available regression checks after
branding. This does not substitute for independent clean-clone acceptance or a
fresh interactive Google login. Next small task: review this PR into `test` and
agree the presentation-only update for persisted identity/provider labels.
