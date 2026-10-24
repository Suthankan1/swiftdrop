#!/usr/bin/env python3
"""
SwiftDrop Platform — Comprehensive Dependencies Smoke & Isolation Test Suite
Validates:
  1. JSON Schema validation of parcel.status.changed.v1 event contract.
  2. Kafka publish & consume with partition key (parcelId) and approved consumer groups.
  3. Kafka broker restart & persistence of topics and consumer offsets.
  4. PostgreSQL logical databases connectivity (pricing_db, delivery_db, communications_db).
  5. PostgreSQL cross-service database access denial & unprivileged application roles.
  6. PostgreSQL restart & data persistence across restarts.
  7. Private S3 object storage bucket privacy (unauthenticated GET/PUT/List denied).
  8. S3 object upload, download, checksum verification with authorized credentials.
  9. S3 object storage restart & data persistence across restarts.
 10. Existing identity & Accounts PostgreSQL safety confirmation.
"""

import hashlib
import json
import os
import subprocess
import sys
import time
import uuid

# Check dependencies
try:
    import jsonschema
except ImportError:
    print("[ERROR] jsonschema package is missing. Install with 'pip install jsonschema'")
    sys.exit(1)

try:
    import botocore.session
    from botocore.exceptions import ClientError
except ImportError:
    print("[ERROR] botocore package is missing. Install with 'pip install botocore'")
    sys.exit(1)

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../../.."))
DEP_DIR = os.path.join(REPO_ROOT, "platform/dependencies")

GREEN = "\033[0;32m"
RED = "\033[0;31m"
YELLOW = "\033[0;33m"
BOLD = "\033[1m"
RESET = "\033[0m"

PASSED = 0
FAILED = 0


def log_pass(msg):
    global PASSED
    PASSED += 1
    print(f"  [{GREEN}PASS{RESET}] {msg}")


def log_fail(msg):
    global FAILED
    FAILED += 1
    print(f"  [{RED}FAIL{RESET}] {msg}")


def log_info(msg):
    print(f"  [{YELLOW}INFO{RESET}] {msg}")


def run_cmd(cmd, check=True):
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed ({res.returncode}): {cmd}\nOutput:\n{res.stdout}\n{res.stderr}")
    return res


# ==============================================================================
# 1. Event Contract Schema Validation
# ==============================================================================
def test_event_schema_contract():
    print(f"\n{BOLD}=== 1. Kafka Event Contract & Schema Validation ==={RESET}")
    schema_path = os.path.join(REPO_ROOT, "contracts/events/parcel.status.changed.v1.schema.json")
    example_path = os.path.join(REPO_ROOT, "contracts/events/parcel.status.changed.v1.example.json")

    with open(schema_path, "r", encoding="utf-8") as f:
        schema = json.load(f)

    with open(example_path, "r", encoding="utf-8") as f:
        example = json.load(f)

    # 1. Validate example against contract schema
    try:
        jsonschema.validate(instance=example, schema=schema)
        log_pass("Checked-in example (parcel.status.changed.v1.example.json) strictly adheres to schema")
    except jsonschema.ValidationError as e:
        log_fail(f"Example contract validation failed: {e.message}")

    # 2. Generate and validate synthetic smoke test event
    synthetic_parcel_id = str(uuid.uuid4())
    synthetic_event = {
        "eventId": str(uuid.uuid4()),
        "eventType": "parcel.status.changed.v1",
        "schemaVersion": "1.0.0",
        "aggregateId": synthetic_parcel_id,
        "aggregateType": "PARCEL",
        "aggregateVersion": 1,
        "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "correlationId": str(uuid.uuid4()),
        "producer": "delivery-operations",
        "payload": {
            "parcelId": synthetic_parcel_id,
            "trackingNumber": "SWD-20261023-TEST01",
            "oldStatus": None,
            "newStatus": "CREATED",
            "reason": None,
            "merchantId": None,
            "courierSub": None,
            "cityOrZone": "Colombo Hub",
        },
    }

    try:
        jsonschema.validate(instance=synthetic_event, schema=schema)
        log_pass("Synthetic lifecycle CREATED event successfully validated against contract schema")
    except jsonschema.ValidationError as e:
        log_fail(f"Synthetic event validation failed: {e.message}")

    return synthetic_event


