# Report diagram placement and requirement coverage

Use [`20-diagram index`](../diagrams/README.md) and [`52-row assignment-traceability.csv`](assignment-traceability.csv) together. **All figures are editable sources; none proves completed runtime behavior.**

| Report section | Essential diagram IDs | Main actor/technical concept |
|---|---|---|
| Functional scope / UML use cases | 01–04 | Customer, Merchant, Courier, Dispatcher/Admin |
| System boundary / architecture | 05–06 | React/Gateway; 4 business services; 1 GraphQL API; REST/gRPC/Kafka/SSE/Keycloak |
| Process flowcharts | 07–09 | Booking/quote, assignment, status/event handling |
| Interactions/sequences | 10–13 | Customer create, merchant order, courier update/notification, Google Keycloak OIDC |
| Data model / ERDs | 14–16 | Delivery, Accounts/Pricing, Communications — separate ownership |
| Domain lifecycle | 17 | CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED or FAILED |
| Event reliability | 18 | Delivery outbox/Kafka/Communications/DLT/SSE tracking |
| Deployment targets, optional/future | 19–20 | K8s bonus, Compose full-stack target (not Nov 1 implemented) |

**Nov 1 report minimum:** diagrams 01–17 plus architecture/reliability figure 18, narrative justifications and truthfully reported login implementation evidence. Figure 19 is bonus target (do not claim running), 20 is planned deployment view. Confirm report template/length with lecturer; all 20 need not be embedded if appendix allowed.
