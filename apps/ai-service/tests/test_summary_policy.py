import json
from typing import Any

import pytest
from pydantic import ValidationError

from divalhr_ai.summary.contract import HeadlineCode, Plan, SummaryInput
from divalhr_ai.summary.policy import (
    PlanInvalidError,
    band,
    check_plan,
    deterministic_plan,
    expected_headline,
    mandatory_ids,
    ranked,
)
from tests.summary_support import CASES, PLANS, case


def parse(payload: dict[str, Any]) -> SummaryInput:
    return SummaryInput.model_validate_json(json.dumps(payload))


@pytest.mark.parametrize("item", CASES, ids=[c["id"] for c in CASES])
def test_deterministic_plan_matches_the_oracle(item: dict[str, Any]) -> None:
    snapshot = parse(item["input"])
    expected = item["expected"]
    plan = deterministic_plan(snapshot)
    by_id = {f.fact_id: f for f in snapshot.facts}
    assert plan.headline_code.value == expected["headlineCode"]
    assert list(plan.fact_ids) == expected["factIds"]
    assert sorted(by_id[i].type.value for i in plan.fact_ids) == sorted(expected["selectedTypes"])
    assert mandatory_ids(snapshot) == set(expected["mandatoryFactIds"])
    assert expected_headline(snapshot) is plan.headline_code
    assert [f.fact_id for f in ranked(snapshot)][: len(plan.fact_ids)] == list(plan.fact_ids)
    check_plan(snapshot, plan)


@pytest.mark.parametrize(
    ("value", "expected"),
    [(0, "NONE"), (1, "LOW"), (2, "LOW"), (3, "MEDIUM"), (9, "MEDIUM"), (10, "HIGH")],
)
def test_severity_bands_are_stable(value: int, expected: str) -> None:
    assert band(value).value == expected


@pytest.mark.parametrize("entry", PLANS, ids=[p["id"] for p in PLANS])
def test_candidate_plans(entry: dict[str, Any]) -> None:
    snapshot = parse(case("many-critical-over-cap")["input"])
    body = json.dumps(entry["plan"])
    if entry["expect"] == "valid":
        check_plan(snapshot, Plan.model_validate_json(body))
        return
    try:
        plan = Plan.model_validate_json(body)
    except ValidationError:
        return
    with pytest.raises(PlanInvalidError):
        check_plan(snapshot, plan)


def test_a_model_plan_cannot_change_the_headline_family() -> None:
    snapshot = parse(case("one-not-checked-in")["input"])
    plan = Plan(headlineCode=HeadlineCode.OPERATIONS_WITHIN_RANGE, factIds=("f1",))
    with pytest.raises(PlanInvalidError):
        check_plan(snapshot, plan)