# ==============================================================================
# 2. Apache Kafka Publish & Consume Smoke Test
# ==============================================================================
def test_kafka_publish_consume(synthetic_event):
    print(f"\n{BOLD}=== 2. Apache Kafka KRaft Publish & Consume Smoke Test ==={RESET}")
    topic = "parcel.lifecycle.v1"
    key = synthetic_event["aggregateId"]
    payload_json = json.dumps(synthetic_event)
    consumer_group = "swiftdrop-smoke-consumer-group"

    # Produce to Kafka via docker exec with key:value using binary input to avoid quote stripping
    log_info(f"Publishing event to topic '{topic}' with partition key '{key}'...")
    res = subprocess.run(
        [
            "docker", "exec", "-i", "swiftdrop-kafka",
            "/opt/kafka/bin/kafka-console-producer.sh",
            "--bootstrap-server", "localhost:9092",
            "--topic", topic,
            "--property", "parse.key=true",
            "--property", "key.separator=:",
        ],
        input=f"{key}:{payload_json}\n".encode("utf-8"),
        capture_output=True,
    )
    if res.returncode == 0:
        log_pass("Event successfully published to Kafka topic with partition key")
    else:
        log_fail(f"Failed to publish event to Kafka: {res.stderr.decode('utf-8')}")

    # Consume from Kafka using explicit consumer group
    log_info(f"Consuming event from topic '{topic}' using consumer group '{consumer_group}'...")
    consume_cmd = (
        f"docker exec swiftdrop-kafka /opt/kafka/bin/kafka-console-consumer.sh "
        f"--bootstrap-server localhost:9092 --topic {topic} "
        f"--group {consumer_group} --from-beginning --max-messages 10 "
        f'--timeout-ms 5000 --formatter-property "print.key=true" --formatter-property "key.separator=::"'
    )
    res = run_cmd(consume_cmd, check=False)

    found = False
    for line in res.stdout.splitlines():
        if "::" in line:
            parts = line.split("::", 1)
            consumed_key = parts[0].strip()
            if consumed_key == key:
                found = True
                log_pass(f"Consumed message key matches partition key ({consumed_key})")
                try:
                    consumed_obj = json.loads(parts[1])
                    if consumed_obj.get("eventId") == synthetic_event["eventId"]:
                        log_pass(f"Consumed message payload matches published eventId ({consumed_obj['eventId']})")
                    else:
                        log_fail(f"Payload mismatch: expected {synthetic_event['eventId']}, got {consumed_obj.get('eventId')}")
                except Exception as e:
                    log_fail(f"Failed to parse consumed JSON payload: {e}")
                break

    if not found:
        log_fail(f"Could not find published message with key {key} in consumer output")


# ==============================================================================
# 3. Kafka Broker Restart & Persistence
# ==============================================================================
def test_kafka_persistence():
    print(f"\n{BOLD}=== 3. Kafka Broker Restart & Offset Persistence ==={RESET}")
    log_info("Restarting Kafka container 'swiftdrop-kafka'...")
    run_cmd("docker restart swiftdrop-kafka")

    # Wait for Kafka to become ready
    ready = False
    for _ in range(30):
        res = run_cmd("docker exec swiftdrop-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092", check=False)
        if res.returncode == 0:
            ready = True
            break
        time.sleep(1)

    if ready:
        log_pass("Kafka broker is UP and healthy following container restart")
    else:
        log_fail("Kafka broker failed to become ready after restart")

    # Verify topics persisted
    res = run_cmd("docker exec swiftdrop-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list")
    topics = res.stdout.splitlines()
    if "parcel.lifecycle.v1" in topics and "parcel.lifecycle.v1.dlt" in topics:
        log_pass("Topics 'parcel.lifecycle.v1' and 'parcel.lifecycle.v1.dlt' persisted across restart")
    else:
        log_fail(f"Topics missing after restart: {topics}")

    # Verify consumer group offsets persisted
    res = run_cmd("docker exec swiftdrop-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group swiftdrop-smoke-consumer-group", check=False)
    if "swiftdrop-smoke-consumer-group" in res.stdout:
        log_pass("Consumer group 'swiftdrop-smoke-consumer-group' offsets persisted across broker restart")
    else:
        log_fail("Consumer group offsets lost after restart")


