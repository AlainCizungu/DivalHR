"""Versioned, reviewed bilingual templates and the renderer (D101-6).

All factual text comes from here: a template, a number and a threshold taken from the governed
snapshot. A model can influence only which facts are selected and in what order. Wording is neutral
and factual (ADR 0016): it never says that a person is absent, at fault or subject to any decision.
"""

import re

from divalhr_ai.summary.contract import (
    Fact,
    FactType,
    GenerationMode,
    Headline,
    HeadlineCode,
    Highlight,
    Language,
    Limitation,
    LimitationCode,
    SummaryOutput,
)
from divalhr_ai.summary.policy import CheckedPlan

TEMPLATE_VERSION = "ops-summary-1"

H = HeadlineCode
T = FactType

HEADLINES: dict[Language, dict[HeadlineCode, str]] = {
    Language.EN: {
        H.OPERATIONS_WITHIN_RANGE: "Recorded operations are within the expected range.",
        H.ATTENDANCE_NEEDS_ATTENTION: "Attendance records need attention.",
        H.COVERAGE_NEEDS_ATTENTION: "Shift coverage needs attention.",
        H.APPROVALS_NEED_ATTENTION: "Approvals need attention.",
        H.SEVERAL_AREAS_NEED_ATTENTION: "Several areas need attention.",
    },
    Language.FR: {
        H.OPERATIONS_WITHIN_RANGE: "Les opérations enregistrées sont dans la plage attendue.",
        H.ATTENDANCE_NEEDS_ATTENTION: "Les pointages enregistrés nécessitent de l’attention.",
        H.COVERAGE_NEEDS_ATTENTION: "La couverture des quarts nécessite de l’attention.",
        H.APPROVALS_NEED_ATTENTION: "Les approbations nécessitent de l’attention.",
        H.SEVERAL_AREAS_NEED_ATTENTION: "Plusieurs domaines nécessitent de l’attention.",
    },
}

#: (singular, plural) per fact type; ``{n}`` is the count and ``{t}`` the threshold, both taken from
#: the snapshot. English uses the singular for exactly 1; French for 0 and 1.
HIGHLIGHTS: dict[Language, dict[FactType, tuple[str, str]]] = {
    Language.EN: {
        T.NOT_CHECKED_IN: (
            "{n} shift has no check-in recorded more than {t} minutes after its start.",
            "{n} shifts have no check-in recorded more than {t} minutes after their start.",
        ),
        T.UNCOVERED_SHIFTS: (
            "{n} shift has no assigned worker.",
            "{n} shifts have no assigned worker.",
        ),
        T.OVERDUE_LEAVE: (
            "{n} leave request has been pending for more than {t} hours.",
            "{n} leave requests have been pending for more than {t} hours.",
        ),
        T.OVERDUE_TIMESHEETS: (
            "{n} timesheet has been submitted for more than {t} hours without a decision.",
            "{n} timesheets have been submitted for more than {t} hours without a decision.",
        ),
        T.LATE_CHECK_INS: (
            "{n} check-in was recorded more than {t} minutes after the shift start.",
            "{n} check-ins were recorded more than {t} minutes after the shift start.",
        ),
        T.PENDING_LEAVE: (
            "{n} leave request is pending, within the {t}-hour threshold.",
            "{n} leave requests are pending, within the {t}-hour threshold.",
        ),
        T.PENDING_TIMESHEETS: (
            "{n} timesheet is pending, within the {t}-hour threshold.",
            "{n} timesheets are pending, within the {t}-hour threshold.",
        ),
        T.EXPECTED_SHIFTS: (
            "{n} shift is awaiting a check-in within the first {t} minutes after its start.",
            "{n} shifts are awaiting a check-in within the first {t} minutes after their start.",
        ),
        T.PRESENT_SHIFTS: (
            "{n} shift is in progress with a check-in recorded.",
            "{n} shifts are in progress with a check-in recorded.",
        ),
        T.COMPLETED_SHIFTS: (
            "{n} shift is completed with check-in and check-out recorded.",
            "{n} shifts are completed with check-in and check-out recorded.",
        ),
        T.SCHEDULED_SHIFTS: (
            "{n} shift is scheduled today in this scope.",
            "{n} shifts are scheduled today in this scope.",
        ),
    },
    Language.FR: {
        T.NOT_CHECKED_IN: (
            "{n} quart n’a aucun pointage d’arrivée enregistré plus de {t} minutes après son début.",
            "{n} quarts n’ont aucun pointage d’arrivée enregistré plus de {t} minutes après leur début.",
        ),
        T.UNCOVERED_SHIFTS: (
            "{n} quart n’a aucune personne affectée.",
            "{n} quarts n’ont aucune personne affectée.",
        ),
        T.OVERDUE_LEAVE: (
            "{n} demande de congé est en attente depuis plus de {t} heures.",
            "{n} demandes de congé sont en attente depuis plus de {t} heures.",
        ),
        T.OVERDUE_TIMESHEETS: (
            "{n} feuille de temps est soumise depuis plus de {t} heures sans décision.",
            "{n} feuilles de temps sont soumises depuis plus de {t} heures sans décision.",
        ),
        T.LATE_CHECK_INS: (
            "{n} pointage d’arrivée a été enregistré plus de {t} minutes après le début du quart.",
            "{n} pointages d’arrivée ont été enregistrés plus de {t} minutes après le début du quart.",
        ),
        T.PENDING_LEAVE: (
            "{n} demande de congé est en attente, dans le seuil de {t} heures.",
            "{n} demandes de congé sont en attente, dans le seuil de {t} heures.",
        ),
        T.PENDING_TIMESHEETS: (
            "{n} feuille de temps est en attente, dans le seuil de {t} heures.",
            "{n} feuilles de temps sont en attente, dans le seuil de {t} heures.",
        ),
        T.EXPECTED_SHIFTS: (
            "{n} quart attend un pointage d’arrivée dans les {t} premières minutes après son début.",
            "{n} quarts attendent un pointage d’arrivée dans les {t} premières minutes après leur début.",
        ),
        T.PRESENT_SHIFTS: (
            "{n} quart est en cours avec un pointage d’arrivée enregistré.",
            "{n} quarts sont en cours avec un pointage d’arrivée enregistré.",
        ),
        T.COMPLETED_SHIFTS: (
            "{n} quart est terminé avec pointages d’arrivée et de départ enregistrés.",
            "{n} quarts sont terminés avec pointages d’arrivée et de départ enregistrés.",
        ),
        T.SCHEDULED_SHIFTS: (
            "{n} quart est planifié aujourd’hui dans ce périmètre.",
            "{n} quarts sont planifiés aujourd’hui dans ce périmètre.",
        ),
    },
}

