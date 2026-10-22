"""The closed Core to AI service contract (``packages/shared-contracts/schemas``).

Every model forbids unknown fields and refuses type coercion. No string field accepts free text:
each is an enum, a fixed pattern or a bounded integer. Mirrors the JSON Schemas, which the
evaluation tests prove against the same fixtures.
"""

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

FACT_ID_PATTERN = r"^f[1-9][0-9]?$"
AS_OF_PATTERN = r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z$"
MAX_FACTS = 11
MAX_SELECTED = 5
MAX_COUNT = 100_000

FactId = Annotated[str, StringConstraints(pattern=FACT_ID_PATTERN, max_length=3)]
Count = Annotated[int, Field(ge=0, le=MAX_COUNT)]


class Language(StrEnum):
    """The requested language: exactly English or French."""

    EN = "en"
    FR = "fr"


class ScopeKind(StrEnum):
    """Which MVP-100 audience the snapshot belongs to."""

    ORGANIZATION = "ORGANIZATION"
    TEAM = "TEAM"


class FactType(StrEnum):
    """The closed candidate-fact types (D101-3)."""

    NOT_CHECKED_IN = "NOT_CHECKED_IN"
    UNCOVERED_SHIFTS = "UNCOVERED_SHIFTS"
    OVERDUE_LEAVE = "OVERDUE_LEAVE"
    OVERDUE_TIMESHEETS = "OVERDUE_TIMESHEETS"
    LATE_CHECK_INS = "LATE_CHECK_INS"
    PENDING_LEAVE = "PENDING_LEAVE"
    PENDING_TIMESHEETS = "PENDING_TIMESHEETS"
    EXPECTED_SHIFTS = "EXPECTED_SHIFTS"
    PRESENT_SHIFTS = "PRESENT_SHIFTS"
    COMPLETED_SHIFTS = "COMPLETED_SHIFTS"
    SCHEDULED_SHIFTS = "SCHEDULED_SHIFTS"


class Severity(StrEnum):
    """A bounded severity band of a count."""

    NONE = "NONE"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class RouteId(StrEnum):
    """Allow-listed source-view identifiers; the browser maps them to authorized routes."""

    ATTENDANCE = "ATTENDANCE"
    SCHEDULES = "SCHEDULES"
    LEAVE_APPROVALS = "LEAVE_APPROVALS"
    TIMESHEETS = "TIMESHEETS"


class HeadlineCode(StrEnum):
    """The approved headline families (D101-6)."""

    OPERATIONS_WITHIN_RANGE = "OPERATIONS_WITHIN_RANGE"
    ATTENDANCE_NEEDS_ATTENTION = "ATTENDANCE_NEEDS_ATTENTION"
    COVERAGE_NEEDS_ATTENTION = "COVERAGE_NEEDS_ATTENTION"
    APPROVALS_NEED_ATTENTION = "APPROVALS_NEED_ATTENTION"
    SEVERAL_AREAS_NEED_ATTENTION = "SEVERAL_AREAS_NEED_ATTENTION"


class GenerationMode(StrEnum):
    """How the plan was produced; never the provider or the model."""

    MODEL = "MODEL"
    FALLBACK = "FALLBACK"


class LimitationCode(StrEnum):
    """The closed limitation statements."""

    RECORDED_FACTS_ONLY = "RECORDED_FACTS_ONLY"
    NO_CONCLUSION = "NO_CONCLUSION"
    REVIEW_SOURCES = "REVIEW_SOURCES"


class _Closed(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True, populate_by_name=True)


class Scope(_Closed):
    """The scope kind and whether each filter is present; never an identifier."""

    kind: ScopeKind
    site_filter: bool = Field(alias="siteFilter")
    legal_entity_filter: bool = Field(alias="legalEntityFilter")


class Thresholds(_Closed):
    """The fixed MVP-100 thresholds."""

    late_minutes: Literal[5] = Field(alias="lateMinutes")
    not_checked_in_grace_minutes: Literal[30] = Field(alias="notCheckedInGraceMinutes")
    leave_overdue_hours: Literal[48] = Field(alias="leaveOverdueHours")
    timesheet_overdue_hours: Literal[72] = Field(alias="timesheetOverdueHours")


class Shifts(_Closed):
    """Shift counts; ``expected + notCheckedIn + present + completed == scheduled``."""

    scheduled: Count
    expected: Count
    not_checked_in: Count = Field(alias="notCheckedIn")
    present: Count
    completed: Count
    late: Count


class Coverage(_Closed):
    """Uncovered operational slots (organization scope only)."""

    uncovered: Count


class Waiting(_Closed):
    """Approvals waiting; overdue is a subset of pending."""

    pending: Count
    overdue: Count


class Approvals(_Closed):
    """Leave and timesheet approvals waiting."""

    leave: Waiting
    timesheets: Waiting


class Fact(_Closed):
    """One candidate fact, selectable by its opaque per-request id."""

    fact_id: FactId = Field(alias="factId")
    type: FactType
    value: Count
    threshold_value: Literal[5, 30, 48, 72] | None = Field(alias="thresholdValue")
    severity: Severity
    route_id: RouteId = Field(alias="routeId")


class SummaryInput(_Closed):
    """The only data that crosses the model boundary (D101-3)."""

    schema_version: Literal[1] = Field(alias="schemaVersion")
    language: Language
    as_of_instant: str = Field(alias="asOfInstant", pattern=AS_OF_PATTERN, max_length=32)
    scope: Scope
    thresholds: Thresholds
    shifts: Shifts
    coverage: Coverage | None
    approvals: Approvals
    facts: Annotated[tuple[Fact, ...], Field(min_length=1, max_length=MAX_FACTS)]


class Plan(_Closed):
    """A planner's output: one headline code and at most five unique fact ids. No free text."""

    headline_code: HeadlineCode = Field(alias="headlineCode")
    fact_ids: Annotated[tuple[FactId, ...], Field(min_length=1, max_length=MAX_SELECTED)] = Field(
        alias="factIds"
    )


class Headline(_Closed):
    """The rendered headline."""

    code: HeadlineCode
    text: Annotated[str, Field(min_length=1, max_length=400)]


class Highlight(_Closed):
    """A rendered highlight bound to the selected fact."""

    fact_id: FactId = Field(alias="factId")
    fact_type: FactType = Field(alias="factType")
    severity: Severity
    route_id: RouteId = Field(alias="routeId")
    text: Annotated[str, Field(min_length=1, max_length=400)]


class Limitation(_Closed):
    """A closed bilingual limitation statement."""

    code: LimitationCode
    text: Annotated[str, Field(min_length=1, max_length=400)]


class SummaryOutput(_Closed):
    """What the AI service returns to Core."""

    language: Language
    generation_mode: GenerationMode = Field(alias="generationMode")
    template_version: Annotated[str, Field(pattern=r"^[a-z0-9.-]{1,32}$")] = Field(
        alias="templateVersion"
    )
    headline: Headline
    highlights: Annotated[tuple[Highlight, ...], Field(min_length=1, max_length=MAX_SELECTED)]
    limitations: Annotated[tuple[Limitation, ...], Field(min_length=3, max_length=3)]
