# Kafka domain events — proposed v1 contract

Kafka KRaft replaces the old RabbitMQ proposal. **Start event work only after the Delivery outbox/status slice exists**; Kafka is not a Nov 1 login requirement.

| Item | Proposed definition |
|---|---|
| Topic | `parcel.lifecycle.v1` |
| Event type | `parcel.status.changed.v1` |
| Partition key | `parcelId` (keep all one-parcel events in the same partition) |
| Minimum envelope data | immutable event ID, event type, `aggregateId` (parcel), `aggregateVersion`, occurred-at time, `correlationId`, old/new status and required non-sensitive identifiers |
| Producer | Delivery transactional outbox publisher |
| Consumers | Communications notification group; Communications webhook group; Delivery SSE relay group(s) |
| Guarantee | Durable **at-least-once** publishing/consumption, not universal exactly-once delivery |
| Replay safety | Durable `(consumerName,eventId)` receipts, aggregate-version staleness checks |
| Error strategy | Retry with limits/jitter, inspected DLT; durable webhook jobs and operator-controlled replay |

**Privacy:** no raw contact details, proof URL, token, full street address or OAuth claims in payload. Notification workers resolve permitted recipient/contact via authenticated Delivery and Accounts APIs. A Kafka offset commit must not precede durable inbox receipt and job storage. External email/webhooks may still duplicate around provider acknowledgement; receiver deduplicates event ID and compares aggregate version to avoid stale overwrites.

**SSE scaling:** same-partition ordering is not broadcast; two Delivery SSE replicas in a *shared* Kafka consumer group would each see only some events. Use a proven fan-out design (the target diagram proposes unique tracking consumer groups per SSE-serving replica). A single-replica demo is explicitly not HA; browser reconnect always refetches authoritative status.

Freeze exact payload schema using a contract PR. The previous v1.3 design pack proposed the same topic/type; this prose is **not** a generated JSON Schema artifact.
