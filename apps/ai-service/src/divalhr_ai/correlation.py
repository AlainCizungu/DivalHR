"""Correlation ID handling (same rules as the Core API)."""

import re
import time
import uuid
from collections.abc import Awaitable, Callable

import structlog
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

HEADER = "X-Correlation-Id"
_ALLOWED = re.compile(r"^[A-Za-z0-9._-]{8,64}$")

log = structlog.get_logger("divalhr_ai.http")


def resolve(candidate: str | None) -> str:
    """Accept a caller-supplied ID only if it is safe to echo and log."""
    if candidate is not None:
        candidate = candidate.strip()
        if _ALLOWED.fullmatch(candidate):
            return candidate
    return str(uuid.uuid4())


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    """Binds the correlation ID to logs, echoes it, and writes one access log line per request.

    The access line records method, route path, status and duration only: never headers, query
    strings or bodies.
    """

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        correlation_id = resolve(request.headers.get(HEADER))
        request.state.correlation_id = correlation_id
        structlog.contextvars.bind_contextvars(correlation_id=correlation_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        finally:
            structlog.contextvars.unbind_contextvars("correlation_id")
        response.headers[HEADER] = correlation_id
        log.info(
            "http_request",
            method=request.method,
            path=request.url.path,
            status=response.status_code,
            duration_ms=round((time.perf_counter() - started) * 1000, 1),
            correlation_id=correlation_id,
        )
        return response
