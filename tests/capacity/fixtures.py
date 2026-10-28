"""
Deterministic synthetic capacity dataset and environment binding (Issue #165 / N10-QA-08).

The dataset is a *seed manifest*: synthetic actor aliases, tenants and >= 100 parcels with
expected visibility. Seeding a real non-production environment (Keycloak users, bookings,
status progression) is an operator step; the operator then supplies a binding file that maps
aliases to the server-generated parcel ids / tracking numbers and to token *files*.

No real personal data: actors have no names, emails or phone numbers; cities are coarse.
Token values are read from files at run time and never written to reports.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

DEFAULT_SEED = "swiftdrop-n10-qa-08-v1"
FIXTURE_NAMESPACE = uuid.UUID("6f1d2c1e-6a51-4c39-9c0e-165165165165")
STATUSES = ("CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED")
TERMINAL = ("DELIVERED", "FAILED")
ZONES = ("CMB-01", "CMB-02", "GMP-01")
ZONE_CITY = {"CMB-01": "Colombo", "CMB-02": "Dehiwala", "GMP-01": "Gampaha"}
# Delivery SseProperties defaults and Gateway SSE policy defaults (subscriber planning only).
SSE_MAX_PER_USER = 10
SSE_MAX_PER_PARCEL = 5


@dataclass(frozen=True)
class Actor:
    alias: str
    role: str
    subject: str
    tenant: Optional[str] = None
    zone: Optional[str] = None
    approved: bool = True


@dataclass(frozen=True)
class Parcel:
    alias: str
    parcel_id: str
    tracking_number: str
    owner_kind: str
    owner_alias: str
    merchant_alias: Optional[str]
    status: str
    version: int
    pickup_zone: str
    dropoff_zone: str
    courier_alias: Optional[str]
    origin_city: str
    destination_city: str


@dataclass
class Dataset:
    seed: str
    actors: Dict[str, Actor]
    parcels: List[Parcel]
    fixture_digest: str = field(default="")

    # ---- expected visibility ------------------------------------------------------------
    def visible_to(self, actor_alias: str) -> List[Parcel]:
        actor = self.actors[actor_alias]
        if actor.role == "CUSTOMER":
            return [p for p in self.parcels if p.owner_kind == "customer" and p.owner_alias == actor.alias]
        if actor.role == "MERCHANT":
            if not actor.approved:
                return []
            return [p for p in self.parcels if p.merchant_alias == actor.alias]
        if actor.role == "DISPATCHER":
            return [p for p in self.unassigned() if actor.zone in (p.pickup_zone, p.dropoff_zone)]
        if actor.role == "ADMIN":
            return self.unassigned()
        return []

    def unassigned(self) -> List[Parcel]:
        return [p for p in self.parcels if p.status == "CREATED" and p.courier_alias is None]

    def can_stream(self, actor_alias: str, parcel: Parcel) -> bool:
        actor = self.actors[actor_alias]
        if actor.role in ("ADMIN", "DISPATCHER"):
            return True
        if actor.role == "COURIER":
            return parcel.courier_alias == actor.alias
        return parcel in self.visible_to(actor_alias)

    def merchant_tenants(self) -> List[str]:
        return sorted(a.alias for a in self.actors.values() if a.role == "MERCHANT" and a.approved)

    def by_role(self, role: str, approved_only: bool = True) -> List[Actor]:
        return [a for a in self.actors.values() if a.role == role and (a.approved or not approved_only)]

    def expected_pages(self, actor_alias: str, size: int) -> int:
        count = len(self.visible_to(actor_alias))
        return 0 if count == 0 else -(-count // size)

    # ---- serialization ------------------------------------------------------------------
    def manifest(self) -> Dict:
        return {
            "seed": self.seed,
            "fixture_digest": self.fixture_digest,
            "counts": self.counts(),
            "actors": {k: asdict(v) for k, v in sorted(self.actors.items())},
            "parcels": [asdict(p) for p in self.parcels],
        }

    def counts(self) -> Dict[str, int]:
        status_counts = {s: sum(1 for p in self.parcels if p.status == s) for s in STATUSES}
        return {
            "parcel_count": len(self.parcels),
            "merchant_tenant_count": len(self.merchant_tenants()),
            "customer_count": len(self.by_role("CUSTOMER")),
            "dispatcher_count": len(self.by_role("DISPATCHER")),
            "courier_count": len(self.by_role("COURIER")),
            "unassigned_count": len(self.unassigned()),
            **{"status_" + s.lower(): n for s, n in status_counts.items()},
        }


def _tracking_suffix(seed: str, index: int) -> str:
    alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    value = int(hashlib.sha256(("%s:tn:%d" % (seed, index)).encode()).hexdigest(), 16)
    chars = []
    for _ in range(6):
        value, rem = divmod(value, 36)
        chars.append(alphabet[rem])
    return "".join(chars)


def build_dataset(parcel_count: int = 120, seed: str = DEFAULT_SEED) -> Dataset:
    if parcel_count < 100:
        raise ValueError("Issue #165 requires at least 100 parcels; got %d" % parcel_count)
    actors: Dict[str, Actor] = {}

    def add(alias: str, role: str, **kw) -> None:
        actors[alias] = Actor(alias=alias, role=role, subject="capacity-%s" % alias, **kw)

    for i in range(1, 6):          # customer-05 owns nothing: empty-result boundary case
        add("customer-%02d" % i, "CUSTOMER")
    for tenant in ("merchant-a", "merchant-b", "merchant-c"):
        add(tenant, "MERCHANT", tenant=tenant)
    add("merchant-pending", "MERCHANT", tenant="merchant-pending", approved=False)
    add("dispatcher-cmb01", "DISPATCHER", zone="CMB-01")
    add("dispatcher-gmp01", "DISPATCHER", zone="GMP-01")
    add("admin-01", "ADMIN")
    for i in range(1, 5):
        add("courier-%02d" % i, "COURIER", zone=ZONES[(i - 1) % len(ZONES)])

    owners = ["customer-01", "customer-02", "customer-03", "customer-04", "merchant-a", "merchant-b", "merchant-c"]
    couriers = ["courier-01", "courier-02", "courier-03", "courier-04"]
    parcels: List[Parcel] = []
    for i in range(parcel_count):
        owner = owners[i % len(owners)]
        status = STATUSES[(i // len(owners) + i) % len(STATUSES)]
        pickup = ZONES[i % len(ZONES)]
        dropoff = ZONES[(i // 3) % len(ZONES)]
        is_merchant = owner.startswith("merchant")
        parcels.append(Parcel(
            alias="P%03d" % (i + 1),
            parcel_id=str(uuid.uuid5(FIXTURE_NAMESPACE, "%s:%d" % (seed, i))),
            tracking_number="SWD-20261106-%s" % _tracking_suffix(seed, i),
            owner_kind="merchant" if is_merchant else "customer",
            owner_alias=owner,
            merchant_alias=owner if is_merchant else None,
            status=status,
            version=STATUSES.index(status) + 1,
            pickup_zone=pickup,
            dropoff_zone=dropoff,
            courier_alias=None if status == "CREATED" else couriers[i % len(couriers)],
            origin_city=ZONE_CITY[pickup],
            destination_city=ZONE_CITY[dropoff],
        ))
    dataset = Dataset(seed=seed, actors=actors, parcels=parcels)
    digest_source = json.dumps({"actors": {k: asdict(v) for k, v in sorted(actors.items())},
                                "parcels": [asdict(p) for p in parcels]}, sort_keys=True)
    dataset.fixture_digest = hashlib.sha256(digest_source.encode()).hexdigest()
    return dataset


def plan_sse_subscribers(dataset: Dataset, connections: int,
                         max_per_user: int = SSE_MAX_PER_USER,
                         max_per_parcel: int = SSE_MAX_PER_PARCEL) -> List[Tuple[str, Parcel]]:
    """Spread SSE subscribers over owning actors and non-terminal parcels within server caps."""
    if connections < 1:
        raise ValueError("connections must be positive")
    subscribers = [a.alias for a in dataset.actors.values()
                   if a.role in ("CUSTOMER", "MERCHANT") and a.approved and dataset.visible_to(a.alias)]
    subscribers.sort()
    per_user: Dict[str, int] = {}
    per_parcel: Dict[str, int] = {}
    cursors: Dict[str, int] = {}
    plan: List[Tuple[str, Parcel]] = []
    turn = idle = 0
    while len(plan) < connections:
        actor = subscribers[turn % len(subscribers)]
        turn += 1
        placed = False
        if per_user.get(actor, 0) < max_per_user:
            owned = [p for p in dataset.visible_to(actor) if p.status not in TERMINAL] or dataset.visible_to(actor)
            cursor = cursors.get(actor, 0)
            for offset in range(len(owned)):
                candidate = owned[(cursor + offset) % len(owned)]
                if per_parcel.get(candidate.alias, 0) < max_per_parcel:
                    cursors[actor] = cursor + offset + 1
                    per_user[actor] = per_user.get(actor, 0) + 1
                    per_parcel[candidate.alias] = per_parcel.get(candidate.alias, 0) + 1
                    plan.append((actor, candidate))
                    placed = True
                    break
        idle = 0 if placed else idle + 1
        if idle > len(subscribers):
            raise ValueError("cannot place %d SSE subscribers within per-user/per-parcel caps" % connections)
    return plan


# ---- environment binding ------------------------------------------------------------------

class BindingError(ValueError):
    """Raised when the operator-supplied binding does not cover the required fixture."""


@dataclass
class BoundParcel:
    alias: str
    parcel_id: str
    tracking_number: str


@dataclass
class Binding:
    environment_id: str
    actors_token_files: Dict[str, str]
    negative_token_files: Dict[str, str]
    parcels: Dict[str, BoundParcel]
    status_drivers: List[Dict[str, str]]
    unknown_tracking_number: str

    def token(self, actor_alias: str) -> str:
        return _read_token(self.actors_token_files[actor_alias])

    def negative_token(self, kind: str) -> Optional[str]:
        path = self.negative_token_files.get(kind)
        return _read_token(path) if path else None

    def tracking_numbers_for(self, aliases: Iterable[str]) -> set:
        return {self.parcels[a].tracking_number for a in aliases if a in self.parcels}


def _read_token(path: str) -> str:
    value = Path(path).read_text(encoding="utf-8").strip()
    if not value or any(c.isspace() for c in value):
        raise BindingError("token file %s is empty or malformed" % Path(path).name)
    return value


def load_binding(path: Path, dataset: Dataset) -> Binding:
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise BindingError("binding %s is unreadable: %s" % (Path(path).name, type(exc).__name__)) from exc
    env = str(raw.get("environment_id", "")).strip()
    if not env:
        raise BindingError("binding.environment_id is required")
    if raw.get("fixture_digest") != dataset.fixture_digest:
        raise BindingError("binding was produced for a different fixture digest")
    repo_tests = Path(__file__).resolve().parent.parent
    token_files = dict(raw.get("actors", {}))
    token_files = {k: (v["token_file"] if isinstance(v, dict) else v) for k, v in token_files.items()}
    for alias, token_path in list(token_files.items()) + list(raw.get("negative_tokens", {}).items()):
        if repo_tests in Path(token_path).resolve().parents:
            raise BindingError("token file for %s must live outside the repository tests tree" % alias)
    missing_actors = sorted(a for a in dataset.actors if dataset.actors[a].role != "COURIER" and a not in token_files)
    if missing_actors:
        raise BindingError("binding lacks tokens for actors: %s" % ", ".join(missing_actors))
    parcels = {alias: BoundParcel(alias, v["parcel_id"], v["tracking_number"])
               for alias, v in raw.get("parcels", {}).items()}
    missing = [p.alias for p in dataset.parcels if p.alias not in parcels]
    if missing:
        raise BindingError("binding lacks %d fixture parcels (first: %s)" % (len(missing), missing[0]))
    status_drivers = raw.get("status_drivers", [])
    if not isinstance(status_drivers, list):
        raise BindingError("binding.status_drivers must be a list")
    fixture_parcels = {p.alias: p for p in dataset.parcels}
    seen_driver_parcels = set()
    for driver in status_drivers:
        if not isinstance(driver, dict) or not driver.get("parcel") or not driver.get("actor"):
            raise BindingError("each status driver must name a parcel and courier actor")
        parcel_alias, actor_alias = driver["parcel"], driver["actor"]
        if not isinstance(parcel_alias, str) or not isinstance(actor_alias, str):
            raise BindingError("status driver parcel and actor aliases must be strings")
        if parcel_alias not in fixture_parcels or actor_alias not in dataset.actors:
            raise BindingError("status driver references an unknown fixture parcel or actor")
        fixture = fixture_parcels[parcel_alias]
        actor = dataset.actors[actor_alias]
        if actor.role != "COURIER" or actor_alias not in token_files:
            raise BindingError("status driver %s must have a bound courier token" % parcel_alias)
        if fixture.status not in ("PICKED_UP", "IN_TRANSIT") or fixture.courier_alias != actor_alias:
            raise BindingError("status driver %s must be an assigned courier's PICKED_UP or IN_TRANSIT parcel"
                               % parcel_alias)
        if parcel_alias in seen_driver_parcels:
            raise BindingError("status driver parcel %s is listed more than once" % parcel_alias)
        seen_driver_parcels.add(parcel_alias)
    return Binding(environment_id=env, actors_token_files=token_files,
                   negative_token_files=dict(raw.get("negative_tokens", {})), parcels=parcels,
                   status_drivers=status_drivers,
                   unknown_tracking_number=raw.get("unknown_tracking_number", "SWD-20261106-ZZZZZZ"))


def write_manifest(dataset: Dataset, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(dataset.manifest(), indent=2), encoding="utf-8")
