# SwiftDrop Root Infrastructure Module — Plan-Only Foundation
# Canonical Architecture Reference: docs/architecture/adr/ADR-0011-strict-zero-cost-hosting-and-azure-allowlist.md
# Policy Contract: platform/security/azure-allowlist-policy.yaml
#
# INVARIANT: Strict $0 hosting ceiling.
# Default target is local k3d/K3s on existing Mac hardware (arm64).
# No Azure cloud resources are declared or enabled in this baseline.
