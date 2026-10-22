# Editable diagrams — SwiftDrop Nov 1 design pack

These **20 editable `.drawio`** files were originally copied unchanged from the uploaded Devarom group design package. On **2026-10-20**, their current product labels were updated to **SwiftDrop**, and all matching `.svg` previews were regenerated with Draw.io Desktop 32.4.1. Source geometry, connectors, IDs, provenance metadata and technical identifiers were preserved. This repo intentionally excludes the 13 MB package's PDFs, PNG exports, atlas, contact sheets, master duplicate and rebuild scripts.

- **Edit:** Open one `editable/*.drawio` in https://app.diagrams.net/ or Draw.io Desktop. Native text/connectors stay editable.
- **Publish:** Export an updated SVG with the **same stem** into `rendered/`; include diagram and preview in one reviewed PR. Changes are **not auto-synced**.
- **Regenerate:** With Draw.io Desktop installed on macOS, run `'/Applications/draw.io.app/Contents/MacOS/draw.io' --export --format svg --size page --theme light --embed-svg-fonts false --output docs/diagrams/rendered docs/diagrams/editable` from the repository root. The full-page export preserves each source canvas. Check the SVGs visually before publication.
- **In the report:** Reference a crisp SVG converted to the required document by the report owner. Do not commit bulky PDF report outputs into this codebase.
- **Compatibility:** Native Draw.io SVGs use HTML text labels with SVG text fallbacks. If a report converter does not render those labels correctly, export directly from the editable source using Draw.io; keep temporary PDF/PNG outputs outside the repository.
- **Scope:** Diagrams are **architecture targets**. Kubernetes/Helm figure 19 and Compose figure 20 do **not** mean deployed infrastructure.
- **Consistency:** Current product labels use **SwiftDrop**. Original source provenance and the proposed `devarom` namespace in figure 19 remain legacy identifiers; the namespace is deferred to the technical migration review. If business scope/protocol changes, update ADR/contracts and only the affected diagrams.

**Issue #13 review (2026-10-22):** Figures **04/08** clarify controlled Admin powers, authorized provider reads, manual assignment and guarded failure/refresh/stop paths. See [Dispatcher/Admin review](../reviews/DISPATCHER_ADMIN_REVIEW.md) for coverage, validation and unresolved policy. Both editable sources and matching SVGs are updated together; other figures retain their existing exports and review ownership.

| ID | Diagram / SVG preview | Editable source | Suggested reviewer |
|---|---|---|---|
| 01 | [Customer use cases](rendered/01_customer_use_cases.svg) | [Edit](editable/01_customer_use_cases.drawio) | Customer pair |
| 02 | [Merchant use cases](rendered/02_merchant_use_cases.svg) | [Edit](editable/02_merchant_use_cases.drawio) | Merchant pair |
| 03 | [Courier use cases](rendered/03_courier_use_cases.svg) | [Edit](editable/03_courier_use_cases.drawio) | Courier pair |
| 04 | [Dispatcher/Admin use cases](rendered/04_dispatcher_admin_use_cases.svg) | [Edit](editable/04_dispatcher_admin_use_cases.drawio) | Dispatcher/Admin pair |
| 05 | [System context](rendered/05_system_context.svg) | [Edit](editable/05_system_context.drawio) | Platform + everyone |
| 06 | [Logical container architecture](rendered/06_logical_architecture.svg) | [Edit](editable/06_logical_architecture.drawio) | Platform + all service owners |
| 07 | [Customer quote and booking activity](rendered/07_quote_booking_activity.svg) | [Edit](editable/07_quote_booking_activity.drawio) | Customer + Dispatcher |
| 08 | [Dispatcher courier assignment activity](rendered/08_assignment_activity.svg) | [Edit](editable/08_assignment_activity.drawio) | Dispatcher + Courier |
| 09 | [Courier status, Kafka and notifications activity](rendered/09_status_event_activity.svg) | [Edit](editable/09_status_event_activity.drawio) | Courier + Merchant + Platform |
| 10 | [Customer delivery booking sequence](rendered/10_customer_delivery_sequence.svg) | [Edit](editable/10_customer_delivery_sequence.drawio) | Customer pair |
| 11 | [Merchant order API sequence](rendered/11_merchant_order_sequence.svg) | [Edit](editable/11_merchant_order_sequence.drawio) | Merchant pair |
| 12 | [Courier status and webhook sequence](rendered/12_courier_status_sequence.svg) | [Edit](editable/12_courier_status_sequence.drawio) | Courier + Merchant |
| 13 | [Google login via Keycloak sequence](rendered/13_google_oidc_sequence.svg) | [Edit](editable/13_google_oidc_sequence.drawio) | Platform + Customer |
| 14 | [Delivery Operations logical ERD](rendered/14_delivery_erd.svg) | [Edit](editable/14_delivery_erd.drawio) | Customer + Courier + Dispatcher |
| 15 | [Accounts and Pricing logical ERD](rendered/15_accounts_pricing_erd.svg) | [Edit](editable/15_accounts_pricing_erd.drawio) | Merchant + Customer + Dispatcher |
| 16 | [Communications logical ERD](rendered/16_communications_erd.svg) | [Edit](editable/16_communications_erd.drawio) | Merchant + Platform |
| 17 | [Parcel lifecycle state diagram](rendered/17_parcel_state_machine.svg) | [Edit](editable/17_parcel_state_machine.drawio) | Courier + Dispatcher |
| 18 | [Kafka reliability and SSE fanout](rendered/18_kafka_sse_topology.svg) | [Edit](editable/18_kafka_sse_topology.drawio) | Platform + Courier + Merchant |
| 19 | [Kubernetes deployment target (bonus only)](rendered/19_kubernetes_bonus.svg) | [Edit](editable/19_kubernetes_bonus.drawio) | Platform + entire team |
| 20 | [Docker Compose target (planned)](rendered/20_compose_target.svg) | [Edit](editable/20_compose_target.drawio) | Platform + entire team |
