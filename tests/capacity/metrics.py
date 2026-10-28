"""Latency percentiles, workload counters and backlog trend helpers (Issue #165)."""

from __future__ import annotations

import math
import threading
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple


def percentile(samples: Sequence[float], pct: float) -> Optional[float]:
    """Nearest-rank percentile; None when there are no samples (never a fabricated zero)."""
    if not samples:
        return None
    if not 0 < pct <= 100:
        raise ValueError("pct must be in (0, 100]")
    ordered = sorted(samples)
    rank = max(1, math.ceil(pct / 100.0 * len(ordered)))
    return round(ordered[rank - 1], 3)


def latency_summary(samples: Sequence[float]) -> Dict[str, Optional[float]]:
    return {
        "count": len(samples),
        "p50_ms": percentile(samples, 50),
        "p95_ms": percentile(samples, 95),
        "p99_ms": percentile(samples, 99),
        "max_ms": round(max(samples), 3) if samples else None,
        "mean_ms": round(sum(samples) / len(samples), 3) if samples else None,
    }


@dataclass
class WorkloadStats:
    """Thread-safe counters for one workload. Expected denials are tracked apart from errors."""
    name: str
    latencies_ms: List[float] = field(default_factory=list)
    total: int = 0
    succeeded: int = 0
    failed: int = 0
    timeouts: int = 0
    rate_limited: int = 0
    response_bytes: List[int] = field(default_factory=list)
    failure_kinds: Dict[str, int] = field(default_factory=dict)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def record(self, ok: bool, latency_ms: float, kind: str = "", size: int = 0) -> None:
        """Gateway 429s are counted separately and excluded from latency and error rate."""
        with self._lock:
            self.total += 1
            if kind != "rate_limited":
                self.latencies_ms.append(latency_ms)
                self.response_bytes.append(size)
            if ok:
                self.succeeded += 1
            else:
                self.failed += 1
                self.failure_kinds[kind or "unknown"] = self.failure_kinds.get(kind or "unknown", 0) + 1
                if kind == "timeout":
                    self.timeouts += 1
                if kind == "rate_limited":
                    self.rate_limited += 1

    def as_dict(self) -> Dict:
        with self._lock:
            counted = self.total - self.rate_limited
            return {
                "total_requests": self.total,
                "successful_requests": self.succeeded,
                "failed_requests": self.failed,
                "timeouts": self.timeouts,
                "rate_limited": self.rate_limited,
                "error_rate": round((self.failed - self.rate_limited) / counted, 6) if counted else None,
                "error_rate_including_rate_limited": round(self.failed / self.total, 6) if self.total else None,
                "failure_kinds": dict(sorted(self.failure_kinds.items())),
                "latency": latency_summary(self.latencies_ms),
                "response_bytes_max": max(self.response_bytes) if self.response_bytes else None,
                "response_bytes_p95": percentile(self.response_bytes, 95),
            }


def linear_slope_per_min(points: Sequence[Tuple[float, float]]) -> Optional[float]:
    """Least-squares slope of (seconds, value) samples, in value units per minute."""
    if len(points) < 2:
        return None
    n = len(points)
    mean_t = sum(t for t, _ in points) / n
    mean_v = sum(v for _, v in points) / n
    denom = sum((t - mean_t) ** 2 for t, _ in points)
    if denom == 0:
        return None
    slope = sum((t - mean_t) * (v - mean_v) for t, v in points) / denom
    return round(slope * 60.0, 6)


def parse_prometheus_text(text: str) -> Dict[str, float]:
    """Parse Prometheus exposition text into {'name{labels}': value}; sums are not computed here."""
    values: Dict[str, float] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            key, raw = line.rsplit(" ", 1)
            values[key] = float(raw)
        except ValueError:
            continue
    return values


def metric_sum(values: Dict[str, float], name: str) -> Optional[float]:
    """Sum every series of metric `name` across label sets; None if the metric is absent."""
    matched = [v for k, v in values.items() if k == name or k.startswith(name + "{")]
    return sum(matched) if matched else None
