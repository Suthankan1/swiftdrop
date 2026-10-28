"""
Target safety, secret scrubbing and public-tracking redaction checks (Issue #165).

Load is only ever sent to explicitly authorized non-production targets: loopback and
*.localhost / *.local / *.internal hosts by default; anything else must be passed with
--allow-host and the operator must confirm --confirm-non-production. Hostnames that look
like production or well-known third-party APIs are always refused.
"""

from __future__ import annotations

import ipaddress
import re
from typing import Any, Iterable, List, Optional, Sequence
from urllib.parse import urlsplit


class UnsafeTargetError(ValueError):
    """Raised when a target URL is not an authorized non-production endpoint."""


DEFAULT_ALLOWED_SUFFIXES = (".localhost", ".local", ".internal", ".svc.cluster.local")
DEFAULT_ALLOWED_HOSTS = ("localhost",)
FORBIDDEN_HOST_PATTERN = re.compile(r"(^|[.-])(prod|production|live)([.-]|$)", re.IGNORECASE)
THIRD_PARTY_SUFFIXES = ("google.com", "googleapis.com", "github.com", "githubusercontent.com",
                        "amazonaws.com", "azure.com", "cloudflare.com", "keycloak.org")


def assert_safe_target(url: str, extra_allowed_hosts: Sequence[str] = (), confirmed_non_production: bool = False) -> str:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise UnsafeTargetError("target must be an absolute http(s) URL: %r" % url)
    host = parts.hostname.lower()
    if FORBIDDEN_HOST_PATTERN.search(host):
        raise UnsafeTargetError("refusing production-like host %s" % host)
    if any(host == s or host.endswith("." + s) for s in THIRD_PARTY_SUFFIXES):
        raise UnsafeTargetError("refusing third-party host %s" % host)
    if _is_loopback(host) or host in DEFAULT_ALLOWED_HOSTS or host.endswith(DEFAULT_ALLOWED_SUFFIXES):
        return host
    if host in {h.lower() for h in extra_allowed_hosts}:
        if not confirmed_non_production:
            raise UnsafeTargetError("host %s requires --confirm-non-production" % host)
        return host
    raise UnsafeTargetError("host %s is not an authorized non-production target (use --allow-host)" % host)


def _is_loopback(host: str) -> bool:
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def assert_safe_environment_id(environment_id: str) -> None:
    if not environment_id or FORBIDDEN_HOST_PATTERN.search(environment_id):
        raise UnsafeTargetError("environment id %r is empty or production-like" % environment_id)


# ---- secret scrubbing ----------------------------------------------------------------------

JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}")
BEARER_PATTERN = re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}")


def scrub(text: str, secrets: Iterable[str] = ()) -> str:
    for value in secrets:
        if value:
            text = text.replace(value, "[REDACTED]")
    text = JWT_PATTERN.sub("[REDACTED_JWT]", text)
    return BEARER_PATTERN.sub("Bearer [REDACTED]", text)


def find_secret_leaks(text: str, secrets: Iterable[str] = ()) -> List[str]:
    leaks = [s[:6] + "..." for s in secrets if s and s in text]
    if JWT_PATTERN.search(text):
        leaks.append("jwt-shaped-value")
    if BEARER_PATTERN.search(text):
        leaks.append("bearer-header")
    return leaks


# ---- public tracking redaction -------------------------------------------------------------

PUBLIC_TRACKING_KEYS = {"trackingNumber", "currentStatus", "originCity", "destinationCity", "timeline"}
PUBLIC_TIMELINE_KEYS = {"status", "occurredAt", "cityOrZone"}
PUBLIC_SSE_KEYS = {"eventId", "trackingNumber", "aggregateVersion", "oldStatus", "newStatus", "cityOrZone", "occurredAt"}
FORBIDDEN_KEY_FRAGMENTS = ("name", "phone", "email", "address", "sub", "courier", "merchant", "proof",
                           "note", "reason", "parcelid", "token", "customer", "recipient")
EMAIL_PATTERN = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PHONE_PATTERN = re.compile(r"(?<![\w-])\+?\d[\d ()-]{8,}\d(?![\w-])")
UUID_PATTERN = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


def public_tracking_violations(payload: Any, private_values: Iterable[str] = ()) -> List[str]:
    """Return redaction violations for a PublicTrackingResponse (empty list when compliant)."""
    violations: List[str] = []
    if not isinstance(payload, dict):
        return ["payload is not a JSON object"]
    extra = set(payload) - PUBLIC_TRACKING_KEYS
    violations += ["unexpected key %s" % k for k in sorted(extra)]
    for item in payload.get("timeline") or []:
        if not isinstance(item, dict):
            violations.append("timeline item is not an object")
            continue
        violations += ["unexpected timeline key %s" % k for k in sorted(set(item) - PUBLIC_TIMELINE_KEYS)]
    violations += _value_violations(payload, private_values)
    return violations


def public_sse_event_violations(payload: Any, private_values: Iterable[str] = ()) -> List[str]:
    if not isinstance(payload, dict):
        return ["event data is not a JSON object"]
    violations = ["unexpected key %s" % k for k in sorted(set(payload) - PUBLIC_SSE_KEYS)]
    return violations + _value_violations({k: v for k, v in payload.items() if k != "eventId"}, private_values)


def _value_violations(payload: Any, private_values: Iterable[str]) -> List[str]:
    violations = []
    for key in _walk_keys(payload):
        lowered = key.lower()
        if lowered not in ("trackingnumber",) and any(f in lowered for f in FORBIDDEN_KEY_FRAGMENTS):
            violations.append("sensitive key %s" % key)
    for text in _walk_strings(payload):
        if EMAIL_PATTERN.search(text):
            violations.append("email-like value")
        if PHONE_PATTERN.search(text):
            violations.append("phone-like value")
        if UUID_PATTERN.search(text):
            violations.append("internal identifier (uuid) value")
        for private in private_values:
            if private and private in text:
                violations.append("private fixture value disclosed")
    return sorted(set(violations))


def _walk_keys(node: Any) -> Iterable[str]:
    if isinstance(node, dict):
        for k, v in node.items():
            yield str(k)
            yield from _walk_keys(v)
    elif isinstance(node, list):
        for v in node:
            yield from _walk_keys(v)


def _walk_strings(node: Any) -> Iterable[str]:
    if isinstance(node, dict):
        for v in node.values():
            yield from _walk_strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from _walk_strings(v)
    elif isinstance(node, str):
        yield node


def first_line(text: Optional[str], limit: int = 160) -> str:
    return (text or "").splitlines()[0][:limit] if text else ""
