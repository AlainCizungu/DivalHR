"""Public status (browser-visible) and internal health probes."""

from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter, Request, Response, status
from pydantic import BaseModel, Field

from divalhr_ai.config import Settings


class SystemStatus(BaseModel):
    """Mirrors SystemStatus in packages/shared-contracts/openapi/ai-service.yaml."""

    service: str = Field(examples=["ai-service"])
    status: Literal["UP", "DOWN"]
    version: str
    checked_at: datetime = Field(serialization_alias="checkedAt")


public_router = APIRouter(prefix="/api/v1/system", tags=["system"])
health_router = APIRouter(prefix="/health", include_in_schema=False)


def _ready(request: Request) -> bool:
    return bool(getattr(request.app.state, "ready", False))


@public_router.get(
    "/status",
    operation_id="getAiServiceStatus",
    response_model=SystemStatus,
    response_model_by_alias=True,
    responses={503: {"model": SystemStatus, "description": "Service is not ready"}},
)
def get_status(request: Request, response: Response) -> SystemStatus:
    """Public, non-sensitive AI Service status."""
    settings: Settings = request.app.state.settings
    ready = _ready(request)
    if not ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return SystemStatus(
        service="ai-service",
        status="UP" if ready else "DOWN",
        version=settings.version,
        checked_at=datetime.now(UTC),
    )


@health_router.get("/live")
def live() -> dict[str, str]:
    """Liveness probe (internal; not CORS-enabled)."""
    return {"status": "UP"}


@health_router.get("/ready")
def ready(request: Request, response: Response) -> dict[str, str]:
    """Readiness probe (internal; not CORS-enabled)."""
    if not _ready(request):
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"status": "DOWN"}
    return {"status": "UP"}
