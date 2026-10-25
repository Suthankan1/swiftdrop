# SwiftDrop Local Kubernetes Cluster & Storage Test Suite

This test suite provides automated, deterministic validation of the local k3d/K3s Kubernetes cluster configuration, script safety, and persistent storage contracts for the SwiftDrop project.

---

## Test Organization

| Test File | Description | Acceptance Criteria Covered |
|---|---|---|
| `test_cluster_config.py` | Validates `k3d.io/v1alpha5` Simple schema, pinned K3s `v1.31.2` image, single server node topology, loopback-only API (`127.0.0.1:16443`), loopback-only ingress (`127.0.0.1:18080`, `18443`), no automatic context switching, management namespaces, and strict $0 policy alignment. | Issue #175 AC1, AC2 |
| `test_cluster_safety.py` | Validates that all lifecycle scripts exist, have executable permissions, enforce `set -euo pipefail`, prohibit destructive `docker system prune` and `docker volume prune`, contain no hardcoded secrets or cloud commands, explicitly target context `k3d-swiftdrop-local`, and never auto-delete clusters. | Issue #175 AC2 |
| `test_storage_contract.py` | Validates domain database ownership boundaries (5 distinct PostgreSQL databases), Kafka KRaft broker persistence, MinIO object storage, storage path isolation outside git working tree, non-world-writable permissions, and persistence lifecycle guarantees across restarts. | Issue #175 AC3 |

---

## Running the Tests

### Option 1: pytest
```bash
python3 -m pytest tests/kubernetes -v
```

### Option 2: Python unittest discovery
```bash
python3 -m unittest discover -s tests/kubernetes -p "test_*.py" -v
```
