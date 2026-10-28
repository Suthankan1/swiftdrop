"""
Versioned capacity-budget loading and verdict evaluation (Issue #165 / N10-QA-08).

Fail-closed rules:
- A PENDING_APPROVAL budget is never reported as PASS, whatever the measurement.
- illustrative_value is never compared against.
- Acceptance mode requires approval.status == APPROVED and a pinned digest equal to the
  digest of the current budgets+invariants, so thresholds cannot be edited after approval.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

DEFAULT_BUDGET_FILE = Path(__file__).resolve().parent / "budgets" / "capacity-budgets.v1.json"

APPROVED = "APPROVED"
PENDING = "PENDING_APPROVAL"

# Verdicts per budget/invariant and overall.
PASS = "PASS"
FAIL = "FAIL"
NOT_MEASURED = "NOT_MEASURED"
PENDING_VERDICT = "PENDING_APPROVAL"

OVERALL_PASS = "PASS"
OVERALL_FAIL = "FAIL"
OVERALL_BLOCKED_APPROVAL = "BLOCKED_PENDING_APPROVAL"
OVERALL_BLOCKED_EVIDENCE = "BLOCKED_INCOMPLETE_EVIDENCE"
OVERALL_NOT_ACCEPTANCE = "NOT_ACCEPTANCE_EVIDENCE"


class BudgetError(ValueError):
    """Raised when the budget file is malformed or violates the approval policy."""


@dataclass
class BudgetSet:
    raw: Dict[str, Any]
    path: Path

    @property
    def budgets(self) -> Dict[str, Dict[str, Any]]:
        return self.raw["budgets"]

    @property
    def invariants(self) -> Dict[str, Dict[str, Any]]:
        return self.raw["invariants"]

    @property
    def approval(self) -> Dict[str, Any]:
        return self.raw["approval"]

    def digest(self) -> str:
        return budget_digest(self.raw)

    def pending_ids(self) -> List[str]:
        return sorted(k for k, b in self.budgets.items() if b["status"] != APPROVED)

    def approved_value(self, budget_id: str) -> Optional[float]:
        b = self.budgets[budget_id]
        return b["value"] if b["status"] == APPROVED else None

    def acceptance_blockers(self) -> List[str]:
        """Reasons the acceptance run must not start. Empty list means acceptance may run."""
        reasons = []
        if self.approval.get("status") != APPROVED:
            reasons.append("approval.status is %s" % self.approval.get("status"))
        if not self.approval.get("approved_by"):
            reasons.append("approval.approved_by is empty")
        pinned = self.approval.get("approved_budget_digest")
        if pinned != self.digest():
            reasons.append("approval.approved_budget_digest does not match current budgets (%s)" % self.digest())
        for budget_id in self.pending_ids():
            reasons.append("budget %s is %s" % (budget_id, self.budgets[budget_id]["status"]))
        return reasons


def budget_digest(raw: Dict[str, Any]) -> str:
    """SHA-256 over the canonical budgets+invariants sections (approval block excluded)."""
    canonical = json.dumps({"budgets": raw["budgets"], "invariants": raw["invariants"]},
                           sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def load_budgets(path: Optional[Path] = None) -> BudgetSet:
    path = Path(path or DEFAULT_BUDGET_FILE)
    raw = json.loads(path.read_text(encoding="utf-8"))
    validate_budget_document(raw)
    return BudgetSet(raw=raw, path=path)


def validate_budget_document(raw: Dict[str, Any]) -> None:
    for key in ("schema_version", "budget_set_id", "revision", "approval", "budgets", "invariants"):
        if key not in raw:
            raise BudgetError("missing top-level key: %s" % key)
    if raw["schema_version"] != 1:
        raise BudgetError("unsupported schema_version %r" % raw["schema_version"])
    for budget_id, b in raw["budgets"].items():
        for key in ("kind", "comparator", "unit", "metric", "status", "value", "source"):
            if key not in b:
                raise BudgetError("budget %s missing %s" % (budget_id, key))
        if b["comparator"] not in ("max", "min"):
            raise BudgetError("budget %s has unsupported comparator %r" % (budget_id, b["comparator"]))
        if b["status"] == APPROVED:
            if not isinstance(b["value"], (int, float)) or isinstance(b["value"], bool):
                raise BudgetError("APPROVED budget %s must have a numeric value" % budget_id)
            if not str(b["source"]).strip():
                raise BudgetError("APPROVED budget %s must cite its source" % budget_id)
        elif b["status"] == PENDING:
            if b["value"] is not None:
                raise BudgetError("PENDING_APPROVAL budget %s must not carry an authoritative value" % budget_id)
        else:
            raise BudgetError("budget %s has unknown status %r" % (budget_id, b["status"]))
    for inv_id, inv in raw["invariants"].items():
        for key in ("metric", "expected", "severity", "source"):
            if key not in inv:
                raise BudgetError("invariant %s missing %s" % (inv_id, key))
    if raw["approval"].get("status") == APPROVED:
        pending = [k for k, b in raw["budgets"].items() if b["status"] != APPROVED]
        if pending:
            raise BudgetError("approval.status APPROVED while budgets still pending: %s" % ", ".join(pending))


def lookup_metric(measurements: Dict[str, Any], dotted: str) -> Optional[float]:
    node: Any = measurements
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    if isinstance(node, bool) or not isinstance(node, (int, float)):
        return None
    return node


@dataclass
class BudgetVerdict:
    budget_id: str
    status: str
    comparator: str
    threshold: Optional[float]
    measured: Optional[float]
    verdict: str
    unit: str
    source: str
    severity: str = ""

    def as_dict(self) -> Dict[str, Any]:
        return dict(self.__dict__)


@dataclass
class Evaluation:
    verdicts: List[BudgetVerdict] = field(default_factory=list)
    overall: str = OVERALL_BLOCKED_EVIDENCE
    reasons: List[str] = field(default_factory=list)

    def as_dict(self) -> Dict[str, Any]:
        return {"overall": self.overall, "reasons": self.reasons,
                "verdicts": [v.as_dict() for v in self.verdicts]}


def _compare(comparator: str, measured: float, threshold: float) -> bool:
    return measured <= threshold if comparator == "max" else measured >= threshold


def evaluate(budgets: BudgetSet, measurements: Dict[str, Any], evidence_is_live: bool,
             acceptance_mode: bool) -> Evaluation:
    """Compare measurements against budgets and invariants and derive the overall verdict."""
    result = Evaluation()
    for budget_id, b in sorted(budgets.budgets.items()):
        measured = lookup_metric(measurements, b["metric"])
        if measured is None:
            verdict = NOT_MEASURED
        elif b["status"] != APPROVED:
            verdict = PENDING_VERDICT
        else:
            verdict = PASS if _compare(b["comparator"], measured, b["value"]) else FAIL
        result.verdicts.append(BudgetVerdict(budget_id, b["status"], b["comparator"], b["value"],
                                             measured, verdict, b["unit"], b["source"]))
    for inv_id, inv in sorted(budgets.invariants.items()):
        measured = lookup_metric(measurements, inv["metric"])
        if measured is None:
            verdict = NOT_MEASURED
        else:
            verdict = PASS if measured == inv["expected"] else FAIL
        result.verdicts.append(BudgetVerdict(inv_id, APPROVED, "eq", inv["expected"], measured, verdict,
                                             "count", inv["source"], inv["severity"]))

    failures = [v for v in result.verdicts if v.verdict == FAIL]
    not_measured = [v for v in result.verdicts if v.verdict == NOT_MEASURED]
    pending = [v for v in result.verdicts if v.status != APPROVED]

    if failures:
        result.overall = OVERALL_FAIL
        result.reasons += ["%s measured %s violates %s %s" % (v.budget_id, v.measured, v.comparator, v.threshold)
                           for v in failures]
    elif not evidence_is_live:
        result.overall = OVERALL_NOT_ACCEPTANCE
        result.reasons.append("measurements are not from an integrated non-production environment")
    elif pending or not acceptance_mode:
        result.overall = OVERALL_BLOCKED_APPROVAL
        result.reasons += ["%s is PENDING_APPROVAL" % v.budget_id for v in pending]
        if not acceptance_mode:
            result.reasons.append("run was not executed in acceptance mode")
    elif not_measured:
        result.overall = OVERALL_BLOCKED_EVIDENCE
        result.reasons += ["%s was not measured" % v.budget_id for v in not_measured]
    else:
        result.overall = OVERALL_PASS
    if failures and not evidence_is_live:
        result.reasons.append("failure detected in non-live evidence; investigate harness or fixture")
    return result
