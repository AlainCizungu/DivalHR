import asyncio
import json
from collections.abc import Callable
from typing import Any

import httpx2
import pytest

from divalhr_ai.summary.contract import SummaryInput
from divalhr_ai.summary.gateway import (
    CircuitBreaker,
    FailureClass,
    OpenAICompatiblePlanner,
    PlannerFailureError,
    plan_schema,
    token_band,
    user_message,
)
from tests.summary_support import case

SECRET = "sk-test-secret-value-1234567890"


def snapshot() -> SummaryInput:
    return SummaryInput.model_validate_json(json.dumps(case("many-critical-over-cap")["input"]))


def completion(content: Any, **choice: Any) -> dict[str, Any]:
    text = content if isinstance(content, str) else json.dumps(content)
    return {
        "choices": [{"message": {"content": text}, "finish_reason": "stop", **choice}],
        "usage": {"prompt_tokens": 120, "completion_tokens": 20},
    }


def planner(
    handler: Callable[[httpx2.Request], httpx2.Response],
    *,
    breaker: CircuitBreaker | None = None,
    deadline: float = 4.0,
) -> OpenAICompatiblePlanner:
    client = httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    return OpenAICompatiblePlanner(
        base_url="https://provider.example.test/v1/",
        model="m-1",
        api_key=SECRET,
        max_output_tokens=300,
        client=client,
        breaker=breaker,
        deadline=deadline,
    )


def run_plan(p: OpenAICompatiblePlanner) -> Any:
    return asyncio.run(p.plan(snapshot()))


def failure_of(p: OpenAICompatiblePlanner) -> FailureClass:
    with pytest.raises(PlannerFailureError) as raised:
        run_plan(p)
    assert SECRET not in str(raised.value)
    return raised.value.failure


def test_success_returns_only_a_plan() -> None:
    seen: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        seen.append(request)
        return httpx2.Response(
            200,
            json=completion(
                {"headlineCode": "SEVERAL_AREAS_NEED_ATTENTION", "factIds": ["f2", "f1"]}
            ),
        )

    plan = run_plan(planner(handler))
    assert list(plan.fact_ids) == ["f2", "f1"]
    assert len(seen) == 1
    request = seen[0]
    assert str(request.url) == "https://provider.example.test/v1/chat/completions"
    assert request.headers["authorization"] == f"Bearer {SECRET}"
    body = json.loads(request.content)
    assert body["temperature"] == 0
    assert body["max_tokens"] == 300
    assert body["response_format"]["json_schema"]["strict"] is True
    assert SECRET not in request.content.decode()


def test_request_contains_only_closed_values() -> None:
    snap = snapshot()
    message = json.loads(user_message(snap))
    assert set(message) == {"language", "scope", "facts"}
    assert set(message["facts"][0]) == {"id", "type", "value", "threshold", "severity"}
    text = user_message(snap)
    assert snap.as_of_instant not in text
    schema = plan_schema(snap)
    assert schema["properties"]["factIds"]["items"]["enum"] == [f.fact_id for f in snap.facts]


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (429, FailureClass.RATE_LIMITED),
        (500, FailureClass.PROVIDER_ERROR),
        (401, FailureClass.PROVIDER_ERROR),
    ],
)
def test_http_failures_are_classified_and_never_retried(
    status: int, expected: FailureClass
) -> None:
    calls: list[int] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        calls.append(1)
        return httpx2.Response(status, json={"error": {"message": f"echo {SECRET}"}})

    assert failure_of(planner(handler)) is expected
    assert len(calls) == 1


def test_timeouts_and_transport_errors() -> None:
    def timeout(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadTimeout("slow", request=request)

    def broken(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ConnectError("down", request=request)

    assert failure_of(planner(timeout)) is FailureClass.TIMEOUT
    assert failure_of(planner(broken)) is FailureClass.TRANSPORT


def test_deadline_is_enforced_even_when_the_transport_stalls() -> None:
    async def slow_client() -> None:
        class Slow(httpx2.AsyncBaseTransport):
            async def handle_async_request(self, request: httpx2.Request) -> httpx2.Response:
                await asyncio.sleep(1)
                return httpx2.Response(200, json={})

        p = OpenAICompatiblePlanner(
            base_url="https://provider.example.test/v1",
            model="m",
            api_key=SECRET,
            max_output_tokens=300,
            client=httpx2.AsyncClient(transport=Slow()),
            deadline=0.05,
        )
        with pytest.raises(PlannerFailureError) as raised:
            await p.plan(snapshot())
        assert raised.value.failure is FailureClass.TIMEOUT

    asyncio.run(slow_client())


@pytest.mark.parametrize(
    ("payload", "expected"),
    [
        ("not json", FailureClass.INVALID_JSON),
        ({"choices": []}, FailureClass.INVALID_JSON),
        (completion("not json"), FailureClass.INVALID_JSON),
        (completion({"headlineCode": "X", "factIds": ["f1"]}), FailureClass.INVALID_JSON),
        (
            completion(
                {"headlineCode": "SEVERAL_AREAS_NEED_ATTENTION", "factIds": ["f1"], "text": "x"}
            ),
            FailureClass.INVALID_JSON,
        ),
        (
            completion(
                {"headlineCode": "SEVERAL_AREAS_NEED_ATTENTION", "factIds": ["f1"]},
                finish_reason="length",
            ),
            FailureClass.INVALID_JSON,
        ),
        (completion("", finish_reason="content_filter"), FailureClass.REFUSAL),
    ],
)
def test_malformed_provider_answers(payload: Any, expected: FailureClass) -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        if isinstance(payload, str):
            return httpx2.Response(200, content=payload.encode())
        return httpx2.Response(200, json=payload)

    assert failure_of(planner(handler)) is expected


def test_explicit_refusal() -> None:
    body = {"choices": [{"message": {"content": None, "refusal": "no"}, "finish_reason": "stop"}]}
    assert failure_of(planner(lambda r: httpx2.Response(200, json=body))) is FailureClass.REFUSAL


def test_circuit_opens_after_three_failures_and_stops_calling() -> None:
    calls: list[int] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        calls.append(1)
        return httpx2.Response(500)

    p = planner(handler)
    for _ in range(3):
        assert failure_of(p) is FailureClass.PROVIDER_ERROR
    assert failure_of(p) is FailureClass.CIRCUIT_OPEN
    assert len(calls) == 3


def test_circuit_half_opens_after_cooldown_and_closes_on_success() -> None:
    breaker = CircuitBreaker(failures=2, cooldown=0.0)
    breaker.failure()
    breaker.failure()
    assert breaker.allow() is True  # cooldown elapsed: one trial
    breaker.failure()
    assert breaker.allow() is True  # zero cooldown trial again
    breaker.success()
    assert breaker.allow() is True


def test_open_circuit_blocks_until_the_cooldown() -> None:
    breaker = CircuitBreaker(failures=1, cooldown=60.0)
    breaker.failure()
    assert breaker.allow() is False


def test_token_bands() -> None:
    assert [token_band(n) for n in (0, 50, 200, 900, 5000)] == [
        "0",
        "<=100",
        "<=300",
        "<=1000",
        ">1000",
    ]
