"""Application factory."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI

from divalhr_ai import __version__
from divalhr_ai.api.status import health_router, public_router
from divalhr_ai.config import Settings, get_settings
from divalhr_ai.correlation import CorrelationIdMiddleware
from divalhr_ai.cors import PathScopedCORSMiddleware
from divalhr_ai.logging import configure_logging

log = structlog.get_logger("divalhr_ai")

PUBLIC_STATUS_PATH = "/api/v1/system/status"


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the FastAPI application."""
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.ready = True
        log.info("startup_complete", environment=settings.environment, version=settings.version)
        yield
        app.state.ready = False
        log.info("shutdown")

    app = FastAPI(
        title="DivalHR AI Service",
        version=__version__,
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.openapi_enabled else None,
    )
    app.state.settings = settings
    app.state.ready = False
    app.include_router(public_router)
    app.include_router(health_router)
    # The browser may call only the public status endpoint: GET, explicit origins, no credentials.
    app.add_middleware(
        PathScopedCORSMiddleware,
        paths=[PUBLIC_STATUS_PATH],
        allow_origins=settings.cors_allowed_origins,
    )
    app.add_middleware(CorrelationIdMiddleware)
    return app