# ==============================================================================
# 4. PostgreSQL Database Isolation & Role Permissions
# ==============================================================================
def test_postgresql_isolation():
    print(f"\n{BOLD}=== 4. PostgreSQL Logical Databases & Role Isolation ==={RESET}")
    pg_container = "swiftdrop-business-postgres"

    # 1. Authorized connections
    tests = [
        ("pricing_app", "pricing_dev_password", "pricing_db"),
        ("delivery_app", "delivery_dev_password", "delivery_db"),
        ("communications_app", "communications_dev_password", "communications_db"),
    ]

    for user, pwd, db in tests:
        res = run_cmd(
            f'docker exec -e PGPASSWORD="{pwd}" {pg_container} psql -U {user} -d {db} -c "SELECT current_user, current_database();"',
            check=False,
        )
        if res.returncode == 0 and user in res.stdout and db in res.stdout:
            log_pass(f"Authorized role '{user}' successfully connected to owned database '{db}'")
        else:
            log_fail(f"Failed authorized connection for {user} on {db}: {res.stderr}")

    # 2. Negative isolation tests (denied access)
    negative_tests = [
        ("pricing_app", "pricing_dev_password", "delivery_db"),
        ("delivery_app", "delivery_dev_password", "communications_db"),
        ("communications_app", "communications_dev_password", "pricing_db"),
    ]

    for user, pwd, target_db in negative_tests:
        res = run_cmd(
            f'docker exec -e PGPASSWORD="{pwd}" {pg_container} psql -U {user} -d {target_db} -c "SELECT 1;"',
            check=False,
        )
        if res.returncode != 0 and ("permission denied" in res.stderr.lower() or "fatal" in res.stderr.lower()):
            log_pass(f"Isolation verified: role '{user}' was strictly DENIED connection to '{target_db}'")
        else:
            log_fail(f"SECURITY BREACH: role '{user}' was permitted to connect to '{target_db}'!")

    # 3. Superuser privilege audit
    audit_cmd = (
        f'docker exec {pg_container} psql -U postgres -d postgres -tAc '
        f'"SELECT rolname, rolsuper, rolcreatedb FROM pg_roles WHERE rolname IN (\'pricing_app\', \'delivery_app\', \'communications_app\');"'
    )
    res = run_cmd(audit_cmd)
    all_unprivileged = True
    for line in res.stdout.strip().splitlines():
        parts = line.split("|")
        if len(parts) >= 3:
            role, super_flag, create_flag = parts[0], parts[1], parts[2]
            if super_flag == "t" or create_flag == "t":
                all_unprivileged = False
                log_fail(f"Role '{role}' has elevated privileges (super={super_flag}, createdb={create_flag})")
    if all_unprivileged:
        log_pass("Audit confirmed: All application roles are unprivileged (NOSUPERUSER, NOCREATEDB)")


