#!/usr/bin/env sh
# ==============================================================================
# SwiftDrop Platform — Kafka Lifecycle Topics Provisioner
# Safe & Idempotent: Uses --if-not-exists to prevent recreating existing topics.
# ==============================================================================
set -e

echo "==> Provisioning Kafka lifecycle topics..."
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 \
  --create --if-not-exists \
  --topic "${KAFKA_TOPIC_PARCEL_LIFECYCLE:-parcel.lifecycle.v1}" \
  --partitions "${KAFKA_DEFAULT_PARTITIONS:-3}" \
  --replication-factor "${KAFKA_DEFAULT_REPLICATION_FACTOR:-1}"

/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 \
  --create --if-not-exists \
  --topic "${KAFKA_TOPIC_PARCEL_LIFECYCLE_DLT:-parcel.lifecycle.v1.dlt}" \
  --partitions "${KAFKA_DEFAULT_PARTITIONS:-3}" \
  --replication-factor "${KAFKA_DEFAULT_REPLICATION_FACTOR:-1}"

echo "==> Kafka topics provisioned successfully:"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe --topic "${KAFKA_TOPIC_PARCEL_LIFECYCLE:-parcel.lifecycle.v1}"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe --topic "${KAFKA_TOPIC_PARCEL_LIFECYCLE_DLT:-parcel.lifecycle.v1.dlt}"
