"""CORS limited to explicitly listed paths.

Only the public status endpoint is browser-callable. Every other path (health probes and any
future operation) gets no CORS headers, so browsers cannot read it cross-origin.
"""

from collections.abc import Iterable

from starlette.middleware.cors import CORSMiddleware
from starlette.types import ASGIApp, Receive, Scope, Send


class PathScopedCORSMiddleware:
    """Applies Starlette's CORS handling only to the given exact paths."""

    def __init__(self, app: ASGIApp, paths: Iterable[str], allow_origins: list[str]) -> None:
        self.app = app
        self.paths = frozenset(paths)
        self.cors = CORSMiddleware(
            app,
            allow_origins=allow_origins,
            allow_methods=["GET"],
            allow_headers=["X-Correlation-Id"],
            expose_headers=["X-Correlation-Id"],
            allow_credentials=False,
            max_age=600,
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and scope["path"] in self.paths:
            await self.cors(scope, receive, send)
        else:
            await self.app(scope, receive, send)