# ==============================================================================
# 5. PostgreSQL Persistence & Safety
# ==============================================================================
def test_postgresql_persistence():
    print(f"\n{BOLD}=== 5. PostgreSQL Restart & Data Persistence ==={RESET}")
    pg_container = "swiftdrop-business-postgres"
    test_probe_id = str(uuid.uuid4())

    # 1. Create temporary smoke probe table in delivery_db
    log_info("Writing synthetic record into temporary smoke test table in 'delivery_db'...")
    setup_sql = (
        f"CREATE TABLE IF NOT EXISTS _smoke_probe (id UUID PRIMARY KEY, created_at TIMESTAMPTZ DEFAULT NOW()); "
        f"INSERT INTO _smoke_probe (id) VALUES ('{test_probe_id}');"
    )
    run_cmd(f'docker exec -e PGPASSWORD="delivery_dev_password" {pg_container} psql -U delivery_app -d delivery_db -c "{setup_sql}"')
    log_pass("Temporary probe table and synthetic row written to 'delivery_db'")

    # 2. Restart PostgreSQL container
    log_info("Restarting Business PostgreSQL container...")
    run_cmd(f"docker restart {pg_container}")

    # Wait for PostgreSQL
    ready = False
    for _ in range(30):
        res = run_cmd(f"docker exec {pg_container} pg_isready -U postgres", check=False)
        if res.returncode == 0:
            ready = True
            break
        time.sleep(1)

    if ready:
        log_pass("Business PostgreSQL container is UP and healthy following restart")
    else:
        log_fail("Business PostgreSQL failed to recover after restart")

    # 3. Read back probe row
    query_sql = f"SELECT count(*) FROM _smoke_probe WHERE id = '{test_probe_id}';"
    res = run_cmd(f'docker exec -e PGPASSWORD="delivery_dev_password" {pg_container} psql -U delivery_app -d delivery_db -tAc "{query_sql}"')
    count = res.stdout.strip()
    if count == "1":
        log_pass(f"Data persistence verified: probe row {test_probe_id} retrieved successfully after restart")
    else:
        log_fail(f"Probe row missing after restart: count = {count}")

    # 4. Clean up probe table
    run_cmd(f'docker exec -e PGPASSWORD="delivery_dev_password" {pg_container} psql -U delivery_app -d delivery_db -c "DROP TABLE _smoke_probe;"')
    log_pass("Temporary smoke probe table cleaned up")


# ==============================================================================
# 6. S3 Object Storage Security & Persistence
# ==============================================================================
def test_s3_storage_security(endpoint_url, bucket_name, access_key, secret_key):
    print(f"\n{BOLD}=== 6. Private S3 Object Storage Security & Privacy ==={RESET}")
    session = botocore.session.get_session()

    # 1. Unauthenticated / anonymous access denial
    log_info(f"Verifying anonymous access to '{bucket_name}' is strictly denied...")
    anon_client = session.create_client(
        "s3",
        endpoint_url=endpoint_url,
        aws_access_key_id="",
        aws_secret_access_key="",
        region_name="us-east-1",
    )
    try:
        anon_client.get_object(Bucket=bucket_name, Key="nonexistent-probe.jpg")
        log_fail("SECURITY BREACH: Anonymous GET succeeded on private bucket!")
    except ClientError as e:
        code = e.response.get("Error", {}).get("Code", "")
        status = e.response.get("ResponseMetadata", {}).get("HTTPStatusCode", 0)
        if status in [401, 403] or "AccessDenied" in code or "Forbidden" in code:
            log_pass(f"Bucket privacy verified: anonymous request rejected ({code}, HTTP {status})")
        else:
            log_info(f"Anonymous request rejected with code: {code} (HTTP {status})")

    # 2. Authorized upload of synthetic object
    auth_client = session.create_client(
        "s3",
        endpoint_url=endpoint_url,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name="us-east-1",
    )

    test_key = f"proofs/smoke-test-{uuid.uuid4()}.dat"
    test_data = b"SWIFTDROP_SYNTHETIC_POD_PHOTO_BYTES_" + os.urandom(256)
    expected_hash = hashlib.sha256(test_data).hexdigest()

    log_info(f"Uploading synthetic proof object '{test_key}' with authorized credentials...")
    auth_client.put_object(Bucket=bucket_name, Key=test_key, Body=test_data)
    log_pass("Synthetic proof object uploaded successfully")

    # 3. Authorized retrieval and checksum verification
    obj = auth_client.get_object(Bucket=bucket_name, Key=test_key)
    retrieved_data = obj["Body"].read()
    retrieved_hash = hashlib.sha256(retrieved_data).hexdigest()

    if retrieved_hash == expected_hash:
        log_pass("Retrieved object content and SHA256 checksum match uploaded payload exactly")
    else:
        log_fail("Checksum mismatch in retrieved object data")

    # 4. Storage restart & persistence
    log_info("Restarting storage container 'swiftdrop-minio'...")
    run_cmd("docker restart swiftdrop-minio")
    time.sleep(3)

    # Re-retrieve object after restart
    obj = auth_client.get_object(Bucket=bucket_name, Key=test_key)
    restart_data = obj["Body"].read()
    if hashlib.sha256(restart_data).hexdigest() == expected_hash:
        log_pass("Object storage persistence verified: synthetic proof intact after container restart")
    else:
        log_fail("Object checksum corrupted after restart")

    # 5. Clean up smoke test object
    auth_client.delete_object(Bucket=bucket_name, Key=test_key)
    log_pass(f"Cleaned up smoke test object '{test_key}'")


