import asyncio
import json
from typing import Any

import pytest
from structlog.testing import capture_logs

from divalhr_ai.summary.contract import GenerationMode, Plan, SummaryInput
from divalhr_ai.summary.gateway import FailureClass, PlannerFailureError
from divalhr_ai.summary.policy import SnapshotInvalidError
from divalhr_ai.summary.service import SummaryService, SummaryUnavailableError, latency_band
from tests.summary_support import INVALID, apply_patch, case


def parse(payload: dict[str, Any]) -> SummaryInput:
    return SummaryInput.model_validate_json(json.dumps(payload))


class Counting:
    def __init__(self, outcome: Plan | Exception) -> None:
        self.outcome = outcome
        self.calls = 0

    async def plan(self, snapshot: SummaryInput) -> Plan:
        self.calls += 1
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


SNAP = case("many-critical-over-cap")["input"]


def generate(service: SummaryService, payload: dict[str, Any] = SNAP) -> Any:
    return asyncio.run(service.generate(parse(payload)))


def test_deterministic_mode_is_a_fallback_briefing() -> None:
    output = generate(SummaryService(None))
    assert output.generation_mode is GenerationMode.FALLBACK


def test_valid_model_plan_is_rendered_as_model() -> None:
    reordered = Plan.model_validate_json(
        '{"headlineCode":"SEVERAL_AREAS_NEED_ATTENTION","factIds":["f2","f1","f4","f3","f5"]}'
    )
    output = generate(SummaryService(Counting(reordered)))
    assert output.generation_mode is GenerationMode.MODEL
    assert [h.fact_id for h in output.highlights] == list(reordered.fact_ids)


@pytest.mark.parametrize("failure", list(FailureClass))
def test_every_provider_failure_falls_back_with_a_bounded_class(failure: FailureClass) -> None:
    with capture_logs() as logs:
        output = generate(SummaryService(Counting(PlannerFailureError(failure, 10, 5))))
    assert output.generation_mode is GenerationMode.FALLBACK
    event = next(e for e in logs if e["event"] == "operational_summary_generated")
    assert event["failure_class"] == failure.value
    assert event["generation_mode"] == "FALLBACK"


def test_plan_missing_a_critical_fact_falls_back() -> None:
    bad = Plan.model_validate_json(
        '{"headlineCode":"SEVERAL_AREAS_NEED_ATTENTION","factIds":["f6","f7","f8"]}'
    )
    output = generate(SummaryService(Counting(bad)))
    assert output.generation_mode is GenerationMode.FALLBACK
    assert {h.fact_id for h in output.highlights} >= {"f1", "f2", "f3", "f4"}


def test_deterministic_failure_is_unavailable() -> None:
    service = SummaryService(None, Counting(PlannerFailureError(FailureClass.TRANSPORT)))
    with pytest.raises(SummaryUnavailableError):
        generate(service)


@pytest.mark.parametrize(
    "entry", [e for e in INVALID if e["layer"] == "semantic"], ids=lambda e: e["id"]
)
def test_invalid_snapshot_never_reaches_the_planner(entry: dict[str, Any]) -> None:
    planner = Counting(PlannerFailureError(FailureClass.TIMEOUT))
    payload = apply_patch(case(entry["base"])["input"], entry["patch"])
    with pytest.raises(SnapshotInvalidError):
        generate(SummaryService(planner), payload)
    assert planner.calls == 0


def test_telemetry_is_bounded_and_carries_no_data() -> None:
    with capture_logs() as logs:
        output = generate(SummaryService(None))
    rendered = json.dumps(logs, default=str)
    for fragment in [h.text for h in output.highlights] + [
        output.headline.text,
        SNAP["asOfInstant"],
    ]:
        assert fragment not in rendered
    assert "factIds" not in rendered
    assert set(logs[0]) <= {
        "event",
        "log_level",
        "operation",
        "outcome",
        "language",
        "generation_mode",
        "failure_class",
        "latency_band",
        "input_token_band",
        "output_token_band",
        "selected_facts",
    }


def test_latency_bands() -> None:
    assert [latency_band(s) for s in (0.05, 0.3, 0.9, 3.0, 9.0)] == [
        "<=100ms",
        "<=500ms",
        "<=1s",
        "<=4s",
        ">4s",
    ]
