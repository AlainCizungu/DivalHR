"""Application factory."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI

from divalhr_ai import __version__
from divalhr_ai.api.status import health_router, public_router
from divalhr_ai.api.summary import router as summary_router
from divalhr_ai.auth import ServiceAuthMiddleware, ServiceTokenVerifier, build_verifier
from divalhr_ai.config import Settings, get_settings
from divalhr_ai.correlation import CorrelationIdMiddleware
from divalhr_ai.cors import PathScopedCORSMiddleware
from divalhr_ai.logging import configure_logging
from divalhr_ai.summary.gateway import Planner, build_model_planner
from divalhr_ai.summary.service import SummaryService

log = structlog.get_logger("divalhr_ai")

PUBLIC_STATUS_PATH = "/api/v1/system/status"


def create_app(
    settings: Settings | None = None,
    *,
    model_planner: Planner | None = None,
    verifier: ServiceTokenVerifier | None = None,
) -> FastAPI:
    """Build the FastAPI application.

    ``model_planner`` and ``verifier`` are test seams; in production both come from the settings.
    """
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
    app.state.summary_service = SummaryService(model_planner or build_model_planner(settings))
    service_verifier = verifier or build_verifier(settings)
    app.include_router(public_router)
    app.include_router(health_router)
    app.include_router(summary_router)
    # The browser may call only the public status endpoint: GET, explicit origins, no credentials.
    app.add_middleware(
        PathScopedCORSMiddleware,
        paths=[PUBLIC_STATUS_PATH],
        allow_origins=settings.cors_allowed_origins,
    )
    # Internal operations authenticate the calling service before the body is read.
    app.add_middleware(ServiceAuthMiddleware, verifier=service_verifier)
    app.add_middleware(CorrelationIdMiddleware)
    return app
