# SwiftDrop Phase 1 name audit

Audit date: **2026-10-20**. Baseline: `eff629af085299a5560e0b3c912e46ea4d056d42`
(`origin/test` when this work began). This is a source audit, not evidence that
an existing installation or external provider was reconfigured.

The company and product name is **SwiftDrop**. The platform was referred to as
**Devarom** during initial development. This Phase 1 source rebrand is dated
2026-10-20; the old name remains in technical bindings and historical evidence.
Earlier documents also record SwiftDrop in early drafts. Those records do not
mean that the current name decision changes the v1.3 service/protocol design.

## Scope and reproducible inventory

[NAME_OCCURRENCES.csv](NAME_OCCURRENCES.csv) accounts for every case-insensitive
`devarom` content match and tracked path match in the baseline. It records only
the matched name, location and decision, never surrounding secret-bearing text.
Line and column coordinates refer to the frozen baseline, not this PR's edited
files; columns are one-based character offsets. A `path` row uses line `0`.
Files with mixed visible wording, runtime bindings and evidence have separate
classifications for those occurrences.

| Discovery | Result |
|---|---:|
| Matching content lines | 801 |
| Content occurrences / files | 959 / 166 |
| Tracked path occurrences / files | 51 / 47 |
| Distinct tracked files across content and paths | 172 |
| CSV data rows | 1,010 |

Exact case-sensitive content counts are `Devarom` **296**, `devarom` **653**,
and `DEVAROM` **10**. The requested subpattern `devarom-` occurs **250** times
within those matches; `devarom_` and `DevaRom` have **0** exact matches. These
subpattern counts are not additional occurrences.

Discovery commands, run against tracked files:

```bash
git grep -n -I -i -e devarom eff629af085299a5560e0b3c912e46ea4d056d42
git ls-tree -r --name-only eff629af085299a5560e0b3c912e46ea4d056d42
git grep -n -I -i -e devarom
```

The final command checks the changing working tree and will intentionally still
find runtime names, provenance and these migration documents. An empty global
search is not a Phase 1 acceptance criterion. Ignored `.env`, private snapshots,
tokens, personal accounts, production configuration and cloud-console credentials
were outside this audit. Generated builds and untracked exports were not read.

## Classification and impact matrix

Counts below cover content matches; all 51 path matches are category C.
Classification describes each baseline occurrence's purpose and treatment.

| Class | Content matches | Locations and examples | Phase 1 treatment |
|---|---:|---|---|
| **A — Public/user-visible branding** | 99 | SPA title/metadata, auth pages, navigation, landing/customer copy; Keycloak messages, textual SVG wordmarks and template text; realm/client display labels and sender display name; editable diagram labels | Change visible wording and corresponding assertions to SwiftDrop. Preserve the existing brand mark, routes and authentication behavior. Source defaults do not update an existing realm automatically. |
| **B — Current documentation/product wording** | 153 | Root/module READMEs; current architecture, contracts, requirements, security, handoff, milestone and AI instructions; explanatory comments and current script banners | Update product prose without changing requirements, ownership, service boundaries or recorded results. |
| **C — Technical runtime identifier** | 530 | Realm/client/audience/default role; issuer/JWKS URLs; `com.devarom`, Maven coordinates, property keys, `DevaromRole`; theme directory, CSS hooks and asset filenames; containers/volume, synthetic fixtures and local paths | Retain. Coordinate any change through the [Phase 2 proposal](PHASE_2_MIGRATION.md). Identifier text inside an error message can remain when it accurately names the unchanged runtime. |
| **D — External provider configuration** | 13 | Google broker callback URLs, recorded Google project/consent/client labels; GitHub clone/repository bindings | Preserve existing bindings. Visible provider branding requires a separately verified console update; repository rename is a later reviewed operation. |
| **E — Historical/provenance reference** | 131 | Dated status/results, contribution record, original PR branch names, issue URLs, accepted ADR baseline metadata, quoted report/test evidence | Preserve original names, PRs, SHAs and claims. Add a current naming note rather than rewriting what a prior run reported. |
| **F — Generated artifact** | 31 | Matching content in the 20 rendered diagram SVGs, including embedded diagram data | Regenerate from corresponding edited Draw.io sources using available tooling; check both label legibility and semantic preservation. |
| **G — Sensitive configuration requiring caution** | 2 | Sanitized SMTP sender in `.env.example`; documented private realm-export filename | Keep the secret/configuration boundary. No real environment, export or credentials are included in the inventory. Review sender delivery/provider changes separately. |

Every matching location, including files that contain no old public text but
have an old filename, is listed in the CSV. A category E occurrence can quote a
technical name: the primary reason to retain that occurrence is preservation of
the original evidence, while the live binding is separately inventoried as C.

### Location coverage

