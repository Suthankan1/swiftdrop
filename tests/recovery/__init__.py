"""
SwiftDrop Application Recovery Consistency Verification Suite (Issue #168 / N10-P09A)
Provides deterministic pre/post rollback and restore application consistency assertions,
durable job verification, point-in-time boundary auditing, and fail-closed safety checks.
"""

from .models import (
    ApplicationSnapshot,
    DomainCheckResult,
    InvariantResult,
    RecoveryMetadata,
    VerificationReport,
    VerificationStatus,
)
from .verifier import RecoveryConsistencyVerifier

__all__ = [
    "ApplicationSnapshot",
    "DomainCheckResult",
    "InvariantResult",
    "RecoveryConsistencyVerifier",
    "RecoveryMetadata",
    "VerificationReport",
    "VerificationStatus",
]
