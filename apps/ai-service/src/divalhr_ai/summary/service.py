"""Orchestration: validate, plan (model first when configured), check, render, fall back."""

import time

import structlog

from divalhr_ai.summary.contract import GenerationMode, Plan, SummaryInput, SummaryOutput
from divalhr_ai.summary.gateway import (
    DeterministicPlanner,
    FailureClass,
    Planner,
    PlannerFailureError,
    token_band,
)
from divalhr_ai.summary.policy import PlanInvalidError, check_plan, validate_snapshot
from divalhr_ai.summary.templates import OutputInvalidError, render

log = structlog.get_logger("divalhr_ai.summary")


class SummaryUnavailableError(Exception):
    """Neither the model nor the deterministic planner produced a valid briefing."""


def latency_band(seconds: float) -> str:
    """A coarse latency band for telemetry."""
    for limit, label in ((0.1, "<=100ms"), (0.5, "<=500ms"), (1.0, "<=1s"), (4.0, "<=4s")):
        if seconds <= limit:
            return label
    return ">4s"


class SummaryService:
    """Generates a briefing from a governed snapshot; stores nothing."""

    def __init__(self, model: Planner | None, fallback: Planner | None = None) -> None:
        self._model = model
        self._fallback: Planner = fallback or DeterministicPlanner()

    async def generate(self, snapshot: SummaryInput) -> SummaryOutput:
        """Validate the snapshot, then plan and render; any model problem falls back.

        Raises :class:`SnapshotInvalidError` before any planner runs and
        :class:`SummaryUnavailableError` when even the deterministic briefing cannot be produced.
        Telemetry carries only bounded classes: never a count, an identifier, a prompt, an output
        or a provider response.
        """
        started = time.perf_counter()
        validate_snapshot(snapshot)
        failure: FailureClass | None = None
        tokens = (0, 0)
        output: SummaryOutput | None = None
        if self._model is not None:
            try:
                plan = await self._model.plan(snapshot)
                output = render(snapshot.language, check_plan(snapshot, plan), GenerationMode.MODEL)
            except PlannerFailureError as error:
                failure = error.failure
                tokens = (error.tokens_in, error.tokens_out)
            except PlanInvalidError:
                failure = FailureClass.INVALID_SELECTION
            except OutputInvalidError:
                failure = FailureClass.INVALID_SELECTION
        if output is None:
            output = await self._fallback_output(snapshot, started, failure)
        log.info(
            "operational_summary_generated",
            operation="generate-operational-summary",
            outcome="ok",
            language=snapshot.language.value,
            generation_mode=output.generation_mode.value,
            failure_class=failure.value if failure else None,
            latency_band=latency_band(time.perf_counter() - started),
            input_token_band=token_band(tokens[0]),
            output_token_band=token_band(tokens[1]),
            selected_facts=len(output.highlights),
        )
        return output

    async def _fallback_output(
        self, snapshot: SummaryInput, started: float, failure: FailureClass | None
    ) -> SummaryOutput:
        try:
            plan: Plan = await self._fallback.plan(snapshot)
            return render(snapshot.language, check_plan(snapshot, plan), GenerationMode.FALLBACK)
        except (PlannerFailureError, PlanInvalidError, OutputInvalidError) as error:
            log.error(
                "operational_summary_unavailable",
                operation="generate-operational-summary",
                outcome="unavailable",
                language=snapshot.language.value,
                failure_class=failure.value if failure else None,
                latency_band=latency_band(time.perf_counter() - started),
            )
            raise SummaryUnavailableError from error
