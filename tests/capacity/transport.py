"""Stdlib HTTP and Server-Sent Events client used by the capacity workloads (Issue #165)."""

from __future__ import annotations

import http.client
import json
import socket
import ssl
import time
from dataclasses import dataclass, field
from typing import Dict, Iterator, List, Optional
from urllib.parse import urlsplit


@dataclass
class HttpResult:
    status: int
    body: bytes
    elapsed_ms: float
    headers: Dict[str, str] = field(default_factory=dict)
    error: str = ""

    def json(self):
        return json.loads(self.body.decode("utf-8"))


def _connection(url: str, timeout: float):
    parts = urlsplit(url)
    if parts.scheme == "https":
        return http.client.HTTPSConnection(parts.hostname, parts.port or 443, timeout=timeout,
                                           context=ssl.create_default_context())
    if parts.scheme == "http":
        return http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    raise ValueError("unsupported URL scheme: %s" % parts.scheme)


def _path(url: str) -> str:
    parts = urlsplit(url)
    return (parts.path or "/") + ("?" + parts.query if parts.query else "")


def request(method: str, url: str, headers: Optional[Dict[str, str]] = None, body: Optional[bytes] = None,
            timeout: float = 10.0) -> HttpResult:
    """Single request on a fresh connection. Never raises for transport errors; returns status 0."""
    start = time.perf_counter()
    conn = _connection(url, timeout)
    try:
        conn.request(method, _path(url), body=body, headers=headers or {})
        resp = conn.getresponse()
        data = resp.read()
        return HttpResult(resp.status, data, (time.perf_counter() - start) * 1000.0,
                          {k.lower(): v for k, v in resp.getheaders()})
    except (socket.timeout, TimeoutError):
        return HttpResult(0, b"", (time.perf_counter() - start) * 1000.0, error="timeout")
    except (OSError, http.client.HTTPException) as exc:
        return HttpResult(0, b"", (time.perf_counter() - start) * 1000.0, error="transport:" + type(exc).__name__)
    finally:
        conn.close()


def graphql(url: str, query: str, token: Optional[str], timeout: float = 10.0,
            variables: Optional[Dict] = None) -> HttpResult:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    payload = {"query": query}
    if variables:
        payload["variables"] = variables
    return request("POST", url, headers, json.dumps(payload).encode("utf-8"), timeout)


@dataclass
class SseFrame:
    event: str = "message"
    data: str = ""
    id: Optional[str] = None
    comment: bool = False


class SseParser:
    """Incremental text/event-stream parser (WHATWG framing; comments surfaced as heartbeats)."""

    def __init__(self) -> None:
        self._buffer = ""
        self._event = "message"
        self._data: List[str] = []
        self._id: Optional[str] = None

    def feed(self, chunk: str) -> List[SseFrame]:
        self._buffer += chunk.replace("\r\n", "\n").replace("\r", "\n")
        frames: List[SseFrame] = []
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            if line == "":
                if self._data:
                    frames.append(SseFrame(self._event, "\n".join(self._data), self._id))
                self._event, self._data = "message", []
                continue
            if line.startswith(":"):
                frames.append(SseFrame(event="comment", data=line[1:].strip(), comment=True))
                continue
            name, _, value = line.partition(":")
            value = value[1:] if value.startswith(" ") else value
            if name == "event":
                self._event = value
            elif name == "data":
                self._data.append(value)
            elif name == "id":
                self._id = value
        return frames


class SseStream:
    """Blocking SSE connection. `frames()` yields frames until closed, deadline or server EOF."""

    def __init__(self, url: str, token: Optional[str], connect_timeout: float = 10.0, read_timeout: float = 45.0):
        self.url = url
        self.token = token
        self.connect_timeout = connect_timeout
        self.read_timeout = read_timeout
        self.status = 0
        self.connect_ms: Optional[float] = None
        self.error = ""
        self._conn = None
        self._resp = None
        self._closed = False

    def open(self) -> bool:
        headers = {"Accept": "text/event-stream", "Cache-Control": "no-cache"}
        if self.token is not None:
            headers["Authorization"] = "Bearer " + self.token
        start = time.perf_counter()
        try:
            self._conn = _connection(self.url, self.connect_timeout)
            self._conn.request("GET", _path(self.url), headers=headers)
            self._resp = self._conn.getresponse()
            self.status = self._resp.status
            self.connect_ms = (time.perf_counter() - start) * 1000.0
            if self.status != 200:
                self._resp.read()
                self.close()
                return False
            if self._conn.sock is not None:
                self._conn.sock.settimeout(self.read_timeout)
            return True
        except (OSError, http.client.HTTPException) as exc:
            self.error = "timeout" if isinstance(exc, (socket.timeout, TimeoutError)) else "transport:" + type(exc).__name__
            self.close()
            return False

    def frames(self, deadline: float) -> Iterator[SseFrame]:
        parser = SseParser()
        last_data = time.monotonic()
        while not self._closed and time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if self._conn is not None and self._conn.sock is not None:
                self._conn.sock.settimeout(max(0.05, min(self.read_timeout, remaining)))
            try:
                chunk = self._resp.read1(4096)
            except (socket.timeout, TimeoutError):
                if time.monotonic() - last_data >= self.read_timeout:
                    self.error = "read_timeout"
                    return
                continue
            except (OSError, http.client.HTTPException, AttributeError, ValueError):
                if not self._closed:
                    self.error = "stream_reset"
                return
            if not chunk:
                self.error = self.error or "server_eof"
                return
            last_data = time.monotonic()
            for frame in parser.feed(chunk.decode("utf-8", errors="replace")):
                yield frame

    def close(self) -> None:
        self._closed = True
        try:
            if self._conn is not None and self._conn.sock is not None:
                self._conn.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            if self._conn is not None:
                self._conn.close()
        except OSError:
            pass
