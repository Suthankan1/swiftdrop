# Editable diagrams — original Devarom Nov 1 design pack

These **20 original `.drawio`** files were copied **unchanged** from the uploaded group design package. Each has a matching **lightweight `.svg` preview**. This repo intentionally excludes the 13 MB package's PDFs, PNG exports, atlas, contact sheets, master duplicate and rebuild scripts.

- **Edit:** Open one `editable/*.drawio` in https://app.diagrams.net/ or Draw.io Desktop. Native text/connectors stay editable.
- **Publish:** Export an updated SVG with the **same stem** into `rendered/`; include diagram and preview in one reviewed PR. Changes are **not auto-synced**.
- **In the report:** Reference a crisp SVG converted to the required document by the report owner. Do not commit bulky PDF report outputs into this codebase.
- **Scope:** Diagrams are **architecture targets**. Kubernetes/Helm figure 19 and Compose figure 20 do **not** mean deployed infrastructure.
- **Consistency:** Diagram names use **Devarom**. If business scope/protocol changes, update ADR/contracts and only the affected diagrams.

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