| Surface | Audit coverage and treatment |
|---|---|
| Root and contributor guidance | `README.md`, `PROJECT_STRUCTURE.md`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.cursor/rules/devarom.mdc`, `.github/copilot-instructions.md` and feature template: current product wording changes; existing technical paths remain. |
| Current documentation | `docs/ai`, `architecture`, `contracts`, `development`, `milestones`, `project`, `report`, `requirements`, `security`, `team`: prose changes are separated from code snippets, original evidence and immutable links. |
| Architecture decisions | ADR decisions, statuses and historical baseline metadata remain authoritative. Current naming notes do not reopen protocol/auth decisions or approve proposed ADR-0009. Proposed SPI class names remain technical proposals, not implemented features. |
| Report figures | All 20 editable `.drawio` and matching rendered `.svg` files contain old-name matches. Preserve numbering, captions, actors, arrows, ERDs and proposed-design disclaimers. |
| Frontend | `index.html`, auth/layout/landing/customer components and visible-brand tests are A. `package.json`, `DevaromRole`, hardcoded realm/client, test fixtures and invalid-route fixture remain C. No route, callback or authorization contract changes are implied. |
| Keycloak | Realm display labels, theme messages/template text and text wordmarks are A. Realm ID/name, client ID, mapper/audience, role names, theme ID/path/hooks and broker credentials remain C/D. SMTP sender display wording may change; mailbox/provider settings do not. |
| Identity scripts/tests | Visible copy/assertions and headings are distinguished from realm guards, expected protocol values, CSS/asset selectors and synthetic mailbox fixtures. Do not make a runtime assertion pass by renaming its unchanged expectation. |
| Java/configuration | Both implemented services use `com.devarom`; Maven coordinates and `devarom.jwt` / `devarom.cors` bindings remain unchanged. Both independently validate the same issuer/audience. |
| Local infrastructure/E2E | User-facing help/banner wording may change. Container names, the Accounts data volume, realm URLs, fixture addresses, token claims and endpoint paths remain unchanged. |
| CI and integrations | No tracked `.github/workflows` or `.gitmodules` exists at the baseline. This does not establish that GitHub-hosted rules, Projects, integrations or external consumers are absent. |

## Verified tracked identity baseline

These are checked-in values inspected in the sanitized realm, SPA session
adapter, service configuration, scripts and schema. They are not a fresh live
admin export, and environment overrides can differ.

| Binding | Current tracked value | Proposed Phase 2 value |
|---|---|---|
| Realm name / imported realm ID | `devarom` / `devarom` | Realm name `swiftdrop`; preserve internal identity IDs unless a separately approved migration proves continuity |
| Public browser client ID | `devarom-frontend` | `swiftdrop-frontend` |
| JWT custom audience | `devarom-api` | `swiftdrop-api` |
| Local issuer | `http://localhost:8090/realms/devarom` | `http://localhost:8090/realms/swiftdrop` |
| Local JWKS | `/realms/devarom/protocol/openid-connect/certs` | Matching new realm path |
| Google provider alias | `google` | Preserve `google` |
| Local Google callback | `http://localhost:8090/realms/devarom/broker/google/endpoint` | Same origin, proposed `swiftdrop` realm path |
| SPA callback / logout return | `http://localhost:5173/auth/callback` / `http://localhost:5173/` | Preserve unless independently approved |
| Default composite role | `default-roles-devarom`, only `CUSTOMER` application role by default | Assess role-name dependencies; do not change privilege membership |
| Theme ID | `devarom` | Optional separate technical change; visible branding does not require it |
| SMTP sender | `no-reply@devarom.test` | Keep for Phase 1; sender/domain approval later |
| Java namespace / config prefix | `com.devarom` / `devarom.*` | Proposed `com.swiftdrop` / `swiftdrop.*` in a separate mechanical change |
| Repository | `Suthankan1/devarom` | `Suthankan1/swiftdrop`, after review and team communication |

The sanitized Google broker import is disabled and contains environment
placeholders; it does not describe the current enabled runtime broker. Actual
Google client ID, secret, Cloud project ID and production issuer were not read.

The current identity Compose uses Keycloak's embedded development storage with
no persistent Keycloak data volume. Its mounts are the sanitized realm and theme
sources. Preserve the existing container; recreating it is not a safe rebrand or
a backup method. The Accounts local startup script separately uses
`devarom-accounts-postgres` and volume `devarom-accounts-postgres-data`.

Accounts resolves profiles by verified JWT `sub`, stored in unique immutable
`account_profiles.keycloak_sub`. A database trigger rejects subject rebinding.
A new subject can therefore create a second profile or lose access to the
existing profile. Matching email addresses is not a safe identity migration.

## Evidence, acceptance and remaining dependencies

Existing [current L03 evidence](../project/STATUS.md#current-l03-real-google-login-profile-and-logout--2026-10-20)
records **PASS for the authorized local Google run on 2026-10-20**, with the user
performing Google authentication; independent teammate review remains pending.
Earlier blocked or unverified runs remain historical. This audit does not claim
that Google login was rerun for branding.

Phase 1 browser checks observed SwiftDrop on the public SPA and native Keycloak
login/registration screens, with the protected customer route returning to login
for an unauthenticated session. Frontend, identity, Python, Gateway, Accounts,
readiness and available demo regressions passed. Security E2E retained four
existing blocked business endpoints and reported no failures. Exact commands,
results, diagram checks and the scope of browser evidence are recorded in
[VALIDATION.md](VALIDATION.md). Interactive Google login was **not rerun** for
the rebrand; its prior authorized PASS remains historical evidence.

The realm/client/issuer/audience, subject/schema, Google bindings, package names
and storage identifiers remain unchanged. A static check or Google broker probe
does not establish a new interactive Google end-to-end run.

External dependencies remain explicitly separate from source changes: Google
consent-screen/client display labels; existing realm display/SMTP metadata;
running Keycloak theme cache; the original academic report/design package and
other copies outside Git; GitHub repository settings/Projects/integrations and
teammate remotes. Verify which are applicable and report completion only after
observing their state. The repository is not renamed by this PR.

Next small task: review the source branding PR into `test`, including the
regression evidence and any remaining installed-theme/provider branding work,
before authorizing a Phase 2 identity rehearsal.
