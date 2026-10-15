# Change policy and working agreement

This repo contains design targets and eventually application implementation. **The actual code/test state is the truth of what's implemented.** A diagram or ADR alone is never executable evidence.

- Use an ADR for material changes to protocol, extra service, user-role model, data DB owner, auth behavior, Kafka payload/topic, public API or approved team ownership; get cross-pair review first.
- Keep editable diagrams as source in `docs/diagrams/editable`; refresh matching SVG only after diagram review. The pre-existing Draw.io master is deliberately absent to avoid two diverging authorities.
- Avoid keeping binary PDFs/PNGs/report atlas, course lecture files, unrelated scripts, experimental Docker manifests, generated clients and build products in Git by default.
- Put only contract drafts and discussion prose under docs until actual owner PRs generate source schemas. No placeholder pseudocode presented as implemented package.
- If task requires secret-bearing local configuration later, add sanitized `*.example`, ignore real values and provide env setup docs.
- Avoid unrelated framework experiments (Next.js BFF, GraphQL mutations, gRPC-Web, Kafka replacement, GPS tracking) without team decision.
- Change owner-driven module files only after explicit owner notification/reviewer agreement; platform coordination isn't universal edit permission.
