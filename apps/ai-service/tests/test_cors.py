from fastapi.testclient import TestClient

PREFLIGHT = {"Access-Control-Request-Method": "GET"}


def test_allows_explicit_origin_on_status(client: TestClient) -> None:
    response = client.options(
        "/api/v1/system/status", headers={"Origin": "http://localhost:5173", **PREFLIGHT}
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "http://localhost:5173"
    assert "access-control-allow-credentials" not in response.headers


def test_rejects_unknown_origin(client: TestClient) -> None:
    response = client.options(
        "/api/v1/system/status", headers={"Origin": "http://evil.example", **PREFLIGHT}
    )
    assert "access-control-allow-origin" not in response.headers
    simple = client.get("/api/v1/system/status", headers={"Origin": "http://evil.example"})
    assert "access-control-allow-origin" not in simple.headers


def test_non_public_paths_are_never_browser_readable(client: TestClient) -> None:
    for path in ("/health/live", "/health/ready"):
        response = client.get(path, headers={"Origin": "http://localhost:5173"})
        assert "access-control-allow-origin" not in response.headers


def test_only_get_is_allowed(client: TestClient) -> None:
    response = client.options(
        "/api/v1/system/status",
        headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST"},
    )
    assert response.status_code == 400
