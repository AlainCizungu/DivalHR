import uuid

import pytest
from fastapi.testclient import TestClient

from divalhr_ai.correlation import resolve


def test_echoes_safe_caller_value(client: TestClient) -> None:
    response = client.get("/api/v1/system/status", headers={"X-Correlation-Id": "smoke-test-0001"})
    assert response.headers["X-Correlation-Id"] == "smoke-test-0001"


def test_generates_when_absent(client: TestClient) -> None:
    response = client.get("/api/v1/system/status")
    assert uuid.UUID(response.headers["X-Correlation-Id"])


@pytest.mark.parametrize("unsafe", ["short", "x" * 65, "abc def ghij", "<script>alert(1)</script>"])
def test_replaces_unsafe_values(unsafe: str) -> None:
    assert uuid.UUID(resolve(unsafe))
