from fastapi.testclient import TestClient

from divalhr_ai.config import Settings
from divalhr_ai.main import create_app


def test_status_is_public_and_up_after_startup(client: TestClient) -> None:
    response = client.get("/api/v1/system/status")
    assert response.status_code == 200
    body = response.json()
    assert body["service"] == "ai-service"
    assert body["status"] == "UP"
    assert body["version"] == "9.9.9"
    assert set(body) == {"service", "status", "version", "checkedAt"}


def test_status_reports_down_before_startup(settings: Settings) -> None:
    client = TestClient(create_app(settings))  # lifespan not started: not ready
    response = client.get("/api/v1/system/status")
    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"
    assert client.get("/health/ready").status_code == 503


def test_health_probes(client: TestClient) -> None:
    assert client.get("/health/live").json() == {"status": "UP"}
    assert client.get("/health/ready").json() == {"status": "UP"}


def test_interactive_docs_and_schema_disabled_by_default(client: TestClient) -> None:
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
    assert client.get("/openapi.json").status_code == 404