#: The only count that can be zero in a highlight.
NO_SHIFTS: dict[Language, str] = {
    Language.EN: "No shifts are scheduled today in this scope.",
    Language.FR: "Aucun quart n’est planifié aujourd’hui dans ce périmètre.",
}

LIMITATIONS: dict[Language, dict[LimitationCode, str]] = {
    Language.EN: {
        LimitationCode.RECORDED_FACTS_ONLY: (
            "This briefing describes recorded operational facts only."
        ),
        LimitationCode.NO_CONCLUSION: "It is not a leave, pay, disciplinary or legal conclusion.",
        LimitationCode.REVIEW_SOURCES: "Review the linked source records before acting.",
    },
    Language.FR: {
        LimitationCode.RECORDED_FACTS_ONLY: (
            "Ce résumé décrit uniquement des faits opérationnels enregistrés."
        ),
        LimitationCode.NO_CONCLUSION: (
            "Il ne constitue pas une conclusion en matière de congé, de paie, de discipline ou de droit."
        ),
        LimitationCode.REVIEW_SOURCES: "Consultez les dossiers sources liés avant d’agir.",
    },
}

#: Case-insensitive stems that must never appear in a headline or highlight. Kept equal to the
#: shared evaluation vocabulary (``prohibited-vocabulary.json``) by a test.
PROHIBITED: dict[Language, tuple[str, ...]] = {
    Language.EN: (
        "absent",
        "absence",
        "fault",
        "blame",
        "noncompl",
        "non-compl",
        "underperform",
        "poor performance",
        "fraud",
        "disciplin",
        "terminat",
        "dismiss",
        "fire ",
        "deduct",
        "sanction",
        "penal",
        "lawsuit",
        "legal action",
        "medical",
        "diagnos",
        "loan",
        "lending",
        "credit",
        "salary cut",
        "misconduct",
    ),
    Language.FR: (
        "absent",
        "absence",
        "faute",
        "blâm",
        "blam",
        "non conforme",
        "non-conforme",
        "sous-perform",
        "mauvaise performance",
        "fraude",
        "disciplin",
        "licenci",
        "renvoi",
        "retenue",
        "sanction",
        "pénalit",
        "penalit",
        "poursuite",
        "action en justice",
        "médical",
        "medical",
        "diagnostic",
        "prêt",
        "pret ",
        "crédit",
        "credit",
        "inconduite",
    ),
}


class OutputInvalidError(ValueError):
    """A rendered text fails the grounding or vocabulary checks; never carries the text."""


def _plural_is_one(language: Language, count: int) -> bool:
    return count == 1 if language is Language.EN else count in (0, 1)


def render_fact(language: Language, fact: Fact) -> str:
    """One highlight: a reviewed template filled only with the fact's own value and threshold."""
    if fact.type is T.SCHEDULED_SHIFTS and fact.value == 0:
        return NO_SHIFTS[language]
    singular, plural = HIGHLIGHTS[language][fact.type]
    template = singular if _plural_is_one(language, fact.value) else plural
    return template.format(n=fact.value, t=fact.threshold_value)


def _contains_number(text: str, number: int) -> bool:
    return re.search(rf"(?<![0-9]){number}(?![0-9])", text) is not None


def _check(language: Language, text: str, fact: Fact | None) -> None:
    lowered = text.lower()
    if any(stem in lowered for stem in PROHIBITED[language]):
        raise OutputInvalidError("vocabulary")
    if fact is not None:
        if fact.value > 0 and not _contains_number(text, fact.value):
            raise OutputInvalidError("value")
        if fact.threshold_value is not None and not _contains_number(text, fact.threshold_value):
            raise OutputInvalidError("threshold")


def render(language: Language, plan: CheckedPlan, mode: GenerationMode) -> SummaryOutput:
    """Render the briefing and prove every highlight is grounded and neutral."""
    headline_text = HEADLINES[language][plan.headline]
    _check(language, headline_text, None)
    highlights = []
    for fact in plan.facts:
        text = render_fact(language, fact)
        _check(language, text, fact)
        highlights.append(
            Highlight(
                factId=fact.fact_id,
                factType=fact.type,
                severity=fact.severity,
                routeId=fact.route_id,
                text=text,
            )
        )
    return SummaryOutput(
        language=language,
        generationMode=mode,
        templateVersion=TEMPLATE_VERSION,
        headline=Headline(code=plan.headline, text=headline_text),
        highlights=tuple(highlights),
        limitations=tuple(
            Limitation(code=code, text=LIMITATIONS[language][code]) for code in LimitationCode
        ),
    )