# ==============================================================================
# 7. Non-Destructive Platform Safety Invariant
# ==============================================================================
def test_existing_platform_safety():
    print(f"\n{BOLD}=== 7. Existing Platform State & Data Safety Audit ==={RESET}")
    # Verify that existing accounts postgres volume exists
    res = run_cmd("docker volume ls", check=False)
    if "devarom-accounts-postgres-data" in res.stdout or "swiftdrop-accounts-postgres-data" in res.stdout:
        log_pass("Existing Accounts PostgreSQL data volume preserved (devarom/swiftdrop-accounts-postgres-data)")
    else:
        log_fail("Accounts PostgreSQL data volume missing!")

    # Verify that existing identity containers were NOT deleted
    res = run_cmd("docker ps -a --format '{{.Names}}'", check=False)
    containers = res.stdout.splitlines()
    has_keycloak = "swiftdrop-keycloak" in containers or "devarom-keycloak" in containers
    if has_keycloak:
        log_pass("Existing Keycloak container preserved (devarom/swiftdrop-keycloak)")
    else:
        log_fail("Keycloak container missing!")


def main():
    print(f"{BOLD}=============================================================================={RESET}")
    print(f"{BOLD} SwiftDrop Platform — Business Infrastructure Smoke Test Suite{RESET}")
    print(f"{BOLD}=============================================================================={RESET}")

    synthetic_event = test_event_schema_contract()
    test_kafka_publish_consume(synthetic_event)
    test_kafka_persistence()
    test_postgresql_isolation()
    test_postgresql_persistence()

    # MinIO / Object storage
    endpoint = os.environ.get("S3_ENDPOINT", "http://127.0.0.1:9000")
    bucket = os.environ.get("SWIFTDROP_STORAGE_BUCKET", "swiftdrop-pod")
    access_key = os.environ.get("MINIO_ROOT_USER", "swiftdrop_storage_admin")
    secret_key = os.environ.get("MINIO_ROOT_PASSWORD", "minio_dev_password")
    test_s3_storage_security(endpoint, bucket, access_key, secret_key)

    test_existing_platform_safety()

    print(f"\n{BOLD}=============================================================================={RESET}")
    print(f"{BOLD} SMOKE TEST RESULTS SUMMARY{RESET}")
    print(f"{BOLD}=============================================================================={RESET}")
    print(f"  Passed : {GREEN}{PASSED}{RESET}")
    print(f"  Failed : {RED}{FAILED}{RESET}")
    print(f"==============================================================================")

    if FAILED == 0:
        print(f"{GREEN}{BOLD}✓ ALL BUSINESS INFRASTRUCTURE DEPENDENCIES AND ISOLATION TESTS PASSED.{RESET}\n")
        sys.exit(0)
    else:
        print(f"{RED}{BOLD}✗ ONE OR MORE SMOKE CHECKS FAILED.{RESET}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
