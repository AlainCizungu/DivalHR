import json
from typing import Any

import pytest
from pydantic import ValidationError

from divalhr_ai.summary.contract import Plan, SummaryInput
from divalhr_ai.summary.policy import SnapshotInvalidError, validate_snapshot
from tests.summary_support import (
    CASES,
    EVAL_DIR,
    INVALID,
    apply_patch,
    case,
    with_language,
)

SCHEMAS = EVAL_DIR.parents[2] / "schemas"


def parse(payload: dict[str, Any]) -> SummaryInput:
    return SummaryInput.model_validate_json(json.dumps(payload))


@pytest.mark.parametrize("language", ["en", "fr"])
@pytest.mark.parametrize("item", CASES, ids=[c["id"] for c in CASES])
def test_every_case_is_accepted_in_both_languages(item: dict[str, Any], language: str) -> None:
    snapshot = parse(with_language(item["input"], language))
    validate_snapshot(snapshot)
    assert snapshot.language.value == language


@pytest.mark.parametrize("entry", INVALID, ids=[e["id"] for e in INVALID])
def test_invalid_inputs_are_rejected_before_any_planner(entry: dict[str, Any]) -> None:
    payload = apply_patch(case(entry["base"])["input"], entry["patch"])
    if entry["layer"] == "schema":
        with pytest.raises(ValidationError):
            parse(payload)
    else:
        snapshot = parse(payload)
        with pytest.raises(SnapshotInvalidError):
            validate_snapshot(snapshot)


def test_plan_model_is_closed() -> None:
    plan = Plan.model_validate_json('{"headlineCode":"OPERATIONS_WITHIN_RANGE","factIds":["f1"]}')
    assert list(plan.fact_ids) == ["f1"]
    for body in (
        '{"headlineCode":"OPERATIONS_WITHIN_RANGE","factIds":["f1"],"text":"hi"}',
        '{"headlineCode":"OPERATIONS_WITHIN_RANGE","factIds":[]}',
        '{"headlineCode":"OPERATIONS_WITHIN_RANGE","factIds":["f1","f2","f3","f4","f5","f6"]}',
        '{"headlineCode":"free text","factIds":["f1"]}',
        '{"headlineCode":"OPERATIONS_WITHIN_RANGE","factIds":["name"]}',
    ):
        with pytest.raises(ValidationError):
            Plan.model_validate_json(body)


def test_input_model_and_json_schema_declare_the_same_properties() -> None:
    schema = json.loads((SCHEMAS / "operational-summary-input.schema.json").read_text("utf-8"))
    model = SummaryInput.model_json_schema(by_alias=True)
    assert set(schema["properties"]) == set(model["properties"])
    assert set(schema["required"]) == set(model["required"])
    defs = model["$defs"]
    assert set(schema["properties"]["shifts"]["properties"]) == set(defs["Shifts"]["properties"])
    assert set(schema["properties"]["thresholds"]["properties"]) == set(
        defs["Thresholds"]["properties"]
    )


def test_openapi_documents_the_internal_operation() -> None:
    from divalhr_ai.config import Settings
    from divalhr_ai.main import create_app

    app = create_app(Settings(environment="test", openapi_enabled=True))
    operation = app.openapi()["paths"]["/api/v1/internal/operational-summary"]["post"]
    assert operation["operationId"] == "generateOperationalSummary"
    assert {"400", "401", "403", "422", "503"} <= set(operation["responses"])
