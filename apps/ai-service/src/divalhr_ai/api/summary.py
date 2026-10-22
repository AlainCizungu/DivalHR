"""The internal operational-summary operation (Core only; ADR 0027)."""

from typing import Any

from fastapi import APIRouter, Request, Response, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ValidationError

from divalhr_ai.summary.contract import SummaryInput, SummaryOutput
from divalhr_ai.summary.policy import SnapshotInvalidError
from divalhr_ai.summary.service import SummaryService, SummaryUnavailableError

MAX_BODY_BYTES = 32_768
CACHE_CONTROL = "private, no-store"


class InternalProblem(BaseModel):
    """The closed error body: a stable code and nothing else."""

    code: str


router = APIRouter(prefix="/api/v1/internal", tags=["internal"])


def _problem(code: str, http_status: int) -> JSONResponse:
    return JSONResponse(
        {"code": code}, status_code=http_status, headers={"Cache-Control": CACHE_CONTROL}
    )


@router.post(
    "/operational-summary",
    operation_id="generateOperationalSummary",
    response_model=SummaryOutput,
    response_model_by_alias=True,
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": SummaryInput.model_json_schema(by_alias=True)}
            },
        }
    },
    responses={
        400: {
            "model": InternalProblem,
            "description": "The body does not satisfy the closed input contract",
        },
        401: {"model": InternalProblem, "description": "Missing, expired or invalid service token"},
        403: {
            "model": InternalProblem,
            "description": "Wrong audience, wrong client or missing capability",
        },
        422: {
            "model": InternalProblem,
            "description": "The snapshot violates the MVP-100 invariants",
        },
        503: {"model": InternalProblem, "description": "No valid briefing could be produced"},
    },
)
async def generate(request: Request) -> Response:
    """Generate a grounded briefing. Authentication has already run (middleware)."""
    body = await request.body()
    if not body or len(body) > MAX_BODY_BYTES:
        return _problem("SUMMARY_INPUT_INVALID", status.HTTP_400_BAD_REQUEST)
    try:
        snapshot = SummaryInput.model_validate_json(body)
    except ValidationError:
        # Never echo the offending value: it is untrusted input.
        return _problem("SUMMARY_INPUT_INVALID", status.HTTP_400_BAD_REQUEST)
    service: SummaryService = request.app.state.summary_service
    try:
        output = await service.generate(snapshot)
    except SnapshotInvalidError:
        return _problem("SUMMARY_SNAPSHOT_INVALID", 422)
    except SummaryUnavailableError:
        return _problem("SUMMARY_UNAVAILABLE", status.HTTP_503_SERVICE_UNAVAILABLE)
    payload: dict[str, Any] = output.model_dump(by_alias=True, mode="json")
    return JSONResponse(payload, headers={"Cache-Control": CACHE_CONTROL})
