"""Structured JSON logging with correlation IDs and defensive redaction."""

import logging
import sys
from collections.abc import MutableMapping
from typing import Any

import structlog

# Keys that must never appear in logs (docs/SECURITY.md). Matching is case-insensitive.
SENSITIVE_KEYS = frozenset(
    {
        "authorization",
        "cookie",
        "set-cookie",
        "password",
        "passwd",
        "secret",
        "token",
        "access_token",
        "refresh_token",
        "id_token",
        "jwt",
        "api_key",
        "email",
        "phone",
    }
)
REDACTED = "[REDACTED]"


def redact_sensitive(
    _logger: Any, _method: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    """Replace values of sensitive keys, recursively, before rendering."""

    def scrub(value: Any) -> Any:
        if isinstance(value, dict):
            return {
                key: REDACTED if str(key).lower() in SENSITIVE_KEYS else scrub(item)
                for key, item in value.items()
            }
        if isinstance(value, list | tuple):
            return [scrub(item) for item in value]
        return value

    for key in list(event_dict.keys()):
        if key.lower() in SENSITIVE_KEYS:
            event_dict[key] = REDACTED
        else:
            event_dict[key] = scrub(event_dict[key])
    return event_dict


def configure_logging(level: str, service: str = "ai-service") -> None:
    """Configure structlog and stdlib logging to emit one JSON object per line."""
    shared: list[Any] = [
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        redact_sensitive,
    ]
    structlog.configure(
        processors=[
            *shared,
            structlog.processors.format_exc_info,
            structlog.processors.EventRenamer("message"),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.getLevelName(level)),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=False,
    )
    structlog.contextvars.bind_contextvars(service=service)
    # Uvicorn's own access log would print raw request lines; ours replaces it.
    logging.getLogger("uvicorn.access").disabled = True
