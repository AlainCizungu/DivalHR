import json
import re
from typing import Any

import pytest

from divalhr_ai.summary.contract import (
    FactType,
    GenerationMode,
    Language,
    LimitationCode,
    SummaryInput,
)
from divalhr_ai.summary.policy import (
    PlanInvalidError,
    check_plan,
    deterministic_plan,
    validate_snapshot,
)
from divalhr_ai.summary.templates import (
    PROHIBITED,
    TEMPLATE_VERSION,
    OutputInvalidError,
    _check,
    render,
    render_fact,
)
from tests.summary_support import CASES, VOCABULARY, fixture, with_language

EXPECTED = fixture("expected-text.json")
PLURAL = fixture("pluralization.json")


def snapshot_of(item: dict[str, Any], language: str) -> SummaryInput:
    return SummaryInput.model_validate_json(json.dumps(with_language(item["input"], language)))


@pytest.mark.parametrize("language", ["en", "fr"])
@pytest.mark.parametrize("item", CASES, ids=[c["id"] for c in CASES])
def test_rendered_text_matches_the_reviewed_fixture(item: dict[str, Any], language: str) -> None:
    snapshot = snapshot_of(item, language)
    validate_snapshot(snapshot)
    output = render(
        snapshot.language,
        check_plan(snapshot, deterministic_plan(snapshot)),
        GenerationMode.FALLBACK,
    )
    expected = EXPECTED["cases"][item["id"]][language]
    assert output.headline.text == expected["headline"]
    assert [h.text for h in output.highlights] == expected["highlights"]
    assert [x.text for x in output.limitations] == expected["limitations"]
    assert output.template_version == TEMPLATE_VERSION == EXPECTED["templateVersion"]
    assert [x.code for x in output.limitations] == list(LimitationCode)


@pytest.mark.parametrize("language", ["en", "fr"])
@pytest.mark.parametrize("item", CASES, ids=[c["id"] for c in CASES])
def test_every_highlight_is_grounded_in_its_own_fact(item: dict[str, Any], language: str) -> None:
    snapshot = snapshot_of(item, language)
    output = render(
        snapshot.language, check_plan(snapshot, deterministic_plan(snapshot)), GenerationMode.MODEL
    )
    by_id = {f.fact_id: f for f in snapshot.facts}
    assert len(output.highlights) <= 5
    for highlight in output.highlights:
        fact = by_id[highlight.fact_id]
        assert (highlight.fact_type, highlight.severity, highlight.route_id) == (
            fact.type,
            fact.severity,
            fact.route_id,
        )
        numbers = {int(n) for n in re.findall(r"[0-9]+", highlight.text)}
        allowed = {fact.value}
        if fact.threshold_value is not None:
            allowed.add(fact.threshold_value)
        assert numbers <= allowed
        if fact.value > 0:
            assert fact.value in numbers


@pytest.mark.parametrize("language", [Language.EN, Language.FR])
@pytest.mark.parametrize("fact_type", list(FactType))
@pytest.mark.parametrize("count", [0, 1, 2, 5])
def test_pluralization_matches_the_reviewed_fixture(
    language: Language, fact_type: FactType, count: int
) -> None:
    from divalhr_ai.summary.policy import THRESHOLD

    base = snapshot_of(CASES[0], "en").facts[0]
    fact = base.model_copy(
        update={"type": fact_type, "value": count, "threshold_value": THRESHOLD[fact_type]}
    )
    assert (
        render_fact(language, fact) == PLURAL["types"][fact_type.value][language.value][str(count)]
    )


def test_prohibited_vocabulary_equals_the_fixture() -> None:
    assert {k: sorted(v) for k, v in VOCABULARY.items() if k in ("en", "fr")} == {
        lang.value: sorted(PROHIBITED[lang]) for lang in Language
    }


def test_no_template_contains_prohibited_vocabulary() -> None:
    for item in CASES:
        for language in ("en", "fr"):
            for text in [EXPECTED["cases"][item["id"]][language]["headline"]] + EXPECTED["cases"][
                item["id"]
            ][language]["highlights"]:
                assert not any(stem in text.lower() for stem in PROHIBITED[Language(language)])


def test_render_rejects_prohibited_wording_and_ungrounded_numbers() -> None:
    fact = (
        snapshot_of(CASES[0], "en")
        .facts[0]
        .model_copy(update={"value": 7, "threshold_value": None})
    )
    with pytest.raises(OutputInvalidError):
        _check(Language.EN, "This employee was absent.", None)
    with pytest.raises(OutputInvalidError):
        _check(Language.EN, "Several shifts need review.", fact)
    with pytest.raises(OutputInvalidError):
        _check(Language.EN, "17 shifts need review.", fact)
    _check(Language.EN, "7 shifts need review.", fact)


def test_unchecked_plans_cannot_be_rendered_by_the_service_path() -> None:
    snapshot = snapshot_of(CASES[0], "en")
    plan = deterministic_plan(snapshot).model_copy(update={"fact_ids": ("f99",)})
    with pytest.raises(PlanInvalidError):
        check_plan(snapshot, plan)
